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
    private val context: Context
) : SensorFusionManager.SensorFusionListener {

    // Navigation state
    private var isTracking = false
    private var initialBearing: Float = 0f
    private var startLatitude: Double = 0.0
    private var startLongitude: Double = 0.0
    private var targetDistance: Int = 50 // Default 50 meters
    private var foundLatitude: Double = 0.0
    private var foundLongitude: Double = 0.0
    private var isTargetFound = false

    private lateinit var sensorFusionManager: SensorFusionManager

    // Callback interface for UI updates
    interface GPSTrackingUpdateListener {
        fun onTrackingStarted(bearing: Float, distance: Int)
        fun onTrackingStopped()
        fun onPositionUpdate(
            currentLat: Double,
            currentLon: Double,
            distanceFromStart: Float,
            crossTrackError: Float,
            distanceRemaining: Float,
            isOnTrack: Boolean,
            confidence: Float  // NEW: confidence in the measurement
        )
        fun onTargetFound(estimatedDistance: Int, actualDistance: Float)
    }

    private var updateListener: GPSTrackingUpdateListener? = null

    init {
        sensorFusionManager = SensorFusionManager(context, navigationCalculator)
        sensorFusionManager.setListener(this)
        sensorFusionManager.initialize()
    }

    fun setUpdateListener(listener: GPSTrackingUpdateListener) {
        updateListener = listener
    }

    fun setTargetDistance(distance: Int) {
        targetDistance = distance
    }

    fun getTargetDistance(): Int = targetDistance

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
     * Updates tracking based on current GPS position (now handled by sensor fusion)
     */
    fun updatePosition(currentLocation: Location, deviceAzimuth: Float) {
        if (!isTracking) return

        // Pass to sensor fusion manager for processing
        sensorFusionManager.updateGpsLocation(currentLocation, deviceAzimuth)
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

        val distanceRemaining = maxOf(0f, targetDistance - fusedDistance)
        val isOnTrack = abs(crossTrackError) < 10f

        Log.d("GPSTracking", "Sensor fusion update:")
        Log.d("GPSTracking", "Fused distance: ${fusedDistance}m")
        Log.d("GPSTracking", "Cross-track error: ${crossTrackError}m")
        Log.d("GPSTracking", "Distance remaining: ${distanceRemaining}m")
        Log.d("GPSTracking", "GPS reliable: $isGpsReliable, Confidence: $confidence")

        // Notify UI with fused data
        updateListener?.onPositionUpdate(
            currentLat, currentLon, fusedDistance, crossTrackError,
            distanceRemaining, isOnTrack, confidence
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
        Log.d("GPSTracking", "Estimated distance: ${targetDistance}m")
        Log.d("GPSTracking", "Actual distance: ${actualDistance}m")
        Log.d("GPSTracking", "Estimation error: ${actualDistance - targetDistance}m")

        // Notify UI
        updateListener?.onTargetFound(targetDistance, actualDistance)
    }

    /**
     * Get current tracking info for UI display
     */
    data class TrackingInfo(
        val isTracking: Boolean,
        val initialBearing: Float,
        val targetDistance: Int,
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
            targetDistance = targetDistance,
            startLatitude = startLatitude,
            startLongitude = startLongitude,
            isTargetFound = isTargetFound,
            foundLatitude = foundLatitude,
            foundLongitude = foundLongitude
        )
    }
}