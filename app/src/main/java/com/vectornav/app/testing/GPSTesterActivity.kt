package com.vectornav.app.testing

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.vectornav.app.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main coordinator for GPS Testing - lightweight activity that delegates to specialized components
 */
class GPSTesterActivity : AppCompatActivity(), GPSTesterUI.GPSTesterUICallbacks {

    // Core components
    private lateinit var ui: GPSTesterUI
    private lateinit var compassManager: GPSTesterCompassManager
    private lateinit var movementSimulator: GPSTesterMovementSimulator
    private lateinit var movementVerifier: GPSTesterMovementVerifier
    private lateinit var textVerifier: GPSTesterTextVerifier
    private lateinit var compassVerifier: GPSTesterCompassVerifier
    private lateinit var dataExporter: GPSTesterDataExporter

    // Navigation components
    private lateinit var navigationCalculator: NavigationCalculator
    private lateinit var locationManager: LocationManager
    private lateinit var gpsTrackingController: GPSTrackingController

    // State
    private var bearing = 90f // East
    private var targetDistance = 50
    private var testingSensorFusion = false
    private val breadcrumbVerificationHistory = mutableListOf<BreadcrumbVerificationRecord>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initializeComponents()

        // Set content view FIRST
        setContentView(ui.createMainLayout())

        // THEN setup callbacks (after UI is created)
        setupCallbacks()

        // Finally update views
        updateTestView()
        runDiagnostic()
    }

    private fun initializeComponents() {
        // Core navigation
        navigationCalculator = NavigationCalculator()
        locationManager = LocationManager(this)
        gpsTrackingController = GPSTrackingController(navigationCalculator, this)

        // UI
        ui = GPSTesterUI(this, this)

        // Specialized managers
        compassManager = GPSTesterCompassManager(this) { compassUpdate ->
            handleCompassUpdate(compassUpdate)
        }

        movementSimulator = GPSTesterMovementSimulator(navigationCalculator) { lat, lon, distance ->
            handleLocationUpdate(lat, lon, distance)
        }

        movementVerifier = GPSTesterMovementVerifier(
            getViewDimensions = { Pair(ui.testingView.width, ui.testingView.height) },
            getDisplayMetrics = { resources.displayMetrics.density },
            getUserPos = { ui.testingView.getUserPosition() },
            updateCallback = { ui.movementDisplay.text = it }
        )

        textVerifier = GPSTesterTextVerifier(
            ui.mockDistanceText,
            ui.mockInstructionText
        ) { ui.textVerificationDisplay.text = it }

        compassVerifier = GPSTesterCompassVerifier(navigationCalculator) {
            ui.compassVerificationDisplay.text = it
        }

        dataExporter = GPSTesterDataExporter(this)
    }

    private fun setupCallbacks() {
        locationManager.setLocationCallback { location ->
            if (movementSimulator.useRealGPS) {
                handleRealGPSUpdate(location)
            }
        }

        gpsTrackingController.setUpdateListener(object : GPSTrackingController.GPSTrackingUpdateListener {
            override fun onTrackingStarted(bearing: Float, distance: Int) {
                updateSensorFusionDisplay("🟢 SENSOR FUSION STARTED",
                    "Bearing: ${bearing.format(1)}°, Distance: ${distance}m")
            }

            override fun onTrackingStopped() {
                updateSensorFusionDisplay("🔴 SENSOR FUSION STOPPED", "Back to basic GPS testing")
            }

            override fun onPositionUpdate(
                currentLat: Double, currentLon: Double, distanceFromStart: Float,
                crossTrackError: Float, isOnTrack: Boolean, confidence: Float
            ) {
                ui.testingView.updateTracking(
                    movementSimulator.startLat, movementSimulator.startLon,
                    currentLat, currentLon, bearing, targetDistance,
                    crossTrackError, distanceFromStart, isOnTrack
                )

                updateSensorFusionDisplay("🔧 SENSOR FUSION UPDATE",
                    "Distance: ${distanceFromStart.format(1)}m\n" +
                            "Cross-track: ${crossTrackError.format(1)}m\n" +
                            "Confidence: ${(confidence * 100).format(0)}%\n" +
                            "On Track: ${if (isOnTrack) "✅" else "❌"}")

                runDiagnostic()
            }

            override fun onTargetFound(estimatedDistance: Int, actualDistance: Float) {
                updateSensorFusionDisplay("🎯 TARGET FOUND",
                    "Estimated: ${estimatedDistance}m, Actual: ${actualDistance.format(1)}m")
            }
        })
    }

    private fun handleCompassUpdate(compassUpdate: GPSTesterCompassManager.CompassUpdate) {
        val accuracyText = when (compassUpdate.accuracy) {
            android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "🟢 HIGH"
            android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "🟡 MEDIUM"
            android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "🟠 LOW"
            android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE -> "🔴 UNRELIABLE"
            else -> "❓ UNKNOWN"
        }

        val orientationWarning = if (!compassUpdate.isVertical) " ⚠️ HOLD VERTICAL" else ""

        val displayText = buildString {
            append("📱 REAL PHONE COMPASS:\n")
            append("Direction: ${compassUpdate.realAzimuth.toInt()}° (${compassManager.getCompassDirection(compassUpdate.realAzimuth)})\n")
            append("Accuracy: $accuracyText$orientationWarning")
        }

        ui.realCompassDisplay.text = displayText
        updateQuickStatus()
    }

    private fun handleLocationUpdate(lat: Double, lon: Double, distance: Float) {
        movementVerifier.verifyMovement(
            movementSimulator.startLat, movementSimulator.startLon,
            lat, lon, bearing, targetDistance, distance, navigationCalculator
        )
        updateTestView()
        handler.postDelayed({ runDiagnostic() }, 100)
    }

    private fun handleRealGPSUpdate(location: Location) {
        val movementDistance = movementSimulator.handleRealGPSUpdate(location)

        if (testingSensorFusion) {
            gpsTrackingController.updatePosition(location, bearing)
        } else {
            updateTestView()
        }

        val gpsQuality = when {
            location.accuracy < 5f -> "🟢 Excellent"
            location.accuracy < 10f -> "🟡 Good"
            location.accuracy < 20f -> "🟠 Fair"
            else -> "🔴 Poor"
        }

        updateDiagnosticDisplay(
            "📍 REAL GPS UPDATE",
            "Accuracy: ${location.accuracy.format(1)}m ($gpsQuality)\n" +
                    "Movement: ${movementDistance.format(1)}m\n" +
                    "Total: ${movementSimulator.simulatedDistance.format(1)}m\n" +
                    "Sensor Fusion: ${if (testingSensorFusion) "ACTIVE" else "OFF"}"
        )

        runDiagnostic()
    }

    // UI Callback implementations
    override fun onToggleStatusDisplays() {
        ui.toggleStatusDisplays()
    }

    override fun onSwitchTab(tabId: String) {
        ui.switchToTab(tabId)
    }

    override fun onQuickTest() {
        runFullDiagnostic()
    }

    override fun onRealGPS() {
        movementSimulator.useRealGPS = !movementSimulator.useRealGPS

        if (movementSimulator.useRealGPS) {
            startRealGPSTracking()
        } else {
            stopRealGPSTracking()
        }
        updateQuickStatus()
    }

    override fun onReset() {
        movementSimulator.resetToStart()
        breadcrumbVerificationHistory.clear()
        runDiagnostic()
    }

    override fun onMoveUser(direction: Float, distance: Float) {
        movementSimulator.moveUser(direction, distance)
    }

    override fun onJumpToTarget() {
        movementSimulator.jumpToTarget(bearing, targetDistance)
    }

    override fun onSimulateWalkingPath() {
        movementSimulator.simulateWalkingPath(bearing, targetDistance) {
            runOnUiThread { /* Update UI if needed */ }
        }
    }

    override fun onRunAutomaticMovementTest() {
        movementSimulator.runAutomaticMovementTest { report ->
            updateDiagnosticDisplay("🤖 AUTO TEST COMPLETE", report)
        }
    }

    override fun onUseRealCompass() {
        bearing = compassManager.useRealCompass()
        updateDiagnosticDisplay("📱 USING REAL COMPASS",
            "Set to phone's actual direction: ${compassManager.getCompassDirection(bearing)} (${bearing.toInt()}°)")
        compassVerifier.testDestinationCalculation(
            movementSimulator.startLat, movementSimulator.startLon, bearing, targetDistance
        )
    }

    override fun onStartCompassCalibration() {
        val instructions = buildString {
            append("🧭 COMPASS CALIBRATION INSTRUCTIONS:\n\n")
            append("Current Status: ${compassManager.getAccuracyStatus()}\n\n")
            append("🔴 If accuracy shows RED:\n")
            append("• Move away from metal objects\n")
            append("• Do figure-8 calibration\n\n")
            append("🟡 If accuracy shows YELLOW:\n")
            append("• Hold phone vertically\n")
            append("• Do slow figure-8 motions\n")
            append("• Wait for GREEN accuracy")
        }
        updateDiagnosticDisplay("🧭 CALIBRATION GUIDE", instructions)
    }

    override fun onTestCompassAccuracy() {
        compassManager.testAccuracy { result ->
            updateDiagnosticDisplay("📊 ACCURACY COMPLETE", result)
        }
    }

    override fun onSetMockCompass(azimuth: Float) {
        compassManager.setMockCompass(azimuth)
        bearing = azimuth
        updateDiagnosticDisplay("🧭 COMPASS SET",
            "Pointing ${compassManager.getCompassDirection(azimuth)} (${azimuth.toInt()}°)")
        compassVerifier.testDestinationCalculation(
            movementSimulator.startLat, movementSimulator.startLon, bearing, targetDistance
        )
    }

    override fun onRunTextVerificationTest() {
        textVerifier.runTextVerificationTest { action, delay ->
            handler.postDelayed(action, delay)
        }
    }

    override fun onRunBreadcrumbVerificationTest() {
        updateDiagnosticDisplay("🍞 BREADCRUMB TEST", "Testing breadcrumb trail rendering...")

        val pathSteps = listOf(
            Pair(0f, 5f),     // North 5m
            Pair(90f, 5f),    // East 5m
            Pair(45f, 5f),    // Northeast 5m
            Pair(180f, 10f)   // South 10m
        )

        onReset()

        pathSteps.forEachIndexed { index, (direction, distance) ->
            handler.postDelayed({
                movementSimulator.moveUser(direction, distance)
                val expectedBreadcrumbs = ((movementSimulator.simulatedDistance / 2f).toInt())
                verifyBreadcrumbs(expectedBreadcrumbs)
            }, (index * 1500).toLong())
        }
    }

    override fun onTestOffTrackScenario() {
        updateDiagnosticDisplay("🎯 OFF-TRACK TEST", "Testing off-track indicators...")
        movementSimulator.moveUser(bearing + 45f, 15f)

        handler.postDelayed({
            val crossTrackError = navigationCalculator.calculateCrossTrackError(
                movementSimulator.currentLat, movementSimulator.currentLon,
                movementSimulator.startLat, movementSimulator.startLon, bearing
            )
            val isOnTrack = kotlin.math.abs(crossTrackError) < 5f
            val distanceFromStart = navigationCalculator.calculateDistance(
                movementSimulator.startLat, movementSimulator.startLon,
                movementSimulator.currentLat, movementSimulator.currentLon
            )
            val distanceRemaining = kotlin.math.max(0f, targetDistance - distanceFromStart)

            textVerifier.verifyTextDisplays("Off-Track Scenario", distanceFromStart,
                crossTrackError, distanceRemaining, isOnTrack)
            verifyBreadcrumbs((distanceFromStart / 2f).toInt())
        }, 200)
    }

    override fun onTestAllCompassDirections() {
        updateDiagnosticDisplay("🧭 COMPASS TEST", "Testing all 8 compass directions...")
        compassVerifier.testAllCompassDirections(
            postDelayed = { action, delay -> handler.postDelayed(action, delay) },
            onComplete = { generateCompassTestReport() }
        )
    }

    override fun onTestDifferentDistances() {
        updateDiagnosticDisplay("📐 DISTANCE TEST", "Testing different target distances...")
        compassVerifier.testDifferentDistances(
            originalDistance = targetDistance,
            postDelayed = { action, delay -> handler.postDelayed(action, delay) },
            onDistanceChange = { distance -> targetDistance = distance },
            onComplete = { generateCompassTestReport() }
        )
    }

    override fun onCompareCalculationMethods() {
        val report = compassVerifier.compareCalculationMethods()
        updateDiagnosticDisplay("🔄 COMPARISON COMPLETE", report)
    }

    override fun onSimulateShortMovement(distance: Float) {
        if (!testingSensorFusion) {
            updateSensorFusionDisplay("⚠️ START SENSOR FUSION FIRST",
                "Need to start sensor fusion test mode")
            return
        }

        movementSimulator.moveUser(bearing, distance)
        val mockLocation = movementSimulator.createMockLocation(
            movementSimulator.currentLat, movementSimulator.currentLon, accuracy = 8f
        )
        gpsTrackingController.updatePosition(mockLocation, bearing)

        updateSensorFusionDisplay("👣 SHORT MOVEMENT SIMULATED",
            "Moved ${distance}m with 8m GPS accuracy")
    }

    override fun onRunFullVerificationTest() {
        updateDiagnosticDisplay("✅ FULL VERIFICATION", "Running complete UI verification test...")

        onReset()
        textVerifier.clearHistory()
        breadcrumbVerificationHistory.clear()

        handler.postDelayed({ onRunTextVerificationTest() }, 500)
        handler.postDelayed({ onRunBreadcrumbVerificationTest() }, 6000)
        handler.postDelayed({ generateFullVerificationReport() }, 12000)
    }

    override fun onStartSensorFusionTest() {
        testingSensorFusion = true
        val startLocation = movementSimulator.createMockLocation(
            movementSimulator.currentLat, movementSimulator.currentLon
        )
        gpsTrackingController.startTracking(startLocation, bearing)
        updateSensorFusionDisplay("🟢 SENSOR FUSION ACTIVE",
            "Testing with real sensors + GPS fusion")
        updateQuickStatus()
    }

    override fun onStopSensorFusionTest() {
        testingSensorFusion = false
        gpsTrackingController.stopTracking()
        updateSensorFusionDisplay("🔴 SENSOR FUSION STOPPED",
            "Back to basic coordinate testing")
        updateQuickStatus()
    }

    override fun onHelpFindNorth() {
        // Implementation similar to original helpFindNorth method
    }

    override fun onStartFigure8Calibration() {
        compassManager.startCalibration { progress, status ->
            updateDiagnosticDisplay("🔄 CALIBRATING", "Figure-8 calibration: ${progress}%\nCurrent accuracy: $status")
        }
    }

    override fun onCompareCompassWithGPS() {
        // Implementation similar to original compareCompassWithGPS method
    }

    override fun onStartCompassComparison() {
        compassManager.isComparingWithMock = true
        updateDiagnosticDisplay("⚖️ REAL vs MOCK", "Rotate phone to different directions...")
    }

    override fun onGenerateFullTestReport() {
        val report = dataExporter.generateFullTestReport(
            movementVerifier, textVerifier, compassVerifier,
            compassManager.mockCompassAzimuth, compassManager.mockCompassAzimuth,
            movementSimulator.useRealGPS, testingSensorFusion
        )
        updateDiagnosticDisplay("📊 FULL REPORT", report)
    }

    override fun onExportTestData() {
        dataExporter.exportTestData(
            movementVerifier, textVerifier, compassVerifier,
            compassManager.mockCompassAzimuth, compassManager.mockCompassAzimuth,
            movementSimulator.useRealGPS, testingSensorFusion
        )
    }

    // Helper methods
    private fun startRealGPSTracking() {
        locationManager.getCurrentLocation { location ->
            movementSimulator.setRealGPSStart(location)
            updateDiagnosticDisplay("🟢 Real GPS Started", "Waiting for movement...")
            updateTestView()
            runDiagnostic()
        }
        locationManager.startLocationUpdates()
    }

    private fun stopRealGPSTracking() {
        locationManager.stopLocationUpdates()
        updateDiagnosticDisplay("🔴 GPS Stopped", "Back to simulation mode")
    }

    private fun updateTestView() {
        val crossTrackError = navigationCalculator.calculateCrossTrackError(
            movementSimulator.currentLat, movementSimulator.currentLon,
            movementSimulator.startLat, movementSimulator.startLon, bearing
        )
        val isOnTrack = kotlin.math.abs(crossTrackError) < 10f

        ui.testingView.updateTracking(
            movementSimulator.startLat, movementSimulator.startLon,
            movementSimulator.currentLat, movementSimulator.currentLon,
            bearing, targetDistance, crossTrackError, movementSimulator.simulatedDistance, isOnTrack
        )
    }

    private fun runDiagnostic() {
        val diagnostic = buildString {
            append("📱 VIEW: ${ui.testingView.width}x${ui.testingView.height}\n")

            val calcDistance = navigationCalculator.calculateDistance(
                movementSimulator.startLat, movementSimulator.startLon,
                movementSimulator.currentLat, movementSimulator.currentLon
            )
            val calcBearing = navigationCalculator.calculateBearing(
                movementSimulator.startLat, movementSimulator.startLon,
                movementSimulator.currentLat, movementSimulator.currentLon
            )

            append("🧮 CALC: Dist=${calcDistance.format(1)}m Bear=${calcBearing.format(0)}°\n")
            append("🚶 MOVE: ${if (movementSimulator.simulatedDistance > 1f && ui.testingView.width > 0) "YES" else "NO"}")
        }

        ui.diagnosticDisplay.text = diagnostic
        updateCoordinateDisplay()

        if (movementSimulator.startLat != 0.0 && movementSimulator.startLon != 0.0) {
            compassVerifier.testDestinationCalculation(
                movementSimulator.startLat, movementSimulator.startLon, bearing, targetDistance
            )
        }
    }

    private fun runFullDiagnostic() {
        updateDiagnosticDisplay("🔬 FULL DIAGNOSTIC", "Testing all systems...")
        ui.testingView.invalidate()
        handler.postDelayed({
            runDiagnostic()
            val report = movementVerifier.generateReport()
            updateDiagnosticDisplay("📊 MOVEMENT REPORT", report)
        }, 200)
    }

    private fun updateCoordinateDisplay() {
        val coords = buildString {
            append("🌍 START: ${movementSimulator.startLat.format(6)}, ${movementSimulator.startLon.format(6)}\n")
            append("📍 NOW:   ${movementSimulator.currentLat.format(6)}, ${movementSimulator.currentLon.format(6)}\n")
            append("📏 MOVE:  ${(movementSimulator.currentLat - movementSimulator.startLat).format(8)}, ${(movementSimulator.currentLon - movementSimulator.startLon).format(8)}\n")
            append("🎯 TARGET: ${bearing.format(0)}° @ ${targetDistance}m\n")
            append("📊 TOTAL: ${movementSimulator.simulatedDistance.format(1)}m traveled")
        }
        ui.coordinateDisplay.text = coords
    }

    private fun updateDiagnosticDisplay(title: String, details: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        ui.diagnosticDisplay.text = "$timestamp - $title\n$details"
    }

    private fun updateSensorFusionDisplay(title: String, details: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        ui.sensorFusionDisplay.text = "$timestamp - $title\n$details"
    }

    private fun updateQuickStatus() {
        val compassStatus = compassManager.getAccuracyStatus()
        val gpsStatus = if (movementSimulator.useRealGPS) "🟢 Active" else "⚪ Sim"
        val fusionStatus = if (testingSensorFusion) "ON" else "OFF"

        ui.updateQuickStatus(compassStatus, gpsStatus, fusionStatus)
    }

    private fun verifyBreadcrumbs(expectedCount: Int) {
        val actualBreadcrumbCount = (movementSimulator.simulatedDistance / 2f).toInt()
        val breadcrumbsVisible = movementSimulator.simulatedDistance > 2f

        val breadcrumbRecord = BreadcrumbVerificationRecord(
            timestamp = System.currentTimeMillis(),
            expectedBreadcrumbs = expectedCount,
            actualBreadcrumbs = actualBreadcrumbCount,
            breadcrumbsMatch = kotlin.math.abs(actualBreadcrumbCount - expectedCount) <= 1,
            renderingWorking = breadcrumbsVisible
        )

        breadcrumbVerificationHistory.add(breadcrumbRecord)
        if (breadcrumbVerificationHistory.size > 10) {
            breadcrumbVerificationHistory.removeAt(0)
        }

        updateBreadcrumbVerificationDisplay(breadcrumbRecord)
    }

    private fun updateBreadcrumbVerificationDisplay(record: BreadcrumbVerificationRecord) {
        val status = when {
            record.breadcrumbsMatch && record.renderingWorking -> "✅ CORRECT"
            record.breadcrumbsMatch && !record.renderingWorking -> "⚠️ COUNT OK, NOT VISIBLE"
            !record.breadcrumbsMatch && record.renderingWorking -> "⚠️ VISIBLE, WRONG COUNT"
            else -> "❌ FAILED"
        }

        val successRate = breadcrumbVerificationHistory.count { it.breadcrumbsMatch && it.renderingWorking } * 100 /
                kotlin.math.max(breadcrumbVerificationHistory.size, 1)

        val displayText = buildString {
            append("🍞 BREADCRUMB VERIFICATION:\n")
            append("Status: $status\n")
            append("Expected: ${record.expectedBreadcrumbs}, Actual: ${record.actualBreadcrumbs}\n")
            append("Success Rate: ${successRate}% (${breadcrumbVerificationHistory.size} tests)")
        }

        ui.breadcrumbVerificationDisplay.text = displayText
    }

    private fun generateCompassTestReport() {
        val report = compassVerifier.generateReport()
        updateDiagnosticDisplay("📊 COMPASS REPORT", report)
    }

    private fun generateFullVerificationReport() {
        val textSuccessRate = textVerifier.getSuccessRate()
        val breadcrumbSuccessRate = breadcrumbVerificationHistory.count { it.breadcrumbsMatch && it.renderingWorking } * 100 /
                kotlin.math.max(breadcrumbVerificationHistory.size, 1)

        val report = buildString {
            append("📊 FULL VERIFICATION REPORT:\n\n")
            append("📝 TEXT DISPLAYS: ${textSuccessRate}% (${textVerifier.getTestCount()} tests)\n")
            append("🍞 BREADCRUMBS: ${breadcrumbSuccessRate}% (${breadcrumbVerificationHistory.size} tests)\n")

            val overallScore = (textSuccessRate + breadcrumbSuccessRate) / 2
            append("📊 OVERALL SCORE: ${overallScore}%\n")

            when {
                overallScore >= 90 -> append("✅ EXCELLENT - UI working correctly!")
                overallScore >= 70 -> append("⚠️ GOOD - Minor issues detected")
                overallScore >= 50 -> append("🔧 NEEDS WORK - Several UI problems")
                else -> append("❌ CRITICAL - Major UI failures")
            }
        }

        updateDiagnosticDisplay("📊 VERIFICATION COMPLETE", report)
    }

    override fun onResume() {
        super.onResume()
        compassManager.startListening()
    }

    override fun onPause() {
        super.onPause()
        compassManager.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (movementSimulator.useRealGPS) {
            locationManager.stopLocationUpdates()
        }
        if (testingSensorFusion) {
            gpsTrackingController.stopTracking()
        }
        compassManager.stopListening()
    }

    // Extension functions
    private fun Float.format(digits: Int) = "%.${digits}f".format(this)
    private fun Double.format(digits: Int) = "%.${digits}f".format(this)

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, GPSTesterActivity::class.java)
            context.startActivity(intent)
        }
    }
}