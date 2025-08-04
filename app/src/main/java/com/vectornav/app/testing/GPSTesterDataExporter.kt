// 📄 GPSTesterDataExporter.kt - FIXED VERSION
package com.vectornav.app.testing

import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.*

class GPSTesterDataExporter(private val context: Context) {

    fun generateFullTestReport(
        movementVerifier: GPSTesterMovementVerifier,
        textVerifier: GPSTesterTextVerifier,
        compassVerifier: GPSTesterCompassVerifier,
        realCompassAzimuth: Float,
        mockCompassAzimuth: Float,
        useRealGPS: Boolean,
        testingSensorFusion: Boolean
    ): String {
        val movementSuccess = movementVerifier.getSuccessRate()
        val textSuccess = textVerifier.getSuccessRate()
        val compassSuccess = compassVerifier.getSuccessRate()

        return buildString {
            append("📊 COMPLETE TEST REPORT\n")
            append("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n\n")

            // Current System State
            append("🔧 CURRENT SYSTEM STATE:\n")
            append("Real Compass: ${realCompassAzimuth.format(1)}° (${getCompassDirection(realCompassAzimuth)})\n")
            append("Mock Compass: ${mockCompassAzimuth.format(1)}° (${getCompassDirection(mockCompassAzimuth)})\n")
            append("GPS Mode: ${if (useRealGPS) "🟢 REAL GPS" else "🔵 SIMULATION"}\n")
            append("Sensor Fusion: ${if (testingSensorFusion) "🟢 ACTIVE" else "⚪ INACTIVE"}\n")

            val compassDifference = kotlin.math.abs(realCompassAzimuth - mockCompassAzimuth).let { diff ->
                if (diff > 180f) 360f - diff else diff
            }
            append("Compass Sync: ${compassDifference.format(1)}° difference\n\n")

            // Test Results
            append("📈 TEST RESULTS:\n")
            append("🎯 MOVEMENT: ${movementSuccess}% (${movementVerifier.getTestCount()} tests)\n")
            append("📝 TEXT: ${textSuccess}% (${textVerifier.getTestCount()} tests)\n")
            append("🧭 COMPASS: ${compassSuccess}% (${compassVerifier.getTestCount()} tests)\n\n")

            val overallScore = listOf(movementSuccess, textSuccess, compassSuccess).average().toInt()

            append("📊 OVERALL SCORE: ${overallScore}%\n")
            append("Grade: ${when {
                overallScore >= 90 -> "✅ EXCELLENT"
                overallScore >= 70 -> "⚠️ GOOD"
                overallScore >= 50 -> "🔧 NEEDS WORK"
                else -> "❌ CRITICAL"
            }}\n\n")

            // System Recommendations
            append("💡 RECOMMENDATIONS:\n")
            if (!useRealGPS && overallScore < 80) {
                append("• Test with real GPS for more accurate results\n")
            }
            if (compassDifference > 10f) {
                append("• Compass calibration needed (${compassDifference.format(1)}° off)\n")
            }
            if (!testingSensorFusion && overallScore > 85) {
                append("• Enable sensor fusion testing for advanced validation\n")
            }
            if (movementSuccess < textSuccess && movementSuccess < compassSuccess) {
                append("• Focus on movement detection issues\n")
            }
        }
    }

    fun exportTestData(
        movementVerifier: GPSTesterMovementVerifier,
        textVerifier: GPSTesterTextVerifier,
        compassVerifier: GPSTesterCompassVerifier,
        realCompassAzimuth: Float,
        mockCompassAzimuth: Float,
        useRealGPS: Boolean,
        testingSensorFusion: Boolean
    ) {
        val exportData = buildString {
            append("GPS Navigation Tester Export\n")
            append("===========================\n\n")

            // Device Info
            append("📱 DEVICE INFORMATION:\n")
            append("Device: ${android.os.Build.MODEL}\n")
            append("Manufacturer: ${android.os.Build.MANUFACTURER}\n")
            append("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
            append("Timestamp: ${System.currentTimeMillis()}\n")
            append("Export Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n\n")

            // Current System State (USING THE PARAMETERS!)
            append("🔧 SYSTEM STATE AT EXPORT:\n")
            append("Real Compass Bearing: ${realCompassAzimuth.format(2)}° (${getCompassDirection(realCompassAzimuth)})\n")
            append("Mock Compass Setting: ${mockCompassAzimuth.format(2)}° (${getCompassDirection(mockCompassAzimuth)})\n")
            append("GPS Source: ${if (useRealGPS) "Real GPS Hardware" else "Simulated GPS"}\n")
            append("Sensor Fusion: ${if (testingSensorFusion) "Enabled and Active" else "Disabled"}\n")

            val compassDiff = kotlin.math.abs(realCompassAzimuth - mockCompassAzimuth).let { diff ->
                if (diff > 180f) 360f - diff else diff
            }
            append("Compass Accuracy: ${compassDiff.format(2)}° difference between real and mock\n\n")

            // Test Statistics
            append("📊 TEST STATISTICS:\n")
            append("Movement Tests: ${movementVerifier.getTestCount()} records (${movementVerifier.getSuccessRate()}% success)\n")
            append("Text Verification Tests: ${textVerifier.getTestCount()} records (${textVerifier.getSuccessRate()}% success)\n")
            append("Compass Tests: ${compassVerifier.getTestCount()} records (${compassVerifier.getSuccessRate()}% success)\n\n")

            // Configuration Analysis
            append("⚙️ CONFIGURATION ANALYSIS:\n")
            if (useRealGPS) {
                append("✓ Using real GPS - results reflect actual device performance\n")
            } else {
                append("⚠ Using simulated GPS - results may not reflect real-world performance\n")
            }

            if (testingSensorFusion) {
                append("✓ Sensor fusion active - testing advanced navigation features\n")
            } else {
                append("ℹ Sensor fusion inactive - basic navigation testing only\n")
            }

            when {
                compassDiff < 5f -> append("✓ Compass calibration excellent (${compassDiff.format(1)}° error)\n")
                compassDiff < 15f -> append("⚠ Compass calibration acceptable (${compassDiff.format(1)}° error)\n")
                else -> append("❌ Compass calibration poor (${compassDiff.format(1)}° error) - recalibration needed\n")
            }

            // Raw Data Section
            append("\n📋 RAW DATA SUMMARY:\n")
            append("Total test duration: Runtime data\n")
            append("Peak memory usage: Runtime data\n")
            append("GPS accuracy range: Runtime data\n")
            append("Compass stability: Runtime data\n\n")

            append("End of Export\n")
            append("Generated by GPS Navigation Tester v1.0")
        }

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, exportData)
            putExtra(Intent.EXTRA_SUBJECT, "GPS Navigation Test Data - ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}")
        }

        context.startActivity(Intent.createChooser(shareIntent, "Export Test Data"))
    }

    fun generateBreadcrumbReport(
        breadcrumbVerificationHistory: List<BreadcrumbVerificationRecord>
    ): String {
        val successRate = breadcrumbVerificationHistory.count { it.breadcrumbsMatch && it.renderingWorking } * 100 /
                kotlin.math.max(breadcrumbVerificationHistory.size, 1)

        return buildString {
            append("🍞 BREADCRUMB VERIFICATION REPORT:\n")
            append("Tests: ${breadcrumbVerificationHistory.size}\n")
            append("Success Rate: ${successRate}%\n")

            val renderingIssues = breadcrumbVerificationHistory.count { !it.renderingWorking }
            val countIssues = breadcrumbVerificationHistory.count { !it.breadcrumbsMatch }

            if (renderingIssues > 0) {
                append("⚠️ Rendering Issues: $renderingIssues tests\n")
            }
            if (countIssues > 0) {
                append("⚠️ Count Issues: $countIssues tests\n")
            }
            if (successRate == 100 && breadcrumbVerificationHistory.isNotEmpty()) {
                append("✅ ALL BREADCRUMB TESTS PASSED\n")
            }
        }
    }

    // Helper function to convert azimuth to compass direction
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

    // Helper extension function
    private fun Float.format(digits: Int) = "%.${digits}f".format(this)
}