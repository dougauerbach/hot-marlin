package com.vectornav.app

// Simple low-pass filter for sensor smoothing
class SensorFilter(private val alpha: Float) {
    private var filteredValue: Float = 0f
    private var isInitialized = false

    fun filter(newValue: Float): Float {
        if (!isInitialized) {
            filteredValue = newValue
            isInitialized = true
            return filteredValue
        }

        // Handle angle wrapping (0° and 360° are the same)
        var diff = newValue - filteredValue
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f

        filteredValue += alpha * diff

        // Normalize to 0-360
        if (filteredValue < 0f) filteredValue += 360f
        if (filteredValue >= 360f) filteredValue -= 360f

        return filteredValue
    }
}