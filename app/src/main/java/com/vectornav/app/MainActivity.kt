package com.vectornav.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.vectornav.app.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

// Google Play Services Location imports

data class BreadcrumbPoint(
    val lat: Double,
    val lon: Double,
    val timestamp: Long,
    val distanceFromStart: Float = 0f,
    val accuracy: Float = 0f
)

class MainActivity : AppCompatActivity(),
    SensorEventListener,
    CompassNavigationController.NavigationUpdateListener,
    GPSTrackingController.GPSTrackingUpdateListener {

    lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var locationManager: LocationManager
    private lateinit var navigationCalculator: NavigationCalculator
    private lateinit var navigationLineManager: NavigationLineManager

    private var lastKnownLocation: Location? = null

    // Dual navigation controllers
    private lateinit var compassNavigationController: CompassNavigationController
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

    private fun getAdaptiveUpdateInterval(isMoving: Boolean, speed: Float): Long {
        return when {
            !isMoving -> 2000L // Slow updates when stationary
            speed < 1f -> 1000L // Walking speed
            speed < 5f -> 500L  // Fast walking/jogging
            else -> 250L        // High speed updates
        }
    }

    private fun adjustInstructionTextForSystemUI() {
        // Get the system insets
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val navigationBarHeight = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }

            // Adjust instruction text position to be above navigation bar
            val layoutParams = binding.instructionText.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            layoutParams.bottomMargin = navigationBarHeight + 50  // 50dp above navigation bar
            binding.instructionText.layoutParams = layoutParams

            Log.d("VectorNav", "Adjusted instruction text bottom margin to: ${layoutParams.bottomMargin}px (nav bar: ${navigationBarHeight}px)")

            insets
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        locationManager = LocationManager(this)
        navigationCalculator = NavigationCalculator()
        navigationLineManager = NavigationLineManager(this)

        // Initialize both navigation controllers
        compassNavigationController = CompassNavigationController(navigationCalculator, navigationLineManager)
        gpsTrackingController = GPSTrackingController(navigationCalculator, context = this)

        // Set update listeners
        compassNavigationController.setUpdateListener(this)
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

        // Set up view toggle button
        binding.toggleViewButton.setOnClickListener {
            toggleView()
        }

        // Set up "Found It!" button
        binding.foundItButton.setOnClickListener {
            markTargetFound()
        }

        // Location updates callback
        // Location updates callback (in onCreate)
        locationManager.setLocationCallback { location ->
            Log.d("VectorNav", "📱 MainActivity received location: ${location.latitude}, ${location.longitude}")

            lastKnownLocation = location  // Store the location

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
                    Log.d("VectorNav", "🎯 Updating GPS tracking controller...")
                    gpsTrackingController.updatePosition(location, filteredAzimuth)
                } else if (!isGpsViewMode && compassNavigationController.isCurrentlyNavigating()) {
                    compassNavigationController.updateNavigation(location, filteredAzimuth)
                }
            }
        }

        // Initialize view mode
        switchToCompassView()

        // Adjust UI for system bars
        adjustInstructionTextForSystemUI()

        // Initially hide the GPS view toggle button
        binding.toggleViewButton.visibility = View.GONE

        /*
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
        */
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
        // Check if phone is vertical enough for accurate compass reading (Compass view only)
        if (!isGpsViewMode && !isPhoneAcceptablyVertical()) {
            Toast.makeText(this, "Hold phone more vertically for accurate compass", Toast.LENGTH_SHORT).show()
            return
        }

        // Request high accuracy when starting navigation
        locationManager.updateLocationRequestPriority(highAccuracy = true)

        locationManager.getCurrentLocation { location ->
            if (isGpsViewMode) {
                // Start GPS tracking with learned default distance
                gpsTrackingController.startTracking(location, filteredAzimuth)

                // Force immediate update
                Handler(Looper.getMainLooper()).postDelayed({
                    gpsTrackingController.updatePosition(location, filteredAzimuth)
                }, 100)

            } else {
                // Start AR navigation
                compassNavigationController.setNavigationTarget(location, filteredAzimuth)
                compassNavigationController.setTargetDistance(1000)
            }
        }
    }

    private fun stopAllNavigation() {
        compassNavigationController.stopNavigation()
        gpsTrackingController.stopTracking()
        // Reduce to normal accuracy when navigation stops (save battery)
        locationManager.updateLocationRequestPriority(highAccuracy = false)
    }

    private fun isAnyNavigationActive(): Boolean {
        return compassNavigationController.isCurrentlyNavigating() || gpsTrackingController.isCurrentlyTracking()
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
        binding.toggleViewButton.text = "View top-down"

        // Transfer navigation state if needed
        if (gpsTrackingController.isCurrentlyTracking()) {
            val trackingInfo = gpsTrackingController.getTrackingInfo()
            gpsTrackingController.stopTracking()

            locationManager.getCurrentLocation { location ->
                compassNavigationController.setNavigationTarget(location, trackingInfo.initialBearing)
                compassNavigationController.setTargetDistance(trackingInfo.targetDistance)
            }
        }

        android.util.Log.d("VectorNav", "Switched to Compass (AR) view")
    }

    // In MainActivity.kt - update the switchToGpsView method:

    private fun switchToGpsView() {
        isGpsViewMode = true

        // Show GPS elements
        binding.cameraPreview.visibility = View.GONE
        binding.gpsTrackingView.visibility = View.VISIBLE
        binding.foundItButton.visibility = View.VISIBLE

        // Update toggle button
        binding.toggleViewButton.text = "Switch to Compass View"

        // Transfer navigation state if needed
        if (compassNavigationController.isCurrentlyNavigating()) {
            val navInfo = compassNavigationController.getNavigationInfo()
            compassNavigationController.stopNavigation()

            // PRESERVE ORIGINAL START LOCATION - don't use current location
            val originalStartLocation = Location("preserved").apply {
                latitude = navInfo.startLatitude    // Use AR navigation's start location
                longitude = navInfo.startLongitude  // Use AR navigation's start location
                accuracy = 5.0f
                time = System.currentTimeMillis()
            }

            // Start GPS tracking from the ORIGINAL start location, not current location
            gpsTrackingController.startTracking(originalStartLocation, navInfo.targetBearing)

            // FORCE IMMEDIATE UPDATE with current location to show proper position
            locationManager.getCurrentLocation { currentLocation ->
                Handler(Looper.getMainLooper()).postDelayed({
                    gpsTrackingController.updatePosition(currentLocation, filteredAzimuth)
                }, 100)
            }
        } else {
            // If no existing navigation, still force a location update for immediate display
            locationManager.getCurrentLocation { location ->
                if (gpsTrackingController.isCurrentlyTracking()) {
                    gpsTrackingController.updatePosition(location, filteredAzimuth)
                }
            }
        }

        Log.d("VectorNav", "Switched to GPS tracking view with preserved start location")
    }

    private fun markTargetFound() {
        if (gpsTrackingController.isCurrentlyTracking()) {
            locationManager.getCurrentLocation { location ->
                gpsTrackingController.markTargetFound(location)
            }
        }
    }

    // AR Navigation callbacks
    override fun onNavigationStarted(targetBearing: Float, targetDistance: Int) {
        showNavigationUI()

        // SHOW GPS VIEW BUTTON when navigation starts
        binding.toggleViewButton.visibility = View.VISIBLE

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

        binding.instructionText.text = guidanceText
        binding.instructionText.visibility = View.VISIBLE
        binding.instructionText.bringToFront()
    }

    // GPS Tracking callbacks
    override fun onTrackingStarted(bearing: Float, distance: Int) {
        showNavigationUI()
        binding.instructionText.text = "GPS tracking active - stay on the bearing line!"
        binding.instructionText.visibility = View.VISIBLE

        // SHOW GPS VIEW BUTTON when GPS tracking starts
        binding.toggleViewButton.visibility = View.VISIBLE

        Toast.makeText(this, "GPS Tracking started! Bearing: ${bearing.toInt()}°", Toast.LENGTH_SHORT).show()
    }

    override fun onTrackingStopped() {
        hideNavigationUI()
        binding.gpsTrackingView.clearTracking()

        // HIDE GPS VIEW BUTTON when GPS tracking stops
        binding.toggleViewButton.visibility = View.GONE

        // If we were in GPS view, switch back to compass view
        if (isGpsViewMode) {
            switchToCompassView()
        }
    }

    // GPS Tracking callback
    override fun onPositionUpdate(
        currentLat: Double,
        currentLon: Double,
        distanceFromStart: Float,
        crossTrackError: Float,
        isOnTrack: Boolean,
        confidence: Float
    ) {
        val trackingInfo = gpsTrackingController.getTrackingInfo()

        // Use the actual GPS coordinates (not sensor fusion coordinates)
        val displayLat = lastKnownLocation?.latitude ?: currentLat
        val displayLon = lastKnownLocation?.longitude ?: currentLon

        binding.gpsTrackingView.updateTracking(
            trackingInfo.startLatitude,
            trackingInfo.startLongitude,
            displayLat,
            displayLon,
            trackingInfo.initialBearing,
            trackingInfo.targetDistance,
            crossTrackError,
            distanceFromStart,
            isOnTrack
        )

        //  Progress indicator focuses on bearing line adherence
        binding.instructionText.text = generateBearingLineGuidanceText(
            distanceFromStart, crossTrackError, isOnTrack
        )
        binding.instructionText.visibility = View.VISIBLE
        Log.d("VectorNav", "PositionUpdated: Instruction: ${binding.instructionText.text}")
    }
    private fun generateBearingLineGuidanceText(
        distanceFromStart: Float,
        crossTrackError: Float,
        isOnTrack: Boolean
    ): String {
        val crossTrackAbs = abs(crossTrackError)

        return when {
            // User is on the bearing line
            isOnTrack -> {
                "✅ On track - continue straight (${distanceFromStart.toInt()}m traveled)"
            }

            // User is off the bearing line
            crossTrackAbs < 5f -> {
                val direction = if (crossTrackError > 0) "left" else "right"
                "📍 Close! Move ${direction} ${crossTrackAbs.toInt()}m to "
            }

            crossTrackAbs < 15f -> {
                val direction = if (crossTrackError > 0) "left" else "right"
                "🧭 Move ${direction} ${crossTrackAbs.toInt()}m "
            }

            else -> {
                val direction = if (crossTrackError > 0) "left" else "right"
                "🔄 Far off bearing - head ${direction} ${crossTrackAbs.toInt()}m to "
            }
        }
    }

    // Update the onTargetFound callback to save the actual distance:
    override fun onTargetFound(estimatedDistance: Int, actualDistance: Float) {
        val error = actualDistance - estimatedDistance
        val errorText = if (error > 0) "${error.toInt()}m over" else "${(-error).toInt()}m under"

        Toast.makeText(
            this,
            "🎯 Found it!\nDistance: ${actualDistance.toInt()}m\n($errorText)",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onTrackingReset(distanceFromStart: Float, currentTargetDistance: Int) {
        Toast.makeText(this,
            "🔄 GPS tracking reset - was ${distanceFromStart.toInt()}m from start",
            Toast.LENGTH_SHORT).show()

    }

    private fun showNavigationUI() {
        binding.crosshairVertical.visibility = View.GONE
        binding.crosshairHorizontal.visibility = View.GONE
        binding.crosshairCenter.visibility = View.GONE
        binding.instructionText.visibility = View.GONE
    }

    private fun hideNavigationUI() {
        binding.crosshairVertical.visibility = View.VISIBLE
        binding.crosshairHorizontal.visibility = View.VISIBLE
        binding.crosshairCenter.visibility = View.VISIBLE
        binding.instructionText.visibility = View.VISIBLE
        binding.instructionText.text = "Tap screen to set navigation target"
        binding.toggleViewButton.visibility = View.GONE
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
                    if (!isGpsViewMode && compassNavigationController.isCurrentlyNavigating() && isPhoneAcceptablyVertical()) {
                        val azimuthChange = kotlin.math.abs(filteredAzimuth - lastNavigationAzimuth)

                        if ((currentTime - lastNavigationUpdateTime > navigationUpdateIntervalMs) &&
                            (azimuthChange > minimumAzimuthChange)) {

                            locationManager.getCurrentLocation { location ->
                                compassNavigationController.updateNavigationLine(location, filteredAzimuth)
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
        return abs(currentPitch) < 45f
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
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

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