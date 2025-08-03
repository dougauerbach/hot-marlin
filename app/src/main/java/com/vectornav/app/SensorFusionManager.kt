package com.vectornav.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.util.Log
import kotlin.math.*

/**
 * Fuses GPS, step counter, and compass data for improved accuracy at short distances.
 * Provides smooth, accurate position tracking by combining multiple sensor inputs.
 */
class SensorFusionManager(
    private val context: Context,
    private val navigationCalculator: NavigationCalculator
) : SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null

    // Sensor data
    private var initialStepCount = 0f
    private var currentStepCount = 0f
    private var lastAcceleration = FloatArray(3)
    private var isMoving = false

    // Fusion state
    private var startLocation: Location? = null
    private var targetBearing: Float = 0f
    private var lastGpsLocation: Location? = null
    private var lastGpsTime: Long = 0
    private var fusedDistance: Float = 0f
    private var fusedBearing: Float = 0f

    // Configuration
    private val averageStepLength = 0.7f // meters per step (average adult)
    private val gpsReliabilityThreshold = 5.0f // meters
    private val movementThreshold = 2.0f // m/s² acceleration threshold

    // Callback for fused data
    interface SensorFusionListener {
        fun onFusedPositionUpdate(
            fusedDistance: Float,
            fusedBearing: Float,
            crossTrackError: Float,
            isGpsReliable: Boolean,
            confidence: Float
        )
    }

    private var listener: SensorFusionListener? = null

    fun setListener(listener: SensorFusionListener) {
        this.listener = listener
    }

    fun initialize() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Check for step counter (Android 4.4+)
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (stepCounterSensor != null) {
            Log.d("SensorFusion", "Step counter available - enabling step-based distance tracking")
        } else {
            Log.d("SensorFusion", "Step counter not available - using GPS-only mode")
        }

        if (accelerometerSensor != null) {
            Log.d("SensorFusion", "Accelerometer available - enabling movement detection")
        }
    }

    fun startTracking(startLocation: Location, bearing: Float) {
        this.startLocation = startLocation
        this.targetBearing = bearing
        this.lastGpsLocation = startLocation
        this.lastGpsTime = System.currentTimeMillis()
        this.fusedDistance = 0f
        this.fusedBearing = bearing

        // Reset step counter baseline
        initialStepCount = currentStepCount

        // Register sensors
        stepCounterSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        accelerometerSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        Log.d("SensorFusion", "Started sensor fusion tracking at $startLocation with bearing $bearing°")
    }

    fun stopTracking() {
        sensorManager.unregisterListener(this)
        startLocation = null
        lastGpsLocation = null
        Log.d("SensorFusion", "Stopped sensor fusion tracking")
    }

    fun updateGpsLocation(newLocation: Location, deviceAzimuth: Float) {
        val startLoc = startLocation ?: return

        // Calculate GPS-based distance and bearing
        val gpsDistance = navigationCalculator.calculateDistance(
            startLoc.latitude, startLoc.longitude,
            newLocation.latitude, newLocation.longitude
        )

        val gpsBearing = if (lastGpsLocation != null) {
            // Calculate movement direction from GPS
            navigationCalculator.calculateBearing(
                lastGpsLocation!!.latitude, lastGpsLocation!!.longitude,
                newLocation.latitude, newLocation.longitude
            )
        } else {
            deviceAzimuth // Fall back to compass
        }

        // Determine GPS reliability
        val timeDelta = (System.currentTimeMillis() - lastGpsTime) / 1000.0f
        val gpsSpeed = if (lastGpsLocation != null && timeDelta > 0) {
            navigationCalculator.calculateDistance(
                lastGpsLocation!!.latitude, lastGpsLocation!!.longitude,
                newLocation.latitude, newLocation.longitude
            ) / timeDelta
        } else 0f

        val isGpsReliable = newLocation.accuracy < gpsReliabilityThreshold &&
                gpsDistance > newLocation.accuracy &&
                gpsSpeed < 10f // Reasonable walking speed

        // Fuse GPS with step counter data
        val stepBasedDistance = getStepBasedDistance()

        fusedDistance = when {
            !isGpsReliable && stepCounterSensor != null -> {
                // GPS unreliable, use steps
                Log.d("SensorFusion", "Using step-based distance: ${stepBasedDistance}m (GPS unreliable)")
                stepBasedDistance
            }
            gpsDistance < 5f && stepCounterSensor != null -> {
                // Short distance, blend GPS and steps
                val stepWeight = 0.6f
                val gpsWeight = 0.4f
                val blended = stepBasedDistance * stepWeight + gpsDistance * gpsWeight
                Log.d("SensorFusion", "Blending: steps=${stepBasedDistance}m, GPS=${gpsDistance}m, result=${blended}m")
                blended
            }
            else -> {
                // Long distance or no step counter, trust GPS
                Log.d("SensorFusion", "Using GPS distance: ${gpsDistance}m")
                gpsDistance
            }
        }

        // Fuse bearing data
        fusedBearing = when {
            !isGpsReliable -> {
                // GPS unreliable, use compass with heavy filtering
                fuseAngles(fusedBearing, deviceAzimuth, 0.1f) // Heavy filtering
            }
            gpsDistance > 3f -> {
                // Sufficient GPS movement, trust GPS bearing
                gpsBearing
            }
            else -> {
                // Short distance, blend GPS and compass
                fuseAngles(gpsBearing, deviceAzimuth, 0.3f) // Light blending
            }
        }

        // Calculate cross-track error
        val crossTrackError = navigationCalculator.calculateCrossTrackError(
            newLocation.latitude, newLocation.longitude,
            startLoc.latitude, startLoc.longitude,
            targetBearing
        )

        // Calculate confidence based on sensor agreement
        val confidence = calculateConfidence(isGpsReliable, stepCounterSensor != null, isMoving)

        // Update tracking state
        lastGpsLocation = newLocation
        lastGpsTime = System.currentTimeMillis()

        // Notify listener
        listener?.onFusedPositionUpdate(
            fusedDistance, fusedBearing, crossTrackError, isGpsReliable, confidence
        )

        Log.d("SensorFusion", "Fused update: distance=${fusedDistance}m, bearing=${fusedBearing}°, confidence=${confidence}")
    }

    private fun getStepBasedDistance(): Float {
        return if (stepCounterSensor != null) {
            val stepsTaken = currentStepCount - initialStepCount
            stepsTaken * averageStepLength
        } else {
            0f
        }
    }

    private fun fuseAngles(angle1: Float, angle2: Float, weight: Float): Float {
        // Handle angle wrapping for proper fusion
        val diff = angle2 - angle1
        val normalizedDiff = when {
            diff > 180f -> diff - 360f
            diff < -180f -> diff + 360f
            else -> diff
        }

        val result = angle1 + normalizedDiff * weight
        return when {
            result < 0f -> result + 360f
            result >= 360f -> result - 360f
            else -> result
        }
    }

    private fun calculateConfidence(gpsReliable: Boolean, hasSteps: Boolean, moving: Boolean): Float {
        return when {
            gpsReliable && moving -> 0.9f          // High confidence
            gpsReliable && !moving -> 0.7f         // Good confidence
            !gpsReliable && hasSteps && moving -> 0.6f  // Medium confidence
            !gpsReliable && hasSteps && !moving -> 0.4f // Low confidence
            else -> 0.2f                           // Very low confidence
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> {
                    currentStepCount = it.values[0]
                    Log.d("SensorFusion", "Step count: $currentStepCount (taken: ${currentStepCount - initialStepCount})")
                }

                Sensor.TYPE_ACCELEROMETER -> {
                    val acceleration = sqrt(
                        it.values[0].pow(2) + it.values[1].pow(2) + it.values[2].pow(2)
                    ) - SensorManager.GRAVITY_EARTH

                    isMoving = abs(acceleration) > movementThreshold
                    lastAcceleration = it.values.clone()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d("SensorFusion", "Sensor accuracy changed: ${sensor?.name} = $accuracy")
    }
}