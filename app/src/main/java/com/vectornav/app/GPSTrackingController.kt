package com.vectornav.app

import android.content.Context
import android.location.Location
import android.util.Log
import kotlin.math.*

/**
 * Pure GPS-based navigation controller enhanced with sensor fusion for improved accuracy.
 * Uses GPS, step counter, and compass data for reliable short-distance tracking.
 */
class GPSTrackingController(
    private val navigationCalculator: NavigationCalculator,
    context: Context
) : SensorFusionManager.SensorFusionListener {

    // Navigation state - SIMPLIFIED
    private var isTracking = false
    private var initialBearing: Float = 0f
    private var startLatitude: Double = 0.0
    private var startLongitude: Double = 0.0
    private val targetDistance: Int = 1000 // FIXED 1000m - no longer changeable
    private var foundLatitude: Double = 0.0
    private var foundLongitude: Double = 0.0
    private var isTargetFound = false

    // Auto-reset configuration
    private val maxDistanceBeforeReset = 1000f // 1km threshold
    private var lastResetCheck = 0L
    private val resetCheckInterval = 800L // Check every resetCheckInterval seconds

    private var sensorFusionManager: SensorFusionManager =
        SensorFusionManager(context, navigationCalculator)

    // Callback interface for UI updates
    interface GPSTrackingUpdateListener {
        fun onTrackingStarted(bearing: Float, distance: Int)
        fun onTrackingStopped()
        fun onPositionUpdate(
            currentLat: Double,
            currentLon: Double,
            distanceFromStart: Float,
            crossTrackError: Float,
            isOnTrack: Boolean,
            confidence: Float
        )
        fun onTargetFound(estimatedDistance: Int, actualDistance: Float)
        fun onTrackingReset(distanceFromStart: Float, currentTargetDistance: Int) {}
    }

    private var updateListener: GPSTrackingUpdateListener? = null

    init {
        sensorFusionManager.setListener(this)
        sensorFusionManager.initialize()
    }

    fun setUpdateListener(listener: GPSTrackingUpdateListener) {
        updateListener = listener
    }

    fun isCurrentlyTracking(): Boolean = isTracking

    /**
     * Starts GPS tracking with sensor fusion from current location with specified bearing.
     */
    fun startTracking(currentLocation: Location, initialBearingFromCompass: Float) {
        startLatitude = currentLocation.latitude
        startLongitude = currentLocation.longitude
        initialBearing = initialBearingFromCompass
        isTracking = true
        isTargetFound = false

        // Start sensor fusion tracking
        sensorFusionManager.startTracking(currentLocation, initialBearingFromCompass)

        Log.d("GPSTracking", "Started GPS tracking with sensor fusion:")
        Log.d("GPSTracking", "Start: ($startLatitude, $startLongitude)")
        Log.d("GPSTracking", "Bearing: ${initialBearing}°")
        Log.d("GPSTracking", "Target distance: ${targetDistance}m")

        // Notify UI
        updateListener?.onTrackingStarted(initialBearing, targetDistance)
    }

    /**
     * Stops GPS tracking and sensor fusion
     */
    fun stopTracking() {
        isTracking = false
        isTargetFound = false
        sensorFusionManager.stopTracking()

        Log.d("GPSTracking", "GPS tracking with sensor fusion stopped")

        // Notify UI
        updateListener?.onTrackingStopped()
    }

    /**
     * Updates tracking based on current GPS position with auto-reset logic
     */
    fun updatePosition(currentLocation: Location, deviceAzimuth: Float) {
        if (!isTracking) return

        // Check if we should auto-reset due to large distance
        checkAndAutoReset(currentLocation, deviceAzimuth)

        if (isTracking) {
            // Pass to sensor fusion manager for processing
            sensorFusionManager.updateGpsLocation(currentLocation, deviceAzimuth)
        }
    }
    /**
     * Checks if the current location is too far from start and auto-resets if needed
     */
    private fun checkAndAutoReset(currentLocation: Location, deviceAzimuth: Float) {
        val currentTime = System.currentTimeMillis()

        // Only check periodically to avoid constant calculations
        if (currentTime - lastResetCheck < resetCheckInterval) {
            return
        }
        lastResetCheck = currentTime

        // Calculate distance from start
        val distanceFromStart = navigationCalculator.calculateDistance(
            startLatitude, startLongitude,
            currentLocation.latitude, currentLocation.longitude
        )

        // If distance is too large, auto-reset
        if (distanceFromStart > maxDistanceBeforeReset) {
            Log.d("GPSTracking", "🔄 AUTO-RESET: Distance too large (${distanceFromStart}m > ${maxDistanceBeforeReset}m)")
            Log.d("GPSTracking", "   Old start: ($startLatitude, $startLongitude)")
            Log.d("GPSTracking", "   New start: (${currentLocation.latitude}, ${currentLocation.longitude})")

            // Store the current target distance before reset
            val currentTargetDistance = targetDistance

            // Stop current tracking
            stopTracking()

            // Notify UI about the reset
            updateListener?.onTrackingReset(distanceFromStart, currentTargetDistance)

            // Start new tracking from current location
            startTracking(currentLocation, deviceAzimuth)
        }
    }
    // SensorFusionManager.SensorFusionListener implementation
    override fun onFusedPositionUpdate(
        fusedDistance: Float,
        fusedBearing: Float,
        crossTrackError: Float,
        isGpsReliable: Boolean,
        confidence: Float
    ) {
        val currentLat = startLatitude  // For UI purposes, use start location
        val currentLon = startLongitude // Real position is calculated by fusion

        val isOnTrack = abs(crossTrackError) < 2f

        Log.d("GPSTracking", "Sensor fusion update: Fused distance: ${fusedDistance}m Cross-track error: ${crossTrackError}m GPS reliable: $isGpsReliable, Confidence: $confidence")

        // Notify UI with fused data
        updateListener?.onPositionUpdate(
            currentLat, currentLon, fusedDistance, crossTrackError,
            isOnTrack, confidence
        )
    }

    /**
     * User found their target at current location
     */
    fun markTargetFound(currentLocation: Location) {
        if (!isTracking) return

        foundLatitude = currentLocation.latitude
        foundLongitude = currentLocation.longitude
        isTargetFound = true

        // Calculate actual distance from start to found location
        val actualDistance = navigationCalculator.calculateDistance(
            startLatitude, startLongitude, foundLatitude, foundLongitude
        )

        Log.d("GPSTracking", "Target found!")
        Log.d("GPSTracking", "Found at: ($foundLatitude, $foundLongitude)")
        Log.d("GPSTracking", "Actual distance: ${actualDistance}m")

        // Notify UI
        updateListener?.onTargetFound(targetDistance, actualDistance)
    }

    /**
     * Get current tracking info for UI display
     */
    data class TrackingInfo(
        val isTracking: Boolean,
        val initialBearing: Float,
        val targetDistance: Int = 1000, // Always 1000m
        val startLatitude: Double,
        val startLongitude: Double,
        val isTargetFound: Boolean,
        val foundLatitude: Double = 0.0,
        val foundLongitude: Double = 0.0
    )

    fun getTrackingInfo(): TrackingInfo {
        return TrackingInfo(
            isTracking = isTracking,
            initialBearing = initialBearing,
            targetDistance = 1000, // FIXED - always 1000m
            startLatitude = startLatitude,
            startLongitude = startLongitude,
            isTargetFound = isTargetFound,
            foundLatitude = foundLatitude,
            foundLongitude = foundLongitude
        )
    }
}