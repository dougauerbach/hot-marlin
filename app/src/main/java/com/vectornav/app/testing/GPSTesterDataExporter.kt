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

            append("🎯 MOVEMENT: ${movementSuccess}% (${movementVerifier.getTestCount()} tests)\n")
            append("📝 TEXT: ${textSuccess}% (${textVerifier.getTestCount()} tests)\n")
            append("🧭 COMPASS: ${compassSuccess}% (${compassVerifier.getTestCount()} tests)\n")

            val overallScore = listOf(movementSuccess, textSuccess, compassSuccess).average().toInt()

            append("\n📊 OVERALL: ${overallScore}%\n")
            append(when {
                overallScore >= 90 -> "✅ EXCELLENT"
                overallScore >= 70 -> "⚠️ GOOD"
                overallScore >= 50 -> "🔧 NEEDS WORK"
                else -> "❌ CRITICAL"
            })
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
            append("Device: ${android.os.Build.MODEL}\n")
            append("Android: ${android.os.Build.VERSION.RELEASE}\n")
            append("Timestamp: ${System.currentTimeMillis()}\n\n")

            append("Movement History: ${movementVerifier.getTestCount()} records\n")
            append("Text Tests: ${textVerifier.getTestCount()} records\n")
            append("Compass Tests: ${compassVerifier.getTestCount()} records\n")

            append("\nCurrent State:\n")
            append("Real Compass: ${realCompassAzimuth.toInt()}°\n")
            append("Mock Compass: ${mockCompassAzimuth.toInt()}°\n")
            append("GPS Mode: ${if (useRealGPS) "REAL" else "SIMULATION"}\n")
            append("Sensor Fusion: ${if (testingSensorFusion) "ACTIVE" else "INACTIVE"}\n")
        }

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, exportData)
            putExtra(Intent.EXTRA_SUBJECT, "GPS Navigation Test Data")
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
}