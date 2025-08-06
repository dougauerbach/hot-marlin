package com.vectornav.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.*

private const val offTrackPaintColor = 0xFFE2725B.toInt() // Coral/salmon off-track indicator

private const val bearingLinePaintColor = 0xFFE1E6E1 // Light gray-green bearing line

private const val userIconSize = 300f

/**
 * Custom view that displays GPS tracking in a top-down perspective.
 * Shows user position relative to bearing line without camera or compass dependency.
 */
class GPSTrackingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr),
    GPSViewGestureHandler.GestureCallback,
    UserIconAnimator.AnimationCallback {
    // Create our own NavigationCalculator instance
    private val navigationCalculator = NavigationCalculator()

    // GPS tracking data
    private var startGpsLat = 0.0
    private var startGpsLon = 0.0
    private var currentGpsBearing = 0f
    private var currentTargetDistance = 50

    // Drawing state
    private var isTrackingActive = false
    private var bearingLine = Path()
    private var userPosition = PointF(0f, 0f)
    private var startPosition = PointF(0f, 0f)
    private var crossTrackError = 0f
    private var distanceFromStart = 0f
    private var distanceRemaining = 0f
    private var isOnTrack = true
    private var lastDrawnScale = 0f
    // Screen layout constants - centralized for DRY principle
    private val dpToPx: Float by lazy { resources.displayMetrics.density }
    private val reservedTopDp = 80f
    private val reservedBottomDp = 140f
    private val buttonAreaBottomDp = 120f // 60dp margin + ~48dp button height + 12dp padding

    // Gesture handling
    private lateinit var gestureHandler: GPSViewGestureHandler

    // User icon animation
    private lateinit var userIconAnimator: UserIconAnimator
    private var animatedUserPosition = PointF(0f, 0f) // Current animated position
    private var calculatedUserPosition = PointF(0f, 0f) // GPS-calculated position
    private var lastGpsDistanceMeters = 0f
    init {
        userIconAnimator = UserIconAnimator(this)
        gestureHandler = GPSViewGestureHandler(context, this)
    }

    // Calculated layout boundaries (updated when view dimensions change)
    private var safeTopBoundary: Float = 0f
    private var safeBottomBoundary: Float = 0f
    private var availableHeight: Float = 0f
    private var availableWidth: Float = 0f

    // Method to update layout calculations when view size changes
    private fun updateLayoutBoundaries() {
        if (height <= 0 || width <= 0) return

        val reservedTop = reservedTopDp * dpToPx
        val reservedBottom = reservedBottomDp * dpToPx
        val buttonAreaBottom = buttonAreaBottomDp * dpToPx

        safeTopBoundary = kotlin.math.max(reservedTop, buttonAreaBottom)
        safeBottomBoundary = height - reservedBottom
        availableHeight = height - reservedTop - reservedBottom
        availableWidth = width.toFloat()

        Log.d("GPSTrackingView", "Layout boundaries updated: safeTop=${safeTopBoundary}, safeBottom=${safeBottomBoundary}")
    }

    // Paint objects
    private val bearingLinePaint = Paint().apply {
        color = bearingLinePaintColor.toInt() // Black bearing line
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt() // White text
        textSize = 48f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    // Add these paint objects to the class properties
    private val bearingLineShadowPaint = Paint().apply {
        color = 0x80000000.toInt() // Semi-transparent black shadow
        strokeWidth = 10f // Slightly thicker than main line
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val arrowPaint = Paint().apply {
        color = bearingLinePaintColor.toInt() // Same color as bearing line
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val arrowShadowPaint = Paint().apply {
        color = 0x80000000.toInt() // Semi-transparent black shadow
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = 0x30FFFFFF.toInt() // Light white with transparency
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val gridLabelPaint = Paint().apply {
        color = 0x60FFFFFF.toInt() // Semi-transparent white
        textSize = 24f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    // UserIconAnimator.AnimationCallback implementation
    override fun onPositionUpdated(x: Float, y: Float) {
        animatedUserPosition.set(x, y)
    }

    override fun requestRedraw() {
        invalidate()
    }


    // View transformation state (replaces fixed viewScale)
    private var viewScale = 50.0f  // Now variable instead of fixed
    private var panOffsetX = 0f    // Horizontal pan offset
    private var panOffsetY = 0f    // Vertical pan offset
    // Store the current GPS coordinates so we can use them for scale recalculation
    private var lastKnownCurrentLat = 0.0
    private var lastKnownCurrentLon = 0.0

    // System UI insets for navigation bar, status bar, etc.
    private var systemInsets = Rect()

    // GPSViewGestureHandler.GestureCallback implementation
    override fun onPanChanged(offsetX: Float, offsetY: Float) {
        panOffsetX = offsetX
        panOffsetY = offsetY
        Log.d("GPSTrackingView", "Pan offset: (${panOffsetX}, ${panOffsetY})")

        // We need to recalculate because the The invalidate() call from the gesture handler did not trigger onDraw() with updated positions
        if (isTrackingActive) {
            // Use the last known GPS coordinates to recalculate with new scale
            calculateViewCoordinates(
                startGpsLat, startGpsLon,
                lastKnownCurrentLat, lastKnownCurrentLon, // Store these when GPS updates
                currentGpsBearing
            )
        }
    }

    override fun onScaleChanged(newScale: Float) {
        val oldScale = viewScale
        viewScale = newScale
        Log.d("GPSTrackingView", "Scale changed: $oldScale -> $viewScale pixels/meter")

        // We need to recalculate because the GPS distances need to be re-scaled
        if (isTrackingActive) {
            // Use the last known GPS coordinates to recalculate with new scale
            calculateViewCoordinates(
                startGpsLat, startGpsLon,
                lastKnownCurrentLat, lastKnownCurrentLon, // Store these when GPS updates
                currentGpsBearing
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateLayoutBoundaries()
    }

    override fun onViewReset() {
        Log.d("GPSTrackingView", "View reset to default position and scale")
    }

    override fun onViewInvalidate() {
        invalidate() // Trigger redraw
    }

    // Override onTouchEvent to handle gestures
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Let gesture handler process the event first
        val gestureHandled = gestureHandler.onTouchEvent(event)

        // If gesture was handled, don't pass to parent
        if (gestureHandled) {
            return true
        }

        // Otherwise, let parent handle (for screen tap navigation)
        return super.onTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // Set up window insets listener to detect system UI
        setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // Android 11+ (API 30+)
                val systemBars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                systemInsets.set(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            } else {
                // Android 10 and below
                @Suppress("DEPRECATION")
                systemInsets.set(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }

            Log.d("GPSTrackingView", "System insets detected: top=${systemInsets.top}, bottom=${systemInsets.bottom}, left=${systemInsets.left}, right=${systemInsets.right}")

            // Trigger recalculation if tracking is active
            if (isTrackingActive) {
                invalidate()
            }

            insets
        }
    }
    fun updateTracking(
        startLat: Double,
        startLon: Double,
        currentLat: Double,
        currentLon: Double,
        bearing: Float,
        targetDistance: Int,
        crossTrackErrorMeters: Float,
        distanceFromStartMeters: Float,
        onTrack: Boolean
    ) {
        // Store GPS data for gesture recalculations
        startGpsLat = startLat
        startGpsLon = startLon
        lastKnownCurrentLat = currentLat  // Store current GPS position
        lastKnownCurrentLon = currentLon  // Store current GPS position
        currentGpsBearing = bearing
        currentTargetDistance = targetDistance

        isTrackingActive = true
        crossTrackError = crossTrackErrorMeters
        distanceFromStart = distanceFromStartMeters
        distanceRemaining = max(0f, targetDistance - distanceFromStartMeters)
        isOnTrack = onTrack

        // Convert GPS coordinates to view coordinates
        calculateViewCoordinates(startLat, startLon, currentLat, currentLon, bearing)

        // Animate user icon to new calculated position
        userIconAnimator.updatePosition(
            calculatedUserPosition.x,
            calculatedUserPosition.y,
            lastGpsDistanceMeters
        )

        // Check if we need to recalculate grid due to scale change
        if (viewScale != lastDrawnScale) {
            lastDrawnScale = viewScale
            Log.d("GPSTrackingView", "Scale changed - grid will be redrawn")
        }

        // Check bounds using the target position (where animation is heading)
        checkAndAdjustUserIconBounds()

        Log.d("GPSTrackingView", "View updated: calculated(${calculatedUserPosition.x}, ${calculatedUserPosition.y}), animated(${animatedUserPosition.x}, ${animatedUserPosition.y}), crossTrack=${crossTrackError}m, onTrack=$isOnTrack")

        invalidate()
    }
    fun clearTracking() {
        isTrackingActive = false
        invalidate()
    }
    private fun calculateViewCoordinates(
        startLat: Double,
        startLon: Double,
        currentLat: Double,
        currentLon: Double,
        bearing: Float
    ) {
        // Use NavigationCalculator for consistency and accuracy
        val distanceFromStart = navigationCalculator.calculateDistance(
            startLat, startLon, currentLat, currentLon
        )

        val currentBearing = navigationCalculator.calculateBearing(
            startLat, startLon, currentLat, currentLon
        )

        // Calculate along-track (progress toward target) and cross-track (left/right offset)
        val bearingRad = Math.toRadians(bearing.toDouble())
        val currentBearingRad = Math.toRadians(currentBearing.toDouble())

        val alongTrackDistance = distanceFromStart * cos(currentBearingRad - bearingRad)
        val crossTrackDistance = distanceFromStart * sin(currentBearingRad - bearingRad)

        Log.d("GPSTrackingView", "NavigationCalculator: distance=${distanceFromStart}m, bearing=${currentBearing}° Calculated: alongTrack=${alongTrackDistance}m, crossTrack=${crossTrackDistance}m variable scale: $viewScale pixels/meter")

        // Use class-level layout constants
        val baseStartX = width / 2f
        val baseStartY = safeTopBoundary + availableHeight - 80 * dpToPx

        // Apply pan offset to start position
        startPosition.set(
            baseStartX + panOffsetX,
            baseStartY + panOffsetY
        )

        // Calculate user position (but don't set userPosition directly)
        val baseUserX = baseStartX + (crossTrackDistance * viewScale).toFloat()
        val baseUserY = baseStartY - (alongTrackDistance * viewScale).toFloat()

        calculatedUserPosition.set(
            baseUserX + panOffsetX,
            baseUserY + panOffsetY
        )

        // Store GPS distance for animation calculation
        lastGpsDistanceMeters = distanceFromStart

        Log.d("CalculateViewCoordinates", "Calculated position: (${calculatedUserPosition.x}, ${calculatedUserPosition.y}), GPS distance: ${lastGpsDistanceMeters}m")
        Log.d("CalculateViewCoordinates", "Positions with scale=${viewScale}, pan=(${panOffsetX}, ${panOffsetY}): Start: (${startPosition.x}, ${startPosition.y}) User: (${userPosition.x}, ${userPosition.y}) User off-track by: ${crossTrackDistance}m, along-track: ${alongTrackDistance}m")

/*
        // Screen layout constants
        val dpToPx = resources.displayMetrics.density
        val reservedTop = 80 * dpToPx
        val reservedBottom = 140 * dpToPx
        val availableHeight = height - reservedTop - reservedBottom

        // BEARING LINE: Calculate safe top boundary (below buttons)
        val buttonAreaBottom = 120 * dpToPx // 60dp margin + ~48dp button height + 12dp padding
        val safeTopBoundary = max(reservedTop, buttonAreaBottom)

        // CALCULATE POSITIONS WITHOUT PAN OFFSET FIRST
        // Base start position (center of available area)
        val baseStartX = width / 2f
        val baseStartY = reservedTop + availableHeight - 80 * dpToPx

        // APPLY PAN OFFSET TO START POSITION
        startPosition.set(
            baseStartX + panOffsetX,
            baseStartY + panOffsetY
        )

        // BEARING LINE: Calculate target from the MOVED start position, then truncate
        // The bearing line should always try to be 1000m long, but truncated to screen bounds
        val fullBearingLineLength = 1000f * viewScale  // Full 1000m line in pixels
        val idealTargetX = startPosition.x  // Same X as start (vertical line)
        val idealTargetY = startPosition.y - fullBearingLineLength  // 1000m up from start

        // truncate to screen bounds (don't let it go above safe boundary)
        val clampedTargetY = kotlin.math.max(idealTargetY, safeTopBoundary)
        val actualBearingLineLength = startPosition.y - clampedTargetY

        // Base user position (calculated from GPS, then apply pan)
        val baseUserX = baseStartX + (crossTrackDistance * viewScale).toFloat()
        val baseUserY = baseStartY - (alongTrackDistance * viewScale).toFloat()

        userPosition.set(
            baseUserX + panOffsetX,
            baseUserY + panOffsetY
        )

        Log.d("CalculateViewCoordinates", "Positions with scale=${viewScale}, pan=(${panOffsetX}, ${panOffsetY}): Start: (${startPosition.x}, ${startPosition.y}) User: (${userPosition.x}, ${userPosition.y}) User off-track by: ${crossTrackDistance}m, along-track: ${alongTrackDistance}m")
*/
    }

    private fun checkAndAdjustUserIconBounds() {
        if (!isTrackingActive) return

        val iconHalfSize = userIconSize / 2
        val targetIconTop = calculatedUserPosition.y - iconHalfSize // Use calculated position for bounds check

        if (targetIconTop < safeTopBoundary) {
            val panAdjustment = safeTopBoundary - targetIconTop + 20f

            Log.d("GPSTrackingView", "User icon will be above safe boundary - auto-panning down by ${panAdjustment}px")

            val (currentPanX, currentPanY) = gestureHandler.getPanOffset()
            gestureHandler.setPanOffset(currentPanX, currentPanY + panAdjustment)

            // Recalculate with new pan offset
            if (isTrackingActive) {
                calculateViewCoordinates(
                    startGpsLat, startGpsLon,
                    lastKnownCurrentLat, lastKnownCurrentLon,
                    currentGpsBearing
                )

                // Update animation target to new calculated position
                userIconAnimator.updatePosition(
                    calculatedUserPosition.x,
                    calculatedUserPosition.y,
                    0f // Pan adjustment, not GPS movement - use 0 for distance calculation
                )
            }

            invalidate()
        }
    }

    private fun drawGrid(canvas: Canvas) {
        if (!isTrackingActive) return

        // Calculate appropriate grid spacing based on scale
        val gridSpacingMeters = getOptimalGridSpacing(viewScale)
        val gridSpacingPixels = gridSpacingMeters * viewScale

        Log.d("GPSGrid", "Drawing grid: ${gridSpacingMeters}m spacing, ${gridSpacingPixels}px, scale=${viewScale}")

        // Use class-level layout constants
        val drawableTop = safeTopBoundary
        val drawableBottom = safeBottomBoundary

        // Calculate grid origin
        val originX = startPosition.x
        val originY = startPosition.y

        // DRAW VERTICAL LINES - within safe boundaries
        val firstVisibleX = ((0 - originX) / gridSpacingPixels).toInt() * gridSpacingPixels + originX
        var x = firstVisibleX

        while (x <= availableWidth) {
            if (x >= 0) {
                canvas.drawLine(x, drawableTop, x, drawableBottom, gridPaint)

                // Add distance label at bottom of drawable area
                val distanceFromOrigin = (x - originX) / gridSpacingPixels
                if (abs(distanceFromOrigin) > 0.1f) {
                    val distanceLabel = "${(distanceFromOrigin * gridSpacingMeters).toInt()}m"
                    canvas.drawText(distanceLabel, x, drawableBottom - 10f, gridLabelPaint)
                }
            }
            x += gridSpacingPixels
        }

        // DRAW HORIZONTAL LINES - within safe boundaries
        val firstVisibleY = ((drawableTop - originY) / gridSpacingPixels).toInt() * gridSpacingPixels + originY
        var y = firstVisibleY

        while (y <= drawableBottom) {
            if (y >= drawableTop) {
                canvas.drawLine(0f, y, availableWidth, y, gridPaint)

                // Add distance label at left edge
                val distanceFromOrigin = -(y - originY) / gridSpacingPixels
                if (abs(distanceFromOrigin) > 0.1f) {
                    val distanceLabel = "${(distanceFromOrigin * gridSpacingMeters).toInt()}m"
                    canvas.drawText(distanceLabel, 30f, y - 5f, gridLabelPaint.apply {
                        textAlign = Paint.Align.LEFT
                    })
                    gridLabelPaint.textAlign = Paint.Align.CENTER // Reset
                }
            }
            y += gridSpacingPixels
        }

        // Draw origin marker - only if visible in drawable area
        if (originX >= 0 && originX <= availableWidth && originY >= drawableTop && originY <= drawableBottom) {
            val originPaint = Paint().apply {
                color = 0x60FFFFFF.toInt()
                strokeWidth = 2f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }

            canvas.drawLine(originX - 20f, originY, originX + 20f, originY, originPaint)
            canvas.drawLine(originX, originY - 20f, originX, originY + 20f, originPaint)
        }
    }

    // Helper method to determine optimal grid spacing based on scale
    private fun getOptimalGridSpacing(scale: Float): Float {
        // Choose grid spacing that results in reasonable visual spacing
        val targetPixelSpacing = 80f // Target ~80 pixels between grid lines
        val metersPerTarget = targetPixelSpacing / scale

        // Round to nice round numbers
        return when {
            metersPerTarget <= 1f -> 1f      // 1m grid
            metersPerTarget <= 2f -> 2f      // 2m grid
            metersPerTarget <= 5f -> 5f      // 5m grid
            metersPerTarget <= 10f -> 10f    // 10m grid
            metersPerTarget <= 20f -> 20f    // 20m grid
            metersPerTarget <= 50f -> 50f    // 50m grid
            metersPerTarget <= 100f -> 100f  // 100m grid
            else -> 200f                     // 200m grid
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isTrackingActive) {
            // Show instruction text when not tracking
            canvas.drawText(
                "Switch to GPS Tracking mode to see position",
                width / 2f,
                height / 2f,
                textPaint
            )
            return
        }

        // Draw bearing line with drop shadow and arrowhead
        drawBearingLine(canvas)

        // Draw grid FIRST (below everything else)
        drawGrid(canvas)

        // Draw bearing line with drop shadow and arrowhead
        drawBearingLine(canvas)

        // Draw start position marker (smaller, different color)
        drawIcon(canvas, startPosition.x, startPosition.y, R.drawable.crosshair_center, 60f)

        // Draw user position icon using ANIMATED position
        drawIcon(canvas, animatedUserPosition.x, animatedUserPosition.y, R.drawable.user_dot_icon, userIconSize)

        // Draw gesture state info (top-left corner)
        drawGestureInfo(canvas)
    }

    private fun drawGestureInfo(canvas: Canvas) {
        val scale = gestureHandler.getCurrentScale()
        val (panX, panY) = gestureHandler.getPanOffset()

        // Create info paint for small text
        val infoPaint = Paint().apply {
            color = 0xCCFFFFFF.toInt() // Semi-transparent white
            textSize = 28f
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }

        // Background for text
        val backgroundPaint = Paint().apply {
            color = 0x80000000.toInt() // Semi-transparent black
            style = Paint.Style.FILL
        }

        val infoTexts = listOf(
            "Scale: ${scale.toInt()}px/m",
            "Pan: (${panX.toInt()}, ${panY.toInt()})",
            "Double-tap: Reset view",
            "Long-press + drag: Pan"
        )

        val lineHeight = 35f
        val padding = 20f
        val startX = 140f
        val startY = 600f // Below the toggle button

        // Draw background
        val textWidth = 300f
        val textHeight = infoTexts.size * lineHeight + padding * 2
        canvas.drawRect(
            startX - padding,
            startY - padding * 2,
            startX + textWidth,
            startY + textHeight - padding,
            backgroundPaint
        )

        // Draw text lines
        infoTexts.forEachIndexed { index, text ->
            canvas.drawText(
                text,
                startX,
                startY + (index * lineHeight),
                infoPaint
            )
        }
    }

    private fun drawBearingLine(canvas: Canvas) {
        // Calculate bearing line end point with BOTH top and bottom truncation
        val fullBearingLineLength = 1000f * viewScale  // Full 1000m line in pixels
        val idealEndY = startPosition.y - fullBearingLineLength  // 1000m up from start

        // Truncate to BOTH safe boundaries
        val clampedEndY = idealEndY.coerceIn(safeTopBoundary, safeBottomBoundary)
        val clampedStartY = startPosition.y.coerceIn(safeTopBoundary, safeBottomBoundary)

        val arrowSize = 40f
        val lineEndX = startPosition.x
        val lineEndY = clampedEndY + arrowSize
        val lineStartY = clampedStartY

        Log.d("GPSBearingLine", "Line bounds: start(${startPosition.x}, ${lineStartY}) end(${lineEndX}, ${lineEndY}) - safe bounds: ${safeTopBoundary} to ${safeBottomBoundary}")

        // Only draw if we have a meaningful line within bounds
        if (lineStartY <= lineEndY + 10f) { // 10px minimum line length
            return
        }

        // Draw shadow line first (within bounds)
        val shadowOffset = 4f
        val shadowPath = Path()
        shadowPath.moveTo(startPosition.x + shadowOffset, lineStartY + shadowOffset)
        shadowPath.lineTo(lineEndX + shadowOffset, lineEndY + shadowOffset)
        canvas.drawPath(shadowPath, bearingLineShadowPaint)

        // Draw main bearing line (within bounds)
        bearingLine.reset()
        bearingLine.moveTo(startPosition.x, lineStartY)
        bearingLine.lineTo(lineEndX, lineEndY)
        canvas.drawPath(bearingLine, bearingLinePaint)

        // Draw arrowhead at the clamped end position
        val arrowTipY = lineEndY - arrowSize
        Log.d("GPSBearingLine", "lineEndY: $lineEndY, arrowTipY: $arrowTipY, safeTopBoundary: $safeTopBoundary")

        val arrowPath = Path()
        arrowPath.moveTo(lineEndX, arrowTipY) // Arrow tip
        arrowPath.lineTo(lineEndX - arrowSize/2, arrowTipY + arrowSize) // Left wing
        arrowPath.lineTo(lineEndX + arrowSize/2, arrowTipY + arrowSize) // Right wing
        arrowPath.close()

        // Draw arrowhead shadow first (offset)
        val arrowShadowPath = Path()
        arrowShadowPath.moveTo(lineEndX + shadowOffset, arrowTipY + shadowOffset)
        arrowShadowPath.lineTo(lineEndX - arrowSize/2 + shadowOffset, arrowTipY + arrowSize + shadowOffset)
        arrowShadowPath.lineTo(lineEndX + arrowSize/2 + shadowOffset, arrowTipY + arrowSize + shadowOffset)
        arrowShadowPath.close()
        canvas.drawPath(arrowShadowPath, arrowShadowPaint)

        // Draw main arrowhead
        canvas.drawPath(arrowPath, arrowPaint)
    }


    private fun drawIcon(canvas: Canvas, x: Float, y: Float, drawableResId: Int, size: Float) {
        val drawable = ContextCompat.getDrawable(context, drawableResId)
        drawable?.let {
            val halfSize = (size / 2).toInt()
            it.setBounds(
                (x - halfSize).toInt(),
                (y - halfSize).toInt(),
                (x + halfSize).toInt(),
                (y + halfSize).toInt()
            )
            it.draw(canvas)
        }
    }

    fun cleanup() {
        userIconAnimator.cleanup()
    }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        userIconAnimator.cleanup()
    }
}