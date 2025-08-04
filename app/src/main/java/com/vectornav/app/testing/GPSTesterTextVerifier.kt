package com.vectornav.app.testing

import android.widget.TextView

class GPSTesterTextVerifier(
    private val mockDistanceText: TextView,
    private val mockInstructionText: TextView,
    private val updateCallback: (String) -> Unit
) {

    private val textVerificationHistory = mutableListOf<TextVerificationRecord>()

    fun runTextVerificationTest(postDelayed: ((() -> Unit), Long) -> Unit) {
        val scenarios = listOf(
            Triple("On Track", 25f, 0f),
            Triple("Off Track Left", 30f, 8f),
            Triple("Off Track Right", 20f, -6f),
            Triple("Very Close", 3f, 1f),
            Triple("Just Started", 48f, 2f)
        )

        scenarios.forEachIndexed { index, (scenario, remaining, crossTrack) ->
            postDelayed({
                val isOnTrack = kotlin.math.abs(crossTrack) < 5f
                verifyTextDisplays(scenario, 50f - remaining, crossTrack, remaining, isOnTrack)
            }, (index * 1000).toLong())
        }
    }

    fun verifyTextDisplays(
        scenario: String,
        distanceFromStart: Float,
        crossTrackError: Float,
        distanceRemaining: Float,
        isOnTrack: Boolean
    ) {
        val expectedDistanceText = generateExpectedDistanceText(distanceRemaining)
        val expectedInstructionText = generateExpectedInstructionText(distanceRemaining, isOnTrack, crossTrackError)
        val expectedInstructionColor = getExpectedInstructionColor(distanceRemaining, isOnTrack)

        mockDistanceText.text = expectedDistanceText
        mockInstructionText.text = expectedInstructionText
        mockInstructionText.setTextColor(expectedInstructionColor)

        val actualDistanceText = mockDistanceText.text.toString()
        val actualInstructionText = mockInstructionText.text.toString()
        val actualInstructionColor = mockInstructionText.currentTextColor

        val distanceTextMatches = actualDistanceText.contains(distanceRemaining.toInt().toString())
        val instructionTextMatches = verifyInstructionTextLogic(actualInstructionText, isOnTrack, crossTrackError)
        val colorCorrect = actualInstructionColor == expectedInstructionColor

        val textRecord = TextVerificationRecord(
            timestamp = System.currentTimeMillis(),
            scenario = scenario,
            expectedText = "$expectedDistanceText | $expectedInstructionText",
            actualText = "$actualDistanceText | $actualInstructionText",
            textMatches = distanceTextMatches && instructionTextMatches,
            colorCorrect = colorCorrect
        )

        textVerificationHistory.add(textRecord)
        if (textVerificationHistory.size > 10) {
            textVerificationHistory.removeAt(0)
        }

        updateTextVerificationDisplay(textRecord)
    }

    private fun generateExpectedDistanceText(distanceRemaining: Float): String {
        return when {
            distanceRemaining < 5f -> "🎯 Almost there! (${(100 * (50 - distanceRemaining) / 50).toInt()}%)"
            distanceRemaining > 45f -> "Just started - ${distanceRemaining.toInt()}m to go"
            else -> "${distanceRemaining.toInt()}m remaining (${(100 * (50 - distanceRemaining) / 50).toInt()}% complete)"
        }
    }

    private fun generateExpectedInstructionText(distanceRemaining: Float, isOnTrack: Boolean, crossTrackError: Float): String {
        return when {
            distanceRemaining < 5f -> "🎯 You're here! Look around for your target."
            distanceRemaining < 10f -> "Very close! Slow down and look carefully."
            isOnTrack -> "✅ On track - keep going straight!"
            crossTrackError > 0 -> "⬅️ Move left to get back on track (${kotlin.math.abs(crossTrackError).toInt()}m off)"
            else -> "➡️ Move right to get back on track (${kotlin.math.abs(crossTrackError).toInt()}m off)"
        }
    }

    private fun getExpectedInstructionColor(distanceRemaining: Float, isOnTrack: Boolean): Int {
        return when {
            distanceRemaining < 5f -> 0xFF9C27B0.toInt() // Purple - complete
            distanceRemaining < 15f -> 0xFFF44336.toInt() // Red - near target
            distanceRemaining < 35f -> 0xFFFF9800.toInt() // Orange - middle
            else -> 0xFF4CAF50.toInt() // Green - start
        }
    }

    private fun verifyInstructionTextLogic(actualText: String, isOnTrack: Boolean, crossTrackError: Float): Boolean {
        return when {
            isOnTrack -> actualText.contains("On track") || actualText.contains("keep going straight")
            crossTrackError > 0 -> actualText.contains("left") || actualText.contains("⬅️")
            crossTrackError < 0 -> actualText.contains("right") || actualText.contains("➡️")
            else -> true
        }
    }

    private fun updateTextVerificationDisplay(record: TextVerificationRecord) {
        val status = when {
            record.textMatches && record.colorCorrect -> "✅ CORRECT"
            record.textMatches && !record.colorCorrect -> "⚠️ TEXT OK, COLOR WRONG"
            !record.textMatches && record.colorCorrect -> "⚠️ COLOR OK, TEXT WRONG"
            else -> "❌ FAILED"
        }

        val successRate = textVerificationHistory.count { it.textMatches && it.colorCorrect } * 100 /
                kotlin.math.max(textVerificationHistory.size, 1)

        val displayText = buildString {
            append("📝 TEXT VERIFICATION:\n")
            append("${record.scenario}: $status\n")
            append("Success Rate: ${successRate}% (${textVerificationHistory.size} tests)")
        }

        updateCallback(displayText)
    }

    fun getSuccessRate(): Int {
        return textVerificationHistory.count { it.textMatches && it.colorCorrect } * 100 /
                kotlin.math.max(textVerificationHistory.size, 1)
    }

    fun getTestCount(): Int = textVerificationHistory.size

    fun clearHistory() {
        textVerificationHistory.clear()
    }
}