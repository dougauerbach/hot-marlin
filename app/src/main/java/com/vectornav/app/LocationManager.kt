package com.vectornav.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*

class LocationManager(private val context: Context) {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var userLocationCallback: ((Location) -> Unit)? = null

    // Add tracking for movement state
    private var currentSpeed: Float = 0f
    private var isMoving: Boolean = false
    private var currentInterval: Long = 2000L // Track current update interval
    private var isLocationUpdatesActive = false

    init {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    }

    fun setLocationCallback(callback: (Location) -> Unit) {
        userLocationCallback = callback
    }

    fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // Start with default interval
        startLocationUpdatesWithInterval(2000L)
    }

    private fun startLocationUpdatesWithInterval(intervalMs: Long) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // Stop existing updates if any
        if (isLocationUpdatesActive) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }

        currentInterval = intervalMs

        // Determine priority based on interval (shorter interval = higher priority)
        val priority = when {
            intervalMs <= 500L -> Priority.PRIORITY_HIGH_ACCURACY
            intervalMs <= 2000L -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            else -> Priority.PRIORITY_LOW_POWER
        }

        val locationRequest = LocationRequest.Builder(
            priority,
            intervalMs
        ).setMinUpdateIntervalMillis(intervalMs / 2).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                Log.d("LocationManager", "📍 LocationManager received update!")
                locationResult.lastLocation?.let { location ->
                    Log.d("LocationManager", "📍 New location: lat=${location.latitude}, lon=${location.longitude}, accuracy=${location.accuracy}")

                    // Update movement detection
                    updateMovementState(location)

                    // Check if we should adapt the update interval
                    checkAndUpdateInterval()

                    // Call user callback
                    Log.d("LocationManager", "📍 Calling user callback...")
                    userLocationCallback?.invoke(location)
                } ?: run {
                    Log.w("LocationManager", "❌ Location result was null!")
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        isLocationUpdatesActive = true
        Log.d("LocationManager", "Started location updates with interval: ${intervalMs}ms, priority: $priority")
    }

    private fun updateMovementState(location: Location) {
        // Update speed and movement detection
        val previousSpeed = currentSpeed
        currentSpeed = if (location.hasSpeed()) location.speed else 0f

        // Consider moving if speed > 0.5 m/s (1.8 km/h) - walking pace
        val wasMoving = isMoving
        isMoving = currentSpeed > 0.5f

        // Log movement state changes
        if (isMoving != wasMoving) {
            Log.d("LocationManager", "Movement state changed: ${if (isMoving) "moving" else "stationary"} (speed: ${currentSpeed}m/s)")
        }
    }

    private fun checkAndUpdateInterval() {
        val optimalInterval = getAdaptiveUpdateInterval(isMoving, currentSpeed)

        // TEMPORARILY DISABLE FOR EMULATOR TESTING
        // Only restart if interval changed significantly (>= 500ms difference)
        // if (kotlin.math.abs(optimalInterval - currentInterval) >= 500L) {
        //     Log.d("LocationManager", "Adapting interval: ${currentInterval}ms -> ${optimalInterval}ms (speed: ${currentSpeed}m/s, moving: $isMoving)")
        //     startLocationUpdatesWithInterval(optimalInterval)
        // }
    }

    private fun getAdaptiveUpdateInterval(isMoving: Boolean, speed: Float): Long {
        return when {
            !isMoving -> 5000L // 5 seconds when stationary (save battery)
            speed < 1f -> 2000L // 2 seconds when walking slowly
            speed < 3f -> 1000L // 1 second when walking normally
            speed < 8f -> 500L  // 0.5 seconds when jogging/cycling
            else -> 250L        // 0.25 seconds when moving very fast
        }
    }

    // Add method to manually update priority (optional)
    fun updateLocationRequestPriority(highAccuracy: Boolean) {
        if (!isLocationUpdatesActive) return

        val newInterval = if (highAccuracy) 1000L else 3000L
        startLocationUpdatesWithInterval(newInterval)
    }

    fun getCurrentLocation(callback: (Location) -> Unit) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                callback(location)
            } else {
                // If no cached location, request a fresh one
                val locationRequest = LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    1000L
                ).setMaxUpdates(1).build()

                val singleLocationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        locationResult.lastLocation?.let { newLocation ->
                            callback(newLocation)
                            fusedLocationClient.removeLocationUpdates(this)
                        }
                    }
                }

                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    singleLocationCallback,
                    Looper.getMainLooper()
                )
            }
        }
    }

    fun stopLocationUpdates() {
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            isLocationUpdatesActive = false
            Log.d("LocationManager", "Stopped location updates")
        }
    }

    // Getter methods for debugging/monitoring
    fun getCurrentSpeed(): Float = currentSpeed
    fun isCurrentlyMoving(): Boolean = isMoving
    fun getCurrentUpdateInterval(): Long = currentInterval
}