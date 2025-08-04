package com.vectornav.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.vectornav.app.databinding.ActivityMainBinding
import com.vectornav.app.testing.GPSTesterActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

data class BreadcrumbPoint(
    val lat: Double,
    val lon: Double,
    val timestamp: Long,
    val distanceFromStart: Float = 0f,
    val accuracy: Float = 0f
)

class MainActivity : AppCompatActivity(),
    SensorEventListener,
    NavigationController.NavigationUpdateListener,
    GPSTrackingController.GPSTrackingUpdateListener {

    lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var locationManager: LocationManager
    private lateinit var navigationCalculator: NavigationCalculator
    private lateinit var navigationLineManager: NavigationLineManager

    // Breadcrumbs
    private val breadcrumbs = mutableListOf<BreadcrumbPoint>()
    private var lastBreadcrumbLocation: Location? = null
    private val breadcrumbMinDistance = 2f // meters between breadcrumbs
    private val maxBreadcrumbs = 100

    // Haptic feedback
    private lateinit var vibrator: Vibrator
    private var lastHapticTime: Long = 0
    private val hapticCooldownMs = 1000L // Prevent too frequent vibrations

    // Dual navigation controllers
    private lateinit var arNavigationController: NavigationController  // Renamed for clarity
    private lateinit var gpsTrackingController: GPSTrackingController

    // View mode state
    private var isGpsViewMode = false

    // Sensor components
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null

    // Device orientation data with filtering
    private var currentAzimuth: Float = 0f
    private var currentPitch: Float = 0f
    private var currentRoll: Float = 0f
    private var filteredAzimuth: Float = 0f
    private val azimuthFilter = SensorFilter(0.15f)

    // Update rate limiting
    private var lastUpdateTime: Long = 0
    private var lastNavigationUpdateTime: Long = 0
    private var lastLocationUpdateTime: Long = 0
    private val updateIntervalMs = 100
    private val navigationUpdateIntervalMs = 50
    private var lastNavigationAzimuth: Float = 0f
    private val minimumAzimuthChange = 0.2f

    private var hapticFeedbackEnabled = true

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false

        if (cameraGranted && locationGranted) {
            startCamera()
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Camera and Location permissions required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun addBreadcrumb(location: Location, distanceFromStart: Float = 0f) {
        // Only add breadcrumb if we've moved enough distance
        lastBreadcrumbLocation?.let { lastLoc ->
            val distance = navigationCalculator.calculateDistance(
                lastLoc.latitude, lastLoc.longitude,
                location.latitude, location.longitude
            )
            if (distance < breadcrumbMinDistance) return
        }

        val breadcrumb = BreadcrumbPoint(
            lat = location.latitude,
            lon = location.longitude,
            timestamp = System.currentTimeMillis(),
            distanceFromStart = distanceFromStart,
            accuracy = location.accuracy
        )

        breadcrumbs.add(breadcrumb)
        lastBreadcrumbLocation = location

        // Keep only recent breadcrumbs
        if (breadcrumbs.size > maxBreadcrumbs) {
            breadcrumbs.removeAt(0)
        }

        Log.d("VectorNav", "Added breadcrumb #${breadcrumbs.size}: distance=${distanceFromStart}m, accuracy=${location.accuracy}m")
    }

    private fun clearBreadcrumbs() {
        breadcrumbs.clear()
        lastBreadcrumbLocation = null
        Log.d("VectorNav", "Cleared breadcrumb trail")
    }

    private fun getAdaptiveUpdateInterval(isMoving: Boolean, speed: Float): Long {
        return when {
            !isMoving -> 2000L // Slow updates when stationary
            speed < 1f -> 1000L // Walking speed
            speed < 5f -> 500L  // Fast walking/jogging
            else -> 250L        // High speed updates
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        locationManager = LocationManager(this)
        navigationCalculator = NavigationCalculator()
        navigationLineManager = NavigationLineManager(this)

        // Initialize both navigation controllers
        arNavigationController = NavigationController(navigationCalculator, navigationLineManager)
        gpsTrackingController = GPSTrackingController(navigationCalculator, context = this)

        // Set update listeners
        arNavigationController.setUpdateListener(this)
        gpsTrackingController.setUpdateListener(this)

        // Initialize sensors
        setupSensors()

        // Request permissions
        if (allPermissionsGranted()) {
            startCamera()
            startLocationUpdates()
        } else {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }

        // Set up touch listener for target selection
        binding.cameraPreview.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                handleScreenTap()
                true
            } else false
        }

        // Set up GPS tracking view touch listener
        binding.gpsTrackingView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                handleScreenTap()
                true
            } else false
        }

        // Set up distance tap listener for editing
        binding.distanceText.setOnClickListener {
            if (isAnyNavigationActive()) {
                showDistanceEditDialog()
            }
        }

        // Set up view toggle button
        binding.toggleViewButton.setOnClickListener {
            toggleView()
        }

        // Set up "Found It!" button
        binding.foundItButton.setOnClickListener {
            markTargetFound()
        }

        // Location updates callback
        locationManager.setLocationCallback { location ->
            // Determine if user is moving based on location speed
            val isMoving = location.hasSpeed() && location.speed > 0.5f
            val speed = if (location.hasSpeed()) location.speed else 0f

            // Get adaptive interval (but don't change location updates, just UI updates)
            val uiUpdateInterval = getAdaptiveUpdateInterval(isMoving, speed)

            // Only update if enough time has passed
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastLocationUpdateTime > uiUpdateInterval) {
                lastLocationUpdateTime = currentTime

                if (isGpsViewMode && gpsTrackingController.isCurrentlyTracking()) {
                    gpsTrackingController.updatePosition(location, filteredAzimuth)
                } else if (!isGpsViewMode && arNavigationController.isCurrentlyNavigating()) {
                    arNavigationController.updateNavigation(location, filteredAzimuth)
                }
            }
        }

        // Initialize view mode
        switchToCompassView()

        // Initialize the vibrator based on the Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibrator = vibratorManager.defaultVibrator
        } else {
            // Suppress the deprecation warning for the necessary fallback
            @Suppress("DEPRECATION")
            val oldVibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator = oldVibrator
        }

        // GPSTester helper: START
        val testLaunchButton = Button(this).apply {
            text = "🔧 Test"
            textSize = 12f
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT,
                80 // Fixed height
            ).apply {
                // Center horizontally
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                setMargins(8, 80, 8, 0)
            }
            setOnClickListener { GPSTesterActivity.launch(this@MainActivity) }
            setBackgroundColor(0xFF000000.toInt()) // Black background
            setTextColor(0xFFFFFFFF.toInt())       // White text
        }
        (binding.root as androidx.constraintlayout.widget.ConstraintLayout).addView(testLaunchButton)

        // GPSTester helper: END
    }

    private fun handleScreenTap() {
        if (isAnyNavigationActive()) {
            // Stop current navigation
            stopAllNavigation()
        } else {
            // Start new navigation
            startNavigation()
        }
    }

    private fun startNavigation() {
        // Check if phone is vertical enough for accurate compass reading
        if (!isPhoneAcceptablyVertical()) {
            Toast.makeText(this, "Hold phone more vertically for accurate compass", Toast.LENGTH_SHORT).show()
            return
        }

        // Request high accuracy when starting navigation
        locationManager.updateLocationRequestPriority(highAccuracy = true)

        locationManager.getCurrentLocation { location ->
            if (isGpsViewMode) {
                // Start GPS tracking
                gpsTrackingController.startTracking(location, filteredAzimuth)
            } else {
                // Start AR navigation
                arNavigationController.setNavigationTarget(location, filteredAzimuth)
            }
        }
    }

    private fun stopAllNavigation() {
        arNavigationController.stopNavigation()
        gpsTrackingController.stopTracking()
        // Reduce to normal accuracy when navigation stops (save battery)
        locationManager.updateLocationRequestPriority(highAccuracy = false)
    }
    // Optional: Add a method to monitor location performance
    private fun logLocationStats() {
        Log.d("VectorNav", "Location stats:")
        Log.d("VectorNav", "- Speed: ${locationManager.getCurrentSpeed()}m/s")
        Log.d("VectorNav", "- Moving: ${locationManager.isCurrentlyMoving()}")
        Log.d("VectorNav", "- Update interval: ${locationManager.getCurrentUpdateInterval()}ms")
    }

    // Optional: Call this periodically to see how the adaptive system is working
    private fun startLocationMonitoring() {
        val handler = android.os.Handler(Looper.getMainLooper())
        val monitoringRunnable = object : Runnable {
            override fun run() {
                if (isAnyNavigationActive()) {
                    logLocationStats()
                    handler.postDelayed(this, 10000) // Log every 10 seconds during navigation
                }
            }
        }
        handler.post(monitoringRunnable)
    }
    private fun isAnyNavigationActive(): Boolean {
        return arNavigationController.isCurrentlyNavigating() || gpsTrackingController.isCurrentlyTracking()
    }

    private fun toggleView() {
        if (isGpsViewMode) {
            switchToCompassView()
        } else {
            switchToGpsView()
        }
    }

    private fun switchToCompassView() {
        isGpsViewMode = false

        // Show AR elements
        binding.cameraPreview.visibility = View.VISIBLE
        binding.gpsTrackingView.visibility = View.GONE
        binding.foundItButton.visibility = View.GONE

        // Update toggle button
        binding.toggleViewButton.text = "Switch to GPS View"

        // Transfer navigation state if needed
        if (gpsTrackingController.isCurrentlyTracking()) {
            val trackingInfo = gpsTrackingController.getTrackingInfo()
            gpsTrackingController.stopTracking()

            locationManager.getCurrentLocation { location ->
                arNavigationController.setNavigationTarget(location, trackingInfo.initialBearing)
                arNavigationController.setTargetDistance(trackingInfo.targetDistance)
            }
        }

        android.util.Log.d("VectorNav", "Switched to Compass (AR) view")
    }

    private fun switchToGpsView() {
        isGpsViewMode = true

        // Show GPS elements
        binding.cameraPreview.visibility = View.GONE
        binding.gpsTrackingView.visibility = View.VISIBLE
        binding.foundItButton.visibility = View.VISIBLE

        // Update toggle button
        binding.toggleViewButton.text = "Switch to Compass View"

        // Transfer navigation state if needed
        if (arNavigationController.isCurrentlyNavigating()) {
            val navInfo = arNavigationController.getNavigationInfo()
            arNavigationController.stopNavigation()

            locationManager.getCurrentLocation { location ->
                gpsTrackingController.startTracking(location, navInfo.targetBearing)
                gpsTrackingController.setTargetDistance(navInfo.targetDistance)
            }
        }

        android.util.Log.d("VectorNav", "Switched to GPS tracking view")
    }

    private fun markTargetFound() {
        if (gpsTrackingController.isCurrentlyTracking()) {
            locationManager.getCurrentLocation { location ->
                gpsTrackingController.markTargetFound(location)
            }
        }
    }

    private fun normalizeAngle(angle: Float): Float {
        var normalized = angle
        while (normalized > 180f) normalized -= 360f
        while (normalized < -180f) normalized += 360f
        return normalized
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun provideTactileGuidance(relativeBearing: Float, distance: Int) {
        if (!hapticFeedbackEnabled) return

        val currentTime = System.currentTimeMillis()

        // Cooldown to prevent constant vibration
        if (currentTime - lastHapticTime < hapticCooldownMs) return

        // Only vibrate if vibrator is available
        if (!::vibrator.isInitialized || !vibrator.hasVibrator()) return

        when {
            distance < 5 -> {
                // Very close - excited rapid pulses
                val pattern = longArrayOf(0, 50, 50, 50, 50, 50)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
                lastHapticTime = currentTime
            }

            abs(relativeBearing) < 5f -> {
                // On target - gentle confirmation pulse
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }
                lastHapticTime = currentTime
            }

            abs(relativeBearing) > 15f -> {
                // Way off course - stronger correction pattern
                val intensity = min(abs(relativeBearing) / 45f, 1f)
                val pattern = longArrayOf(0, 200, 100, 200)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val amplitudes = intArrayOf(0, (intensity * 255).toInt(), 0, (intensity * 255).toInt())
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
                lastHapticTime = currentTime
            }
        }
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    fun toggleHapticFeedback() {
        hapticFeedbackEnabled = !hapticFeedbackEnabled
        Toast.makeText(this,
            "Haptic feedback ${if (hapticFeedbackEnabled) "enabled" else "disabled"}",
            Toast.LENGTH_SHORT).show()

        // Give a quick test vibration when enabling
        if (hapticFeedbackEnabled && ::vibrator.isInitialized && vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        }
    }

    private fun updateProgressIndicator(distanceFromStart: Float, targetDistance: Int, distanceRemaining: Float) {
        val progress = if (targetDistance > 0) {
            (distanceFromStart / targetDistance * 100f).coerceIn(0f, 100f)
        } else 0f

        // Change instruction text color based on progress
        val textColor = when {
            distanceRemaining < 5f -> ContextCompat.getColor(this, R.color.progress_complete)
            progress < 25f -> ContextCompat.getColor(this, R.color.progress_start)
            progress < 75f -> ContextCompat.getColor(this, R.color.progress_middle)
            else -> ContextCompat.getColor(this, R.color.progress_near_target)
        }

        binding.instructionText.setTextColor(textColor)

        // Add emoji to existing instruction text
        val progressEmoji = when {
            progress < 25f -> "🟢" // Green start
            progress < 50f -> "🟡" // Yellow middle
            progress < 75f -> "🟠" // Orange getting close
            progress < 95f -> "🔴" // Red almost there
            else -> "🎯" // Target reached
        }
        val currentInstruction = binding.instructionText.text.toString()
        if (!currentInstruction.startsWith(progressEmoji)) {
            binding.instructionText.text = "$progressEmoji $currentInstruction"
        }


        // Add progress percentage to distance text
        val progressText = when {
            distanceRemaining < 5f -> "🎯 Almost there! (${progress.toInt()}%)"
            progress < 10f -> "Just started - ${distanceRemaining.toInt()}m to go"
            progress > 90f -> "So close! ${distanceRemaining.toInt()}m left (${progress.toInt()}%)"
            else -> "${distanceRemaining.toInt()}m remaining (${progress.toInt()}% complete)"
        }

        // Update the distance text to include progress
        binding.distanceText.text = progressText

        Log.d("VectorNav", "Progress: ${progress}%, distance remaining: ${distanceRemaining}m")
    }

    // AR Navigation callbacks
    override fun onNavigationStarted(targetBearing: Float, targetDistance: Int) {
        showNavigationUI()
        binding.distanceText.text = "Distance: ${targetDistance}m"

        Toast.makeText(this, "AR Navigation started! Bearing: ${targetBearing.toInt()}°", Toast.LENGTH_SHORT).show()
    }

    override fun onNavigationStopped() {
        hideNavigationUI()
    }

    override fun onNavigationUpdate(
        currentDistance: Int,
        relativeBearing: Float,
        isOnCourse: Boolean,
        guidanceText: String,
        statusText: String
    ) {
        if (!isPhoneAcceptablyVertical()) {
            showVerticalOrientationWarning()
            return
        }

        binding.distanceText.text = "Distance: ${currentDistance}m"
        binding.instructionText.text = guidanceText
        binding.instructionText.visibility = View.VISIBLE
        binding.instructionText.bringToFront()

        provideTactileGuidance(relativeBearing, currentDistance)
    }

    // GPS Tracking callbacks
    override fun onTrackingStarted(bearing: Float, distance: Int) {
        showNavigationUI()
        binding.distanceText.text = "Distance: ${distance}m"
        binding.instructionText.text = "GPS tracking active - follow the line!"
        binding.instructionText.visibility = View.VISIBLE

        Toast.makeText(this, "GPS Tracking started! Bearing: ${bearing.toInt()}°", Toast.LENGTH_SHORT).show()
    }

    override fun onTrackingStopped() {
        hideNavigationUI()
        binding.gpsTrackingView.clearTracking()
    }

    // GPS Tracking callback
    override fun onPositionUpdate(
        currentLat: Double,
        currentLon: Double,
        distanceFromStart: Float,
        crossTrackError: Float,
        distanceRemaining: Float,
        isOnTrack: Boolean,
        confidence: Float
    ) {
        val trackingInfo = gpsTrackingController.getTrackingInfo()

        // Add breadcrumb for current position
        locationManager.getCurrentLocation { location ->
            addBreadcrumb(location, distanceFromStart)
        }

        // Update GPS tracking view with breadcrumbs
        binding.gpsTrackingView.updateTrackingWithBreadcrumbs(
            trackingInfo.startLatitude,
            trackingInfo.startLongitude,
            currentLat,
            currentLon,
            trackingInfo.initialBearing,
            trackingInfo.targetDistance,
            crossTrackError,
            distanceFromStart,
            isOnTrack,
            breadcrumbs
        )

        // Update progress indicator
        updateProgressIndicator(distanceFromStart, trackingInfo.targetDistance, distanceRemaining)

        // Calculate relative bearing for tactile guidance
        val relativeBearing = normalizeAngle(trackingInfo.initialBearing - filteredAzimuth)
        provideTactileGuidance(relativeBearing, distanceRemaining.toInt())

        // Update distance display
        binding.distanceText.text = "Remaining: ${distanceRemaining.toInt()}m"

        // Update instruction text
        binding.instructionText.text = when {
            distanceRemaining < 5 -> "You're close! Look around for your target."
            isOnTrack -> "On track - keep going straight!"
            crossTrackError > 0 -> "Move left to get back on track"
            else -> "Move right to get back on track"
        }
        binding.instructionText.visibility = View.VISIBLE
    }

    override fun onTargetFound(estimatedDistance: Int, actualDistance: Float) {
        val error = actualDistance - estimatedDistance
        val errorText = if (error > 0) "${error.toInt()}m over" else "${(-error).toInt()}m under"

        Toast.makeText(
            this,
            "🎯 Target Found!\nEstimated: ${estimatedDistance}m\nActual: ${actualDistance.toInt()}m\n($errorText)",
            Toast.LENGTH_LONG
        ).show()

        binding.instructionText.text = "Target found! Actual distance: ${actualDistance.toInt()}m"
    }

    private fun showNavigationUI() {
        binding.crosshairVertical.visibility = View.GONE
        binding.crosshairHorizontal.visibility = View.GONE
        binding.crosshairCenter.visibility = View.GONE
        binding.instructionText.visibility = View.GONE
        binding.navigationInfo.visibility = View.VISIBLE
    }

    private fun hideNavigationUI() {
        binding.crosshairVertical.visibility = View.VISIBLE
        binding.crosshairHorizontal.visibility = View.VISIBLE
        binding.crosshairCenter.visibility = View.VISIBLE
        binding.instructionText.visibility = View.VISIBLE
        binding.instructionText.text = "Tap screen to set navigation target"
        binding.navigationInfo.visibility = View.GONE
    }

    private fun showDistanceEditDialog() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Set Target Distance")

        val input = android.widget.EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER

        val currentDistance = if (isGpsViewMode) {
            gpsTrackingController.getTargetDistance()
        } else {
            arNavigationController.getTargetDistance()
        }

        input.setText(currentDistance.toString())
        builder.setView(input)

        builder.setPositiveButton("OK") { _, _ ->
            val newDistance = input.text.toString().toIntOrNull()
            if (newDistance != null && newDistance > 0) {
                if (isGpsViewMode) {
                    gpsTrackingController.setTargetDistance(newDistance)
                } else {
                    arNavigationController.setTargetDistance(newDistance)
                }
                binding.distanceText.text = "Distance: ${newDistance}m"
                Toast.makeText(this, "Distance updated to ${newDistance}m", Toast.LENGTH_SHORT).show()
            }
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.cancel()
        }

        builder.show()
    }

    // Sensor handling (for AR mode)
    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val currentTime = System.currentTimeMillis()
                    // Early exit if too soon
                    if (currentTime - lastUpdateTime < updateIntervalMs) return

                    val oldAzimuth = filteredAzimuth
                    updateDeviceOrientation(it.values)

                    // Only update UI if significant change
                    val azimuthChange = abs(filteredAzimuth - oldAzimuth)
                    if (azimuthChange < 0.5f && currentTime - lastUpdateTime < 500L) {
                        return // Skip minor changes
                    }

                    lastUpdateTime = currentTime

                    updateDeviceOrientation(it.values)

                    // Only update AR navigation if in compass mode
                    if (!isGpsViewMode && arNavigationController.isCurrentlyNavigating() && isPhoneAcceptablyVertical()) {
                        val azimuthChange = kotlin.math.abs(filteredAzimuth - lastNavigationAzimuth)

                        if ((currentTime - lastNavigationUpdateTime > navigationUpdateIntervalMs) &&
                            (azimuthChange > minimumAzimuthChange)) {

                            locationManager.getCurrentLocation { location ->
                                arNavigationController.updateNavigationLine(location, filteredAzimuth)
                            }

                            lastNavigationUpdateTime = currentTime
                            lastNavigationAzimuth = filteredAzimuth
                        }
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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
        currentPitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
        currentRoll = Math.toDegrees(orientation[2].toDouble()).toFloat()

        currentAzimuth = if (rawAzimuth < 0) rawAzimuth + 360f else rawAzimuth
        filteredAzimuth = azimuthFilter.filter(currentAzimuth)
    }

    private fun isPhoneAcceptablyVertical(): Boolean {
        return kotlin.math.abs(currentPitch) < 45f
    }

    private fun showVerticalOrientationWarning() {
        navigationLineManager.hideNavigationLine()
        binding.instructionText.text = "Hold phone more vertically for accurate compass"
        binding.instructionText.visibility = View.VISIBLE
        binding.instructionText.bringToFront()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera initialization failed", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startLocationUpdates() {
        locationManager.startLocationUpdates()
    }

    private fun allPermissionsGranted() = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        // Triple long-press launches GPSTest tester
        binding.navigationInfo.setOnLongClickListener {
            handleDebugAccess()
            true
        }
    }

    // GPSTest helpers: BEGIN
    private var debugTapCount = 0
    private var lastDebugTap = 0L

    private fun handleDebugAccess() {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastDebugTap > 3000) {
            debugTapCount = 1
        } else {
            debugTapCount++
        }
        lastDebugTap = currentTime

        if (debugTapCount >= 3) {
            GPSTesterActivity.launch(this)
            debugTapCount = 0
        } else {
            Toast.makeText(this, "Debug: ${debugTapCount}/3", Toast.LENGTH_SHORT).show()
        }
    }
    // GPSTest helpers: END

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        locationManager.stopLocationUpdates()
        sensorManager.unregisterListener(this)
        navigationLineManager.removeNavigationView()
    }
}