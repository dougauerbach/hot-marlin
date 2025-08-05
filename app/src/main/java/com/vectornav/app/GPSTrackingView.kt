package com.vectornav.app

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

private const val userIconSize = 200f

/**
 * Custom view that displays GPS tracking in a top-down perspective.
 * Shows user position relative to bearing line without camera or compass dependency.
 */
class GPSTrackingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), GPSViewGestureHandler.GestureCallback {
    // Create our own NavigationCalculator instance
    private val navigationCalculator = NavigationCalculator()

    // GPS tracking data
    private var startGpsLat = 0.0
    private var startGpsLon = 0.0
    private var currentGpsBearing = 0f
    private var currentTargetDistance = 50
    private var currentBearing = 0f

    // Drawing state
    private var isTrackingActive = false
    private var bearingLine = Path()
    private var userPosition = PointF(0f, 0f)
    private var startPosition = PointF(0f, 0f)
    private var targetPosition = PointF(0f, 0f)
    private var crossTrackError = 0f
    private var distanceFromStart = 0f
    private var distanceRemaining = 0f
    private var isOnTrack = true
    private var breadcrumbs = listOf<BreadcrumbPoint>()

    // Paint objects
    private val bearingLinePaint = Paint().apply {
        color = bearingLinePaintColor.toInt() // Black bearing line
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val offTrackPaint = Paint().apply {
        color = offTrackPaintColor.toInt() // Purple (opposite of material green) off-track indicator
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val textPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt() // White text
        textSize = 48f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val distancePaint = Paint().apply {
        color = 0xFFFFFFFF.toInt() // White text
        textSize = 40f
        isAntiAlias = true
        textAlign = Paint.Align.LEFT  // Left aligned
        typeface = Typeface.DEFAULT_BOLD
    }

    private val breadcrumbPaint = Paint().apply {
        color = 0xFFFFD700.toInt() // Bright gold/yellow - highly visible against green
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val borderPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt() // White border
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val breadcrumbTrailPaint = Paint().apply {
        color = 0xFFFF6B35.toInt() // Bright orange trail - very visible
        strokeWidth = 4f // Slightly thicker
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f) // Longer dashes
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

    // Gesture handling
    private lateinit var gestureHandler: GPSViewGestureHandler

    // View transformation state (replaces fixed viewScale)
    private var viewScale = 50.0f  // Now variable instead of fixed
    private var panOffsetX = 0f    // Horizontal pan offset
    private var panOffsetY = 0f    // Vertical pan offset
    // Store the current GPS coordinates so we can use them for scale recalculation
    private var lastKnownCurrentLat = 0.0
    private var lastKnownCurrentLon = 0.0

    // System UI insets for navigation bar, status bar, etc.
    private var systemInsets = Rect()

    init {
        // Initialize gesture handler
        gestureHandler = GPSViewGestureHandler(context, this)
    }

    // GPSViewGestureHandler.GestureCallback implementation
    override fun onPanChanged(offsetX: Float, offsetY: Float) {
        panOffsetX = offsetX
        panOffsetY = offsetY
        Log.d("GPSTrackingView", "Pan offset: (${panOffsetX}, ${panOffsetY})")

        // DON'T recalculate from GPS - just apply the new pan offset to existing positions
        // The invalidate() call from the gesture handler will trigger onDraw() with updated positions
    }

    // Also update onScaleChanged to recalculate positions
    override fun onScaleChanged(newScale: Float) {
        val oldScale = viewScale
        viewScale = newScale
        Log.d("GPSTrackingView", "Scale changed: ${oldScale} -> ${viewScale} pixels/meter")

        // FOR SCALE CHANGES: We do need to recalculate because the GPS distances need to be re-scaled
        if (isTrackingActive) {
            // Use the last known GPS coordinates to recalculate with new scale
            calculateViewCoordinates(
                startGpsLat, startGpsLon,
                lastKnownCurrentLat, lastKnownCurrentLon, // Store these when GPS updates
                currentGpsBearing, currentTargetDistance
            )
        }
    }

    override fun onViewReset() {
        Log.d("GPSTrackingView", "View reset to default position and scale")
    }

    override fun onViewInvalidate() {
        invalidate() // Trigger redraw
    }

    // Override onTouchEvent to handle gestures
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
        calculateViewCoordinates(startLat, startLon, currentLat, currentLon, bearing, targetDistance)

        Log.d("GPSTrackingView", "View updated: user($userPosition), crossTrack=${crossTrackError}m, onTrack=$isOnTrack")

        invalidate()
    }
    /* TEMPORARILY DISABLED: BREADCRUMBS
    fun updateTrackingWithBreadcrumbs(
        startLat: Double,
        startLon: Double,
        currentLat: Double,
        currentLon: Double,
        bearing: Float,
        targetDistance: Int,
        crossTrackErrorMeters: Float,
        distanceFromStartMeters: Float,
        onTrack: Boolean,
        breadcrumbList: List<BreadcrumbPoint>
    ) {
        // Store breadcrumbs
        breadcrumbs = breadcrumbList

        // Call existing update method
        updateTracking(
            startLat, startLon, currentLat, currentLon,
            bearing, targetDistance, crossTrackErrorMeters,
            distanceFromStartMeters, onTrack
        )
    }
    */
    fun clearTracking() {
        isTrackingActive = false
        breadcrumbs = emptyList()
        invalidate()
    }
    private fun calculateViewCoordinates(
        startLat: Double,
        startLon: Double,
        currentLat: Double,
        currentLon: Double,
        bearing: Float,
        targetDistance: Int
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

        Log.d("GPSTrackingView", "NavigationCalculator: distance=${distanceFromStart}m, bearing=${currentBearing}°")
        Log.d("GPSTrackingView", "Calculated: alongTrack=${alongTrackDistance}m, crossTrack=${crossTrackDistance}m")

        // viewScale is now managed by gestureHandler - it's variable, not fixed
        Log.d("GPSTrackingView", "Using variable scale: ${viewScale} pixels/meter")

        // Screen layout constants
        val dpToPx = resources.displayMetrics.density
        val reservedTop = 80 * dpToPx
        val reservedBottom = 140 * dpToPx
        val availableHeight = height - reservedTop - reservedBottom

        // BEARING LINE: Calculate safe top boundary (below buttons)
        val buttonAreaBottom = 120 * dpToPx // 60dp margin + ~48dp button height + 12dp padding
        val safeTopBoundary = kotlin.math.max(reservedTop, buttonAreaBottom)

        // CALCULATE POSITIONS WITHOUT PAN OFFSET FIRST
        // Base start position (center of available area)
        val baseStartX = width / 2f
        val baseStartY = reservedTop + availableHeight - 80 * dpToPx

        // Base bearing line end position
        val maxBearingLineLength = baseStartY - safeTopBoundary
        val bearingLineLength = kotlin.math.min(1000f * viewScale, maxBearingLineLength)
        val baseTargetX = baseStartX
        val baseTargetY = baseStartY - bearingLineLength

        // Base user position
        val baseUserX = baseStartX + (crossTrackDistance * viewScale).toFloat()
        val baseUserY = baseStartY - (alongTrackDistance * viewScale).toFloat()

        // APPLY PAN OFFSET TO ALL POSITIONS
        startPosition.set(
            baseStartX + panOffsetX,
            baseStartY + panOffsetY
        )

        targetPosition.set(
            baseTargetX + panOffsetX,
            baseTargetY + panOffsetY
        )

        userPosition.set(
            baseUserX + panOffsetX,
            baseUserY + panOffsetY
        )

        // Create bearing line with pan offset applied
        bearingLine.reset()
        bearingLine.moveTo(startPosition.x, startPosition.y)
        bearingLine.lineTo(targetPosition.x, targetPosition.y)

        Log.d("GPSTrackingView", "Positions with scale=${viewScale}, pan=(${panOffsetX}, ${panOffsetY}):")
        Log.d("GPSTrackingView", "  Start: (${startPosition.x}, ${startPosition.y})")
        Log.d("GPSTrackingView", "  User: (${userPosition.x}, ${userPosition.y})")
        Log.d("GPSTrackingView", "  Target: (${targetPosition.x}, ${targetPosition.y})")
        Log.d("GPSTrackingView", "User off-track by: ${crossTrackDistance}m, along-track: ${alongTrackDistance}m")
    }

    fun getUserPosition(): PointF {
        return userPosition  // This should be the actual calculated position
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

        // Draw off-track indicator if needed
        if (!isOnTrack) {
            val perpX = userPosition.x
            val perpY = findPerpendicularPointOnLine(userPosition, startPosition, targetPosition)
            canvas.drawLine(userPosition.x, userPosition.y, perpX, perpY, offTrackPaint)
        }

        // Draw start position marker (smaller, different color)
        drawIcon(canvas, startPosition.x, startPosition.y, R.drawable.crosshair_center, 60f)

        // Draw user position icon (main focus)
        drawIcon(canvas, userPosition.x, userPosition.y, R.drawable.user_dot_icon, userIconSize)

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
        val padding = 12f
        val startX = 16f
        val startY = 200f // Below the toggle button

        // Draw background
        val textWidth = 200f
        val textHeight = infoTexts.size * lineHeight + padding * 2
        canvas.drawRect(
            startX - padding,
            startY - padding,
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
        val shadowOffset = 4f

        // Draw shadow line first (offset)
        val shadowPath = Path()
        shadowPath.moveTo(startPosition.x + shadowOffset, startPosition.y + shadowOffset)
        shadowPath.lineTo(targetPosition.x + shadowOffset, targetPosition.y + shadowOffset)
        canvas.drawPath(shadowPath, bearingLineShadowPaint)

        // Draw main bearing line
        canvas.drawPath(bearingLine, bearingLinePaint)

        // Calculate arrowhead at the top of the bearing line
        val arrowSize = 40f // Size of arrowhead
        val lineEndX = targetPosition.x
        val lineEndY = targetPosition.y-8

        // Calculate arrowhead points (pointing up since bearing line goes up)
        val arrowPath = Path()
        arrowPath.moveTo(lineEndX, lineEndY) // Arrow tip
        arrowPath.lineTo(lineEndX - arrowSize/2, lineEndY + arrowSize) // Left wing
        arrowPath.lineTo(lineEndX + arrowSize/2, lineEndY + arrowSize) // Right wing
        arrowPath.close()

        // Draw arrowhead shadow first (offset)
        val arrowShadowPath = Path()
        arrowShadowPath.moveTo(lineEndX + shadowOffset, lineEndY + shadowOffset) // Arrow tip
        arrowShadowPath.lineTo(lineEndX - arrowSize/2 + shadowOffset, lineEndY + arrowSize + shadowOffset) // Left wing
        arrowShadowPath.lineTo(lineEndX + arrowSize/2 + shadowOffset, lineEndY + arrowSize + shadowOffset) // Right wing
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

    private fun findPerpendicularPointOnLine(point: PointF, lineStart: PointF, lineEnd: PointF): Float {
        // Simplified - just return the Y coordinate of the perpendicular point
        // Full implementation would calculate actual perpendicular intersection
        val lineLength = sqrt((lineEnd.x - lineStart.x).pow(2) + (lineEnd.y - lineStart.y).pow(2))
        if (lineLength == 0f) return point.y

        val t = ((point.x - lineStart.x) * (lineEnd.x - lineStart.x) +
                (point.y - lineStart.y) * (lineEnd.y - lineStart.y)) / (lineLength * lineLength)

        return lineStart.y + t * (lineEnd.y - lineStart.y)
    }
    /* TEMPORARILY DISABLED: BREADCRUMB (START)
        private fun drawBreadcrumbTrail(canvas: Canvas) {
            //Log.d("GPSTrackingView", "Drawing breadcrumbs: ${breadcrumbs.size} total")

            if (breadcrumbs.size < 2) {
                Log.d("GPSTrackingView", "Not enough breadcrumbs to draw trail")
                return
            }

            val trailPath = Path()
            var isFirstPoint = true

            breadcrumbs.forEachIndexed { index, breadcrumb ->
                // Convert breadcrumb GPS coordinates to screen coordinates
                val screenPoint = convertBreadcrumbGpsToScreen(breadcrumb.lat, breadcrumb.lon)

                //Log.d("GPSTrackingView", "Breadcrumb $index: GPS(${breadcrumb.lat}, ${breadcrumb.lon}) -> Screen(${screenPoint.x}, ${screenPoint.y})")

                if (isFirstPoint) {
                    trailPath.moveTo(screenPoint.x, screenPoint.y)
                    isFirstPoint = false
                } else {
                    trailPath.lineTo(screenPoint.x, screenPoint.y)
                }

                // Draw individual breadcrumb dot
                val radius = when {
                    breadcrumb.accuracy < 5f -> 12f  // High accuracy = larger dot (increased size)
                    breadcrumb.accuracy < 10f -> 10f
                    else -> 8f // Low accuracy = smaller dot (increased size)
                }

                // Draw  border first
                canvas.drawCircle(screenPoint.x, screenPoint.y, radius + 2f, borderPaint)
                // Then draw the colored center
                canvas.drawCircle(screenPoint.x, screenPoint.y, radius, breadcrumbPaint)
                //Log.d("GPSTrackingView", "Drew breadcrumb circle at (${screenPoint.x}, ${screenPoint.y}) with radius $radius")
            }

            // Draw the connecting trail
            canvas.drawPath(trailPath, breadcrumbTrailPaint)
            Log.d("GPSTrackingView", "Drew breadcrumb trail connecting ${breadcrumbs.size} points")
        }
        private fun convertBreadcrumbGpsToScreen(lat: Double, lon: Double): PointF {
            if (!isTrackingActive || width == 0 || height == 0) {
                return PointF(width / 2f, height / 2f)
            }

            // Use the CURRENT GPS tracking data and CURRENT scale/positions
            val distanceFromStart = navigationCalculator.calculateDistance(startGpsLat, startGpsLon, lat, lon)
            val bearingFromStart = navigationCalculator.calculateBearing(startGpsLat, startGpsLon, lat, lon)

            // Convert to along-track and cross-track distances using CURRENT bearing
            val bearingRad = Math.toRadians(currentGpsBearing.toDouble())
            val currentBearingRad = Math.toRadians(bearingFromStart.toDouble())

            val alongTrackDistance = distanceFromStart * cos(currentBearingRad - bearingRad)
            val crossTrackDistance = distanceFromStart * sin(currentBearingRad - bearingRad)

            // Use CURRENT scale and CURRENT start position (not stored values)
            val screenX = startPosition.x + (crossTrackDistance * viewScale).toFloat()
            val screenY = startPosition.y - (alongTrackDistance * viewScale).toFloat()

            // Apply the same clamping as user position
            val padding = 50f
            val dpToPx = resources.displayMetrics.density
            val reservedTop = 80 * dpToPx
            val reservedBottom = 140 * dpToPx

            return PointF(
                screenX.coerceIn(padding, width - padding),
                screenY.coerceIn(reservedTop + padding, height - reservedBottom - padding)
            )
        }
        // TEMPORARILY DISABLED: BREADCRUMB (END) */
}