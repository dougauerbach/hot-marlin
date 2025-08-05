package com.vectornav.app

import android.location.Location
import android.util.Log

/**
 * Handles all navigation logic including target setting, bearing calculations,
 * and guidance updates. Separates navigation concerns from UI management.
 */
class CompassNavigationController(
    private val navigationCalculator: NavigationCalculator,
    private val navigationLineManager: NavigationLineManager
) {

    // Navigation state
    private var isNavigating = false
    private var targetBearing: Float = 0f
    private var startLatitude: Double = 0.0
    private var startLongitude: Double = 0.0
    private var targetDistance: Int = 50 // Default 50 meters
    private var destinationLatitude: Double = 0.0
    private var destinationLongitude: Double = 0.0

    // Smoothing for GPS bearing jumps
    private var lastGpsBearing: Float = 0f
    private var isFirstBearingCalculation = true
    private val gpsBearingFilter = SensorFilter(0.3f)  // Heavy filtering for GPS bearing

    // Callback interfaces for communicating back to MainActivity
    interface NavigationUpdateListener {
        fun onNavigationStarted(targetBearing: Float, targetDistance: Int)
        fun onNavigationStopped()
        fun onNavigationUpdate(
            currentDistance: Int,
            relativeBearing: Float,
            isOnCourse: Boolean,
            guidanceText: String,
            statusText: String
        )
    }

    private var updateListener: NavigationUpdateListener? = null

    fun setUpdateListener(listener: NavigationUpdateListener) {
        updateListener = listener
    }

    fun setTargetDistance(distance: Int) {
        targetDistance = distance
        if (isNavigating) {
            // Recalculate destination with new distance
            calculateDestination()
        }
    }

    fun isCurrentlyNavigating(): Boolean = isNavigating

    /**
     * Sets a new navigation target based on current location and camera direction
     */
    fun setNavigationTarget(currentLocation: Location, cameraAzimuth: Float) {
        startLatitude = currentLocation.latitude
        startLongitude = currentLocation.longitude
        targetBearing = cameraAzimuth

        // Calculate destination using GPS + camera direction + distance
        calculateDestination()

        // Start navigation
        isNavigating = true

        Log.d("VectorNav", "Navigation target set:")
        Log.d("VectorNav", "Start location: ($startLatitude, $startLongitude)")
        Log.d("VectorNav", "Camera direction: ${targetBearing}°")
        Log.d("VectorNav", "Distance: ${targetDistance}m")
        Log.d("VectorNav", "Destination: ($destinationLatitude, $destinationLongitude)")

        // Update navigation line immediately
        updateNavigationLine(currentLocation, cameraAzimuth)

        // Notify UI
        updateListener?.onNavigationStarted(targetBearing, targetDistance)
    }

    /**
     * Stops current navigation and clears target
     */
    fun stopNavigation() {
        isNavigating = false
        navigationLineManager.hideNavigationLine()

        Log.d("VectorNav", "Navigation stopped")

        // Notify UI
        updateListener?.onNavigationStopped()
    }

    /**
     * Updates navigation based on current location and device orientation
     */
    fun updateNavigation(currentLocation: Location, deviceAzimuth: Float) {
        if (!isNavigating) return

        // Calculate current distance to destination
        val currentDistance = navigationCalculator.calculateDistance(
            currentLocation.latitude, currentLocation.longitude,
            destinationLatitude, destinationLongitude
        ).toInt()

        // Calculate bearing from CURRENT position to destination (GPS-based)
        val rawGpsBearing = navigationCalculator.calculateBearing(
            currentLocation.latitude, currentLocation.longitude,
            destinationLatitude, destinationLongitude
        )

        // Detect and smooth GPS bearing jumps
        val smoothedGpsBearing = if (isFirstBearingCalculation) {
            gpsBearingFilter.filter(rawGpsBearing)
            lastGpsBearing = rawGpsBearing
            isFirstBearingCalculation = false
            rawGpsBearing
        } else {
            val bearingDiff = normalizeAngle(rawGpsBearing - lastGpsBearing)

            // Log all bearing changes for debugging
            if (kotlin.math.abs(bearingDiff) > 0.5f) {
                Log.d("VectorNav", "GPS bearing change: ${lastGpsBearing}° → ${rawGpsBearing}° (${bearingDiff}°)")
            }

            val filteredBearing = if (kotlin.math.abs(bearingDiff) > 3f) {
                Log.d("VectorNav", "⚠️ GPS BEARING JUMP DETECTED: ${lastGpsBearing}° → ${rawGpsBearing}° (${bearingDiff}°)")
                // Use heavily filtered bearing for large jumps
                gpsBearingFilter.filter(rawGpsBearing)
            } else {
                // Apply light filtering for small changes
                gpsBearingFilter.filter(rawGpsBearing)
            }

            lastGpsBearing = filteredBearing
            filteredBearing
        }

        // Calculate relative bearing for guidance
        val relativeBearing = normalizeAngle(smoothedGpsBearing - deviceAzimuth)

        // Debug logging to catch jumps
        Log.d("VectorNav", "=== NAVIGATION UPDATE ===")
        Log.d("VectorNav", "Raw GPS bearing: ${rawGpsBearing}°, Smoothed: ${smoothedGpsBearing}°")
        Log.d("VectorNav", "Device azimuth: ${deviceAzimuth}°")
        Log.d("VectorNav", "Relative bearing: ${relativeBearing}°")
        Log.d("VectorNav", "Distance: ${currentDistance}m")

        // Update navigation line with smoothed bearing
        updateNavigationLineWithBearing(deviceAzimuth, smoothedGpsBearing)

        // Generate guidance text and status
        val guidanceText = generateGuidanceText(currentDistance, relativeBearing)
        val statusText = generateStatusText(relativeBearing)
        val isOnCourse = kotlin.math.abs(relativeBearing) < 5

        Log.d("VectorNav", "Generated guidance: '$guidanceText', status: '$statusText'")

        // Notify UI
        updateListener?.onNavigationUpdate(
            currentDistance, relativeBearing, isOnCourse, guidanceText, statusText
        )
    }

    /**
     * Updates navigation line when device orientation changes
     */
    private fun updateNavigationLineWithBearing(deviceAzimuth: Float, gpsBearing: Float) {
        if (!isNavigating) return

        Log.d("VectorNav", "Line update: GPS=${gpsBearing}°, device=${deviceAzimuth}°, relative=${normalizeAngle(gpsBearing - deviceAzimuth)}°")

        // Update navigation line with smoothed GPS bearing
        navigationLineManager.createOrUpdateNavigationLine(
            targetBearing = gpsBearing,
            currentDeviceAzimuth = deviceAzimuth
        )
    }

    // Legacy method for sensor-only updates
    fun updateNavigationLine(currentLocation: Location, deviceAzimuth: Float) {
        if (!isNavigating) return

        // Calculate bearing from current GPS position to fixed destination
        val gpsCalculatedBearing = navigationCalculator.calculateBearing(
            currentLocation.latitude, currentLocation.longitude,
            destinationLatitude, destinationLongitude
        )

        updateNavigationLineWithBearing(deviceAzimuth, gpsCalculatedBearing)
    }

    private fun calculateDestination() {
        val destinationCoords = navigationCalculator.calculateDestination(
            startLatitude, startLongitude, targetBearing, targetDistance.toFloat()
        )
        destinationLatitude = destinationCoords.first
        destinationLongitude = destinationCoords.second
    }

    private fun generateGuidanceText(currentDistance: Int, relativeBearing: Float): String {
        return when {
            currentDistance < 10 -> "Destination reached!"
            kotlin.math.abs(relativeBearing) < 5 -> "On target - keep going straight!"
            relativeBearing > 5 -> "Turn right to get back on course"
            relativeBearing < -5 -> "Turn left to get back on course"
            else -> "On course - follow the yellow line"
        }
    }

    private fun generateStatusText(relativeBearing: Float): String {
        return when {
            kotlin.math.abs(relativeBearing) < 5 -> "On course ✓"
            relativeBearing > 0 -> "Off course: turn right"
            else -> "Off course: turn left"
        }
    }

    private fun normalizeAngle(angle: Float): Float {
        var normalized = angle
        while (normalized > 180f) normalized -= 360f
        while (normalized < -180f) normalized += 360f
        return normalized
    }

    /**
     * Get current navigation info for UI updates
     */
    data class NavigationInfo(
        val isNavigating: Boolean,
        val targetBearing: Float,
        val targetDistance: Int,
        val startLatitude: Double,
        val startLongitude: Double,
        val destinationLatitude: Double,
        val destinationLongitude: Double
    )

    fun getNavigationInfo(): NavigationInfo {
        return NavigationInfo(
            isNavigating = isNavigating,
            targetBearing = targetBearing,
            targetDistance = targetDistance,
            startLatitude = startLatitude,
            startLongitude = startLongitude,
            destinationLatitude = destinationLatitude,
            destinationLongitude = destinationLongitude
        )
    }
}