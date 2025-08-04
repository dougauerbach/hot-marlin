package com.vectornav.app.testing

import com.vectornav.app.NavigationCalculator
import kotlin.math.*

class GPSTesterCompassVerifier(
    private val navigationCalculator: NavigationCalculator,
    private val updateCallback: (String) -> Unit
) {

    private val compassVerificationHistory = mutableListOf<CompassVerificationRecord>()

    fun testDestinationCalculation(
        startLat: Double,
        startLon: Double,
        compassAzimuth: Float,
        targetDistance: Int
    ) {
        val expectedDestination = calculateExpectedDestination(startLat, startLon, compassAzimuth, targetDistance)
        val actualDestination = navigationCalculator.calculateDestination(startLat, startLon, compassAzimuth, targetDistance.toFloat())

        val distanceError = navigationCalculator.calculateDistance(
            expectedDestination.first, expectedDestination.second,
            actualDestination.first, actualDestination.second
        )

        val expectedBearing = navigationCalculator.calculateBearing(
            startLat, startLon, expectedDestination.first, expectedDestination.second
        )
        val actualBearing = navigationCalculator.calculateBearing(
            startLat, startLon, actualDestination.first, actualDestination.second
        )
        val bearingError = abs(expectedBearing - actualBearing).let { error ->
            if (error > 180f) 360f - error else error
        }

        val isAccurate = distanceError < 5f && bearingError < 2f

        val compassRecord = CompassVerificationRecord(
            timestamp = System.currentTimeMillis(),
            startLat = startLat,
            startLon = startLon,
            compassAzimuth = compassAzimuth,
            targetDistance = targetDistance,
            expectedDestLat = expectedDestination.first,
            expectedDestLon = expectedDestination.second,
            actualDestLat = actualDestination.first,
            actualDestLon = actualDestination.second,
            destinationAccurate = isAccurate,
            distanceError = distanceError,
            bearingError = bearingError
        )

        compassVerificationHistory.add(compassRecord)
        if (compassVerificationHistory.size > 15) {
            compassVerificationHistory.removeAt(0)
        }

        updateCompassVerificationDisplay(compassRecord)
    }

    private fun calculateExpectedDestination(startLat: Double, startLon: Double, bearing: Float, distance: Int): Pair<Double, Double> {
        val earthRadius = 6371000.0
        val bearingRad = Math.toRadians(bearing.toDouble())
        val startLatRad = Math.toRadians(startLat)
        val startLonRad = Math.toRadians(startLon)

        val endLatRad = asin(
            sin(startLatRad) * cos(distance / earthRadius) +
                    cos(startLatRad) * sin(distance / earthRadius) * cos(bearingRad)
        )

        val endLonRad = startLonRad + atan2(
            sin(bearingRad) * sin(distance / earthRadius) * cos(startLatRad),
            cos(distance / earthRadius) - sin(startLatRad) * sin(endLatRad)
        )

        return Pair(Math.toDegrees(endLatRad), Math.toDegrees(endLonRad))
    }

    private fun updateCompassVerificationDisplay(record: CompassVerificationRecord) {
        val status = if (record.destinationAccurate) "✅ ACCURATE" else "❌ INACCURATE"
        val compassDirection = getCompassDirection(record.compassAzimuth)

        val successRate = compassVerificationHistory.count { it.destinationAccurate } * 100 /
                kotlin.math.max(compassVerificationHistory.size, 1)

        val displayText = buildString {
            append("🧭 COMPASS/DESTINATION TEST:\n")
            append("Direction: $compassDirection (${record.compassAzimuth.toInt()}°) - $status\n")
            append("Distance Error: ${record.distanceError.format(1)}m, Bearing Error: ${record.bearingError.format(1)}°\n")
            append("Success Rate: ${successRate}% (${compassVerificationHistory.size} tests)")
        }

        updateCallback(displayText)
    }

    fun testAllCompassDirections(postDelayed: ((() -> Unit), Long) -> Unit, onComplete: () -> Unit) {
        val directions = listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)

        directions.forEachIndexed { index, direction ->
            postDelayed({
                // This would trigger a compass direction change in the main controller
            }, (index * 800).toLong())
        }

        postDelayed({
            onComplete()
        }, (directions.size * 800 + 500).toLong())
    }

    fun testDifferentDistances(
        originalDistance: Int,
        postDelayed: ((() -> Unit), Long) -> Unit,
        onDistanceChange: (Int) -> Unit,
        onComplete: () -> Unit
    ) {
        val distances = listOf(10, 25, 50, 100, 200)

        distances.forEachIndexed { index, distance ->
            postDelayed({
                onDistanceChange(distance)
            }, (index * 600).toLong())
        }

        postDelayed({
            onDistanceChange(originalDistance)
            onComplete()
        }, (distances.size * 600 + 200).toLong())
    }

    fun compareCalculationMethods(): String {
        val testCases = listOf(
            Triple(47.4979, -122.3668, 90f),   // Burien, WA pointing East
            Triple(40.7589, -73.9851, 0f),    // New York pointing North
            Triple(34.0522, -118.2437, 180f), // Los Angeles pointing South
            Triple(51.5074, -0.1278, 270f)    // London pointing West
        )

        val comparisonResults = mutableListOf<String>()

        testCases.forEach { (lat, lon, bearing) ->
            val expected = calculateExpectedDestination(lat, lon, bearing, 100)
            val actual = navigationCalculator.calculateDestination(lat, lon, bearing, 100f)

            val error = navigationCalculator.calculateDistance(
                expected.first, expected.second,
                actual.first, actual.second
            )

            comparisonResults.add("${getCompassDirection(bearing)}: ${error.format(2)}m error")
        }

        val avgError = comparisonResults.map { it.substringAfter(": ").substringBefore("m").toFloat() }.average().toFloat()

        return buildString {
            append("🔄 CALCULATION METHOD COMPARISON:\n")
            comparisonResults.forEach { append("$it\n") }
            append("Average Error: ${avgError.format(2)}m\n")
            append("Status: ${if (avgError < 5.0) "✅ EXCELLENT" else if (avgError < 15.0) "⚠️ ACCEPTABLE" else "❌ POOR"}")
        }
    }

    fun generateReport(): String {
        val totalTests = compassVerificationHistory.size
        val accurateTests = compassVerificationHistory.count { it.destinationAccurate }
        val successRate = if (totalTests > 0) (accurateTests * 100) / totalTests else 0

        val avgDistanceError = if (compassVerificationHistory.isNotEmpty()) {
            compassVerificationHistory.map { it.distanceError }.average().toFloat()
        } else 0f
        val avgBearingError = if (compassVerificationHistory.isNotEmpty()) {
            compassVerificationHistory.map { it.bearingError }.average().toFloat()
        } else 0f

        return buildString {
            append("📊 COMPASS TEST REPORT:\n")
            append("Tests: $totalTests, Success: ${successRate}%\n")
            append("Avg Distance Error: ${avgDistanceError.format(2)}m\n")
            append("Avg Bearing Error: ${avgBearingError.format(2)}°\n")

            val overallGrade = when {
                successRate >= 95 && avgDistanceError < 3f -> "✅ EXCELLENT"
                successRate >= 85 && avgDistanceError < 10f -> "⚠️ GOOD"
                successRate >= 70 -> "🔧 NEEDS WORK"
                else -> "❌ CRITICAL"
            }
            append("Overall: $overallGrade")
        }
    }

    private fun getCompassDirection(azimuth: Float): String {
        return when (((azimuth + 22.5f) % 360f).toInt() / 45) {
            0 -> "North"
            1 -> "Northeast"
            2 -> "East"
            3 -> "Southeast"
            4 -> "South"
            5 -> "Southwest"
            6 -> "West"
            7 -> "Northwest"
            else -> "North"
        }
    }

    fun getSuccessRate(): Int {
        return compassVerificationHistory.count { it.destinationAccurate } * 100 /
                kotlin.math.max(compassVerificationHistory.size, 1)
    }

    fun getTestCount(): Int = compassVerificationHistory.size

    fun clearHistory() {
        compassVerificationHistory.clear()
    }

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)
}