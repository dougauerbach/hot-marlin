package com.vectornav.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
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

class MainActivity : AppCompatActivity(),
    SensorEventListener,
    NavigationController.NavigationUpdateListener,
    GPSTrackingController.GPSTrackingUpdateListener {

    lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var locationManager: LocationManager
    private lateinit var navigationCalculator: NavigationCalculator
    private lateinit var navigationLineManager: NavigationLineManager

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
            if (isGpsViewMode && gpsTrackingController.isCurrentlyTracking()) {
                gpsTrackingController.updatePosition(location, filteredAzimuth)
            } else if (!isGpsViewMode && arNavigationController.isCurrentlyNavigating()) {
                arNavigationController.updateNavigation(location, filteredAzimuth)
            }
        }

        // Initialize view mode
        switchToCompassView()
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

        // Update GPS tracking view
        binding.gpsTrackingView.updateTracking(
            trackingInfo.startLatitude,
            trackingInfo.startLongitude,
            currentLat,
            currentLon,
            trackingInfo.initialBearing,
            trackingInfo.targetDistance,
            crossTrackError,
            distanceFromStart,
            isOnTrack
        )

        // Update distance display
        binding.distanceText.text = "Remaining: ${distanceRemaining.toInt()}m"

        // Update instruction text
        binding.instructionText.text = when {
            distanceRemaining < 10 -> "You're close! Look around for your target."
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
                    if (currentTime - lastUpdateTime < updateIntervalMs) return
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