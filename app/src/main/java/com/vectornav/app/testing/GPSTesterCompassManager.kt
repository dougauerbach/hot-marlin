package com.vectornav.app.testing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.*

class GPSTesterCompassManager(
    private val context: Context,
    private val updateCallback: (CompassUpdate) -> Unit
) : SensorEventListener {

    data class CompassUpdate(
        val realAzimuth: Float,
        val pitch: Float,
        val roll: Float,
        val accuracy: Int,
        val isVertical: Boolean
    )

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var magneticFieldSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null

    private var realCompassAzimuth: Float = 0f
    private var realCompassPitch: Float = 0f
    private var realCompassRoll: Float = 0f
    private var compassAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
    private val azimuthFilter = SensorFilter(0.15f)

    var mockCompassAzimuth = 90f
    var isComparingWithMock = false
    private val compassCalibrationHistory = mutableListOf<CompassCalibrationRecord>()

    init {
        setupSensors()
    }

    private fun setupSensors() {
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (rotationVectorSensor == null) {
            magneticFieldSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
    }

    fun startListening() {
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        if (rotationVectorSensor == null) {
            magneticFieldSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            accelerometerSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    updateDeviceOrientation(it.values)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (sensor?.type) {
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_MAGNETIC_FIELD -> {
                compassAccuracy = accuracy
                notifyUpdate()
            }
        }
    }

    private fun updateDeviceOrientation(rotationVector: FloatArray) {
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)

        val remappedMatrix = FloatArray(9)
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_X,
            SensorManager.AXIS_Z,
            remappedMatrix
        )

        val orientation = FloatArray(3)
        SensorManager.getOrientation(remappedMatrix, orientation)

        val rawAzimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        realCompassPitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
        realCompassRoll = Math.toDegrees(orientation[2].toDouble()).toFloat()

        val normalizedAzimuth = if (rawAzimuth < 0) rawAzimuth + 360f else rawAzimuth
        realCompassAzimuth = azimuthFilter.filter(normalizedAzimuth)

        notifyUpdate()

        if (isComparingWithMock) {
            recordCompassComparison()
        }
    }

    private fun notifyUpdate() {
        updateCallback(
            CompassUpdate(
                realAzimuth = realCompassAzimuth,
                pitch = realCompassPitch,
                roll = realCompassRoll,
                accuracy = compassAccuracy,
                isVertical = isPhoneVertical()
            )
        )
    }

    private fun isPhoneVertical(): Boolean {
        return abs(realCompassPitch) < 45f && abs(realCompassRoll) < 45f
    }

    fun useRealCompass(): Float {
        mockCompassAzimuth = realCompassAzimuth
        return realCompassAzimuth
    }

    fun setMockCompass(azimuth: Float) {
        mockCompassAzimuth = azimuth
    }

    fun getCompassDirection(azimuth: Float): String {
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

    fun startCalibration(progressCallback: (Int, String) -> Unit) {
        val calibrationStartTime = System.currentTimeMillis()
        val handler = Handler(Looper.getMainLooper())

        val calibrationMonitor = object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - calibrationStartTime) / 1000

                if (elapsed < 30) {
                    val progress = (elapsed * 100 / 30).toInt()
                    val status = when (compassAccuracy) {
                        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "🟢 HIGH - Perfect!"
                        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "🟡 MEDIUM - Keep going"
                        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "🟠 LOW - Keep moving"
                        SensorManager.SENSOR_STATUS_UNRELIABLE -> "🔴 UNRELIABLE - Move faster"
                        else -> "❓ UNKNOWN"
                    }
                    progressCallback(progress, status)
                    handler.postDelayed(this, 1000)
                } else {
                    val finalStatus = when (compassAccuracy) {
                        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "✅ SUCCESS - Compass calibrated!"
                        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "⚠️ PARTIAL - Better but could improve"
                        else -> "❌ FAILED - Try again or check environment"
                    }
                    progressCallback(100, finalStatus)
                }
            }
        }

        handler.post(calibrationMonitor)
    }

    fun testAccuracy(resultCallback: (String) -> Unit) {
        val readings = mutableListOf<Float>()
        val testStartTime = System.currentTimeMillis()
        val handler = Handler(Looper.getMainLooper())

        val accuracyTest = object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - testStartTime) / 1000

                if (elapsed < 15) {
                    readings.add(realCompassAzimuth)
                    handler.postDelayed(this, 500)
                } else {
                    val mean = readings.average().toFloat()
                    val variance = readings.map { (it - mean) * (it - mean) }.average().toFloat()
                    val stdDev = sqrt(variance)

                    val stabilityGrade = when {
                        stdDev < 2f -> "✅ EXCELLENT - Very stable (±${stdDev.format(1)}°)"
                        stdDev < 5f -> "⚠️ GOOD - Mostly stable (±${stdDev.format(1)}°)"
                        stdDev < 10f -> "🔧 FAIR - Some drift (±${stdDev.format(1)}°)"
                        else -> "❌ POOR - Very unstable (±${stdDev.format(1)}°)"
                    }
                    resultCallback(stabilityGrade)
                }
            }
        }

        handler.post(accuracyTest)
    }

    private fun recordCompassComparison() {
        val difference = abs(realCompassAzimuth - mockCompassAzimuth).let { diff ->
            if (diff > 180f) 360f - diff else diff
        }

        val record = CompassCalibrationRecord(
            timestamp = System.currentTimeMillis(),
            realAzimuth = realCompassAzimuth,
            mockAzimuth = mockCompassAzimuth,
            difference = difference,
            phoneVertical = isPhoneVertical(),
            accuracy = compassAccuracy
        )

        compassCalibrationHistory.add(record)
        if (compassCalibrationHistory.size > 20) {
            compassCalibrationHistory.removeAt(0)
        }
    }

    fun getAccuracyStatus(): String {
        return when (compassAccuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "🟢 Good"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "🟡 OK"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "🟠 Poor"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "🔴 Bad"
            else -> "❓ Unknown"
        }
    }

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)
}

// Simple sensor filter for smoothing compass readings
class SensorFilter(private val alpha: Float) {
    private var filteredValue: Float = 0f
    private var isInitialized = false

    fun filter(value: Float): Float {
        if (!isInitialized) {
            filteredValue = value
            isInitialized = true
            return filteredValue
        }

        // Handle compass wrap-around (359° -> 1°)
        var diff = value - filteredValue
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f

        filteredValue += alpha * diff
        if (filteredValue >= 360f) filteredValue -= 360f
        if (filteredValue < 0f) filteredValue += 360f

        return filteredValue
    }
}