// 📄 GPSTesterMovementVerifier.kt
package com.vectornav.app.testing

import android.graphics.PointF
import kotlin.math.*

class GPSTesterMovementVerifier(
    private val getViewDimensions: () -> Pair<Int, Int>,
    private val getDisplayMetrics: () -> Float,
    private val getUserPos: () -> PointF,
    private val updateCallback: (String) -> Unit
) {

    private val movementHistory = mutableListOf<MovementRecord>()
    private var previousUserPosition = PointF(0f, 0f)

    fun verifyMovement(
        startLat: Double, startLon: Double,
        currentLat: Double, currentLon: Double,
        bearing: Float, targetDistance: Int,
        simulatedDistance: Float,
        navigationCalculator: com.vectornav.app.NavigationCalculator
    ): MovementRecord {

        val currentPos = getCurrentUserPosition()
        val expectedPos = calculateExpectedPosition(
            startLat, startLon, currentLat, currentLon,
            bearing, targetDistance, simulatedDistance, navigationCalculator
        )
        val shouldMove = shouldIconMove(simulatedDistance)

        val actualMovementDistance = calculateDistance(previousUserPosition, currentPos)
        val actuallyMoved = actualMovementDistance > 5f

        val record = MovementRecord(
            timestamp = System.currentTimeMillis(),
            expectedPos = expectedPos,
            actualPos = currentPos,
            shouldMove = shouldMove,
            actuallyMoved = actuallyMoved,
            movementDistance = actualMovementDistance
        )

        movementHistory.add(record)
        if (movementHistory.size > 10) {
            movementHistory.removeAt(0)
        }

        previousUserPosition = PointF(currentPos.x, currentPos.y)
        updateMovementDisplay(record)

        return record
    }

    private fun calculateExpectedPosition(
        startLat: Double, startLon: Double,
        currentLat: Double, currentLon: Double,
        bearing: Float, targetDistance: Int,
        simulatedDistance: Float,
        navigationCalculator: com.vectornav.app.NavigationCalculator
    ): PointF {

        val (viewWidth, viewHeight) = getViewDimensions()

        if (viewWidth == 0 || viewHeight == 0) return PointF(0f, 0f)

        val distanceFromStart = navigationCalculator.calculateDistance(startLat, startLon, currentLat, currentLon)
        val currentBearing = navigationCalculator.calculateBearing(startLat, startLon, currentLat, currentLon)

        val bearingRad = Math.toRadians(bearing.toDouble())
        val currentBearingRad = Math.toRadians(currentBearing.toDouble())

        val alongTrack = distanceFromStart * cos(currentBearingRad - bearingRad)
        val crossTrack = distanceFromStart * sin(currentBearingRad - bearingRad)

        val dpToPx = getDisplayMetrics()
        val reservedTop = 80 * dpToPx
        val reservedBottom = 140 * dpToPx
        val availableHeight = viewHeight - reservedTop - reservedBottom

        val maxDistance = maxOf(targetDistance.toFloat(), abs(alongTrack).toFloat(), abs(crossTrack).toFloat() * 2)
        val viewScale = if (maxDistance > 0) (availableHeight * 0.8f) / maxDistance else 1f

        val startX = viewWidth / 2f
        val startY = reservedTop + availableHeight - 40 * dpToPx

        return PointF(
            startX + (crossTrack * viewScale).toFloat(),
            startY - (alongTrack * viewScale).toFloat()
        )
    }

    private fun getCurrentUserPosition(): PointF {
        return try {
            getUserPos()  // Use the provided function
        } catch (e: Exception) {
            val (viewWidth, viewHeight) = getViewDimensions()
            PointF(viewWidth / 2f, viewHeight / 2f)  // Fallback
        }
    }

    private fun shouldIconMove(simulatedDistance: Float): Boolean {
        val (viewWidth, _) = getViewDimensions()
        return simulatedDistance > 1f && viewWidth > 0
    }

    private fun calculateDistance(point1: PointF, point2: PointF): Float {
        val dx = point2.x - point1.x
        val dy = point2.y - point1.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun updateMovementDisplay(record: MovementRecord) {
        val status = when {
            !record.shouldMove && !record.actuallyMoved -> "✅ CORRECT - No movement expected or detected"
            record.shouldMove && record.actuallyMoved -> "✅ CORRECT - Movement expected and detected (${record.movementDistance.format(0)}px)"
            record.shouldMove && !record.actuallyMoved -> "❌ FAILED - Should move but icon didn't move"
            !record.shouldMove && record.actuallyMoved -> "⚠️ UNEXPECTED - Icon moved when it shouldn't have"
            else -> "❓ UNKNOWN STATE"
        }

        val recentRecords = movementHistory.takeLast(5)
        val correctCount = recentRecords.count { record ->
            (record.shouldMove && record.actuallyMoved) || (!record.shouldMove && !record.actuallyMoved)
        }
        val successRate = if (recentRecords.isNotEmpty()) (correctCount * 100) / recentRecords.size else 0

        val movementInfo = buildString {
            append("🎯 MOVEMENT DETECTION:\n")
            append("Status: $status\n")
            append("Expected: (${record.expectedPos.x.format(0)}, ${record.expectedPos.y.format(0)}) ")
            append("Actual: (${record.actualPos.x.format(0)}, ${record.actualPos.y.format(0)})\n")
            append("Success Rate: ${successRate}% (last ${recentRecords.size} tests)")
        }

        updateCallback(movementInfo)
    }

    fun generateReport(): String {
        val correctMovements = movementHistory.count {
            (it.shouldMove && it.actuallyMoved) || (!it.shouldMove && !it.actuallyMoved)
        }
        val successRate = if (movementHistory.isNotEmpty()) {
            (correctMovements * 100) / movementHistory.size
        } else 0

        return buildString {
            append("📊 MOVEMENT TEST REPORT:\n")
            append("Tests Run: ${movementHistory.size}\n")
            append("Success Rate: ${successRate}%\n")
            append("Correct: $correctMovements/${movementHistory.size}\n")

            val shouldMoveButDidnt = movementHistory.count { it.shouldMove && !it.actuallyMoved }
            val shouldntMoveButDid = movementHistory.count { !it.shouldMove && it.actuallyMoved }

            if (shouldMoveButDidnt > 0) {
                append("❌ ICON STUCK: $shouldMoveButDidnt times\n")
            }
            if (shouldntMoveButDid > 0) {
                append("⚠️ GHOST MOVEMENT: $shouldntMoveButDid times\n")
            }
            if (correctMovements == movementHistory.size && movementHistory.size > 0) {
                append("✅ ALL TESTS PASSED\n")
            }
        }
    }

    fun getSuccessRate(): Int {
        val correctMovements = movementHistory.count {
            (it.shouldMove && it.actuallyMoved) || (!it.shouldMove && !it.actuallyMoved)
        }
        return if (movementHistory.isNotEmpty()) {
            (correctMovements * 100) / movementHistory.size
        } else 0
    }

    fun getTestCount(): Int = movementHistory.size

    fun clearHistory() {
        movementHistory.clear()
        previousUserPosition = PointF(0f, 0f)
    }

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)
}