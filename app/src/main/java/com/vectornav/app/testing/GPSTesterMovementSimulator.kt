package com.vectornav.app.testing

import android.location.Location
import com.vectornav.app.NavigationCalculator
import kotlin.math.*

class GPSTesterMovementSimulator(
    private val navigationCalculator: NavigationCalculator,
    private val locationUpdateCallback: (Double, Double, Float) -> Unit
) {

    var startLat = 47.4979
    var startLon = -122.3668
    var currentLat = 47.4979
    var currentLon = -122.3668
    var simulatedDistance = 0f

    var useRealGPS = false
    private var lastRealLocation: Location? = null

    fun resetToStart() {
        currentLat = startLat
        currentLon = startLon
        simulatedDistance = 0f
        locationUpdateCallback(currentLat, currentLon, simulatedDistance)
    }

    fun moveUser(direction: Float, distanceMeters: Float) {
        if (useRealGPS) return // Don't simulate if using real GPS

        val directionRad = Math.toRadians(direction.toDouble())
        val deltaLat = (distanceMeters * cos(directionRad)) / 111320.0
        val deltaLon = (distanceMeters * sin(directionRad)) / (111320.0 * cos(Math.toRadians(currentLat)))

        currentLat += deltaLat
        currentLon += deltaLon
        simulatedDistance = navigationCalculator.calculateDistance(startLat, startLon, currentLat, currentLon)

        locationUpdateCallback(currentLat, currentLon, simulatedDistance)
    }

    fun jumpToTarget(bearing: Float, targetDistance: Int) {
        val destination = navigationCalculator.calculateDestination(
            startLat, startLon, bearing, targetDistance.toFloat()
        )
        currentLat = destination.first
        currentLon = destination.second
        simulatedDistance = targetDistance.toFloat()

        locationUpdateCallback(currentLat, currentLon, simulatedDistance)
    }

    fun simulateWalkingPath(bearing: Float, targetDistance: Int, stepCallback: () -> Unit) {
        Thread {
            val steps = 10
            val stepDistance = targetDistance.toFloat() / steps

            for (i in 1..steps) {
                val deviation = (Math.random() - 0.5) * 20
                moveUser(bearing + deviation.toFloat(), stepDistance)
                stepCallback()
                Thread.sleep(500)
            }
        }.start()
    }

    fun runAutomaticMovementTest(testCompleteCallback: (String) -> Unit) {
        val testSequence = listOf(
            Pair(0f, 5f),    // North 5m
            Pair(90f, 5f),   // East 5m
            Pair(180f, 5f),  // South 5m
            Pair(270f, 5f),  // West 5m
            Pair(45f, 10f)   // Northeast 10m
        )

        resetToStart()

        var testIndex = 0
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        fun runNextTest() {
            if (testIndex < testSequence.size) {
                val (direction, distance) = testSequence[testIndex]
                moveUser(direction, distance)
                testIndex++
                handler.postDelayed({ runNextTest() }, 1000)
            } else {
                handler.postDelayed({
                    val report = generateMovementTestReport()
                    testCompleteCallback(report)
                }, 500)
            }
        }

        runNextTest()
    }

    fun handleRealGPSUpdate(location: Location): Float {
        currentLat = location.latitude
        currentLon = location.longitude
        simulatedDistance = navigationCalculator.calculateDistance(startLat, startLon, currentLat, currentLon)

        val movementDistance = lastRealLocation?.let { lastLoc ->
            navigationCalculator.calculateDistance(
                lastLoc.latitude, lastLoc.longitude,
                location.latitude, location.longitude
            )
        } ?: 0f

        lastRealLocation = location
        locationUpdateCallback(currentLat, currentLon, simulatedDistance)

        return movementDistance
    }

    fun setRealGPSStart(location: Location) {
        startLat = location.latitude
        startLon = location.longitude
        currentLat = location.latitude
        currentLon = location.longitude
        simulatedDistance = 0f
        locationUpdateCallback(currentLat, currentLon, simulatedDistance)
    }

    fun createMockLocation(lat: Double, lon: Double, accuracy: Float = 5f): Location {
        return Location("gps").apply {
            latitude = lat
            longitude = lon
            this.accuracy = accuracy
            time = System.currentTimeMillis()
            speed = 1.4f
        }
    }

    private fun generateMovementTestReport(): String {
        return buildString {
            append("📊 MOVEMENT TEST REPORT:\n")
            append("Total Distance: ${simulatedDistance.format(1)}m\n")
            append("Start: (${startLat.format(6)}, ${startLon.format(6)})\n")
            append("End: (${currentLat.format(6)}, ${currentLon.format(6)})\n")
            append("✅ AUTO TEST COMPLETE")
        }
    }

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)
    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}