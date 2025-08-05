package com.vectornav.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.*

private const val offTrackPaintColor = 0xFFE2725B.toInt() // Coral/salmon off-track indicator

private const val bearingLinePaintColor = 0xFFE1E6E1 // Light gray-green bearing line

private const val userIconSize = 200f
private const val targetIconSize = 160f

/**
 * Custom view that displays GPS tracking in a top-down perspective.
 * Shows user position relative to bearing line without camera or compass dependency.
 */
class GPSTrackingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
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

    // View parameters
    private var viewScale = 1.0f  // Meters per pixel
    private var viewCenterX = 0f
    private var viewCenterY = 0f

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
        // Store GPS data for breadcrumb conversion
        startGpsLat = startLat
        startGpsLon = startLon
        currentGpsBearing = bearing
        currentTargetDistance = targetDistance

        isTrackingActive = true
        crossTrackError = crossTrackErrorMeters
        distanceFromStart = distanceFromStartMeters
        distanceRemaining = kotlin.math.max(0f, targetDistance - distanceFromStartMeters)
        isOnTrack = onTrack

        // Convert GPS coordinates to view coordinates
        calculateViewCoordinates(startLat, startLon, currentLat, currentLon, bearing, targetDistance)

        Log.d("GPSTrackingView", "View updated: user($userPosition), crossTrack=${crossTrackError}m, onTrack=$isOnTrack")

        invalidate()
    }
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

        // Screen layout constants
        val dpToPx = resources.displayMetrics.density
        val reservedTop = 80 * dpToPx
        val reservedBottom = 140 * dpToPx
        val availableHeight = height - reservedTop - reservedBottom
        val availableWidth = width.toFloat()

        // ADAPTIVE SCALE CALCULATION:
        // Recalculate scale for both zoom out (far movement) and zoom in (close approach)
        val userAlongTrack = alongTrackDistance.toFloat()
        val userCrossTrack = kotlin.math.abs(crossTrackDistance).toFloat()

        // Calculate actual distance from user to target (not just along-track)
        val distanceToTarget = kotlin.math.sqrt(
            (userAlongTrack - targetDistance).pow(2) + userCrossTrack.pow(2)
        )

        // Check if we need to adjust scale for better visibility
        val needsScaleChange = when {
            // ZOOM OUT: User went significantly past target (>20% beyond)
            userAlongTrack > targetDistance * 1.2f -> true

            // ZOOM OUT: User went significantly behind start (>20% of target distance)
            userAlongTrack < -targetDistance * 0.2f -> true

            // ZOOM OUT: User went too far off-track (would be outside screen width)
            userCrossTrack > targetDistance * 0.8f -> true

            // ZOOM IN: User is very close to actual target location (within 10m and scale allows for zooming in)
            distanceToTarget < 10f && viewScale < 50f -> true

            // ZOOM IN: User is close to target area (within 15m and on a reasonable track)
            distanceToTarget < 15f && userCrossTrack < 5f && viewScale < 40f -> true

            // ZOOM OUT: Current view is too zoomed in for the user's position
            (kotlin.math.abs(userAlongTrack) > targetDistance && viewScale > 15f) -> true

            // Current scale not set yet (first time) - check for uninitialized scale
            viewScale <= 0f || viewScale == 1.0f -> true

            else -> false
        }

        if (needsScaleChange) {
            val zoomReason = when {
                userAlongTrack > targetDistance * 1.2f -> "past target"
                userAlongTrack < -targetDistance * 0.2f -> "behind start"
                userCrossTrack > targetDistance * 0.8f -> "off-track"
                distanceToTarget < 10f -> "very close to target"
                distanceToTarget < 15f && userCrossTrack < 5f -> "approaching target area"
                kotlin.math.abs(userAlongTrack) > targetDistance && viewScale > 15f -> "too zoomed in"
                else -> "initializing"
            }

            Log.d("GPSTrackingView", "🔄 Scale change needed - $zoomReason (distance to target: ${"%.1f".format(distanceToTarget)}m)")

            // Calculate new view range based on user proximity to target
            val viewRangeVertical = when {
                // Very close to target - zoom in for precision (but not at startup)
                distanceToTarget < 10f && distanceFromStart > 1f -> {
                    kotlin.math.max(20f, distanceToTarget * 4f)  // Tight zoom around target area
                }

                // User behind start - show actual movement range efficiently
                userAlongTrack < 0 -> {
                    val actualRange = kotlin.math.abs(userAlongTrack) + targetDistance
                    actualRange * 1.1f  // Just 10% padding for efficient space usage
                }

                // User past target - show actual movement range efficiently
                userAlongTrack > targetDistance -> {
                    userAlongTrack * 1.1f  // Just 10% padding
                }

                else -> {
                    // Normal case (including startup) - efficient range based on actual movement
                    val actualMovementRange = kotlin.math.max(distanceFromStart, targetDistance.toFloat())
                    actualMovementRange * 1.2f  // 20% padding for normal case
                }
            }

            val viewRangeHorizontal = when {
                // Close approach - smaller horizontal range for precision
                distanceToTarget < 10f -> {
                    kotlin.math.max(userCrossTrack * 6f, 15f)  // Tight horizontal zoom
                }
                else -> {
                    // Efficient horizontal range - just what's needed
                    kotlin.math.max(userCrossTrack * 2.2f, targetDistance * 0.3f)
                }
            }

            // Calculate new scale to fit the view range in available space EFFICIENTLY
            val scaleForHeight = (availableHeight * 0.95f) / viewRangeVertical  // Use 95% of available height
            val scaleForWidth = (availableWidth * 0.90f) / viewRangeHorizontal   // Use 90% of available width
            viewScale = kotlin.math.min(scaleForHeight, scaleForWidth)

            // Ensure reasonable scale bounds
            val minScale = 8f   // Minimum for very large distances
            val maxScale = 100f // Maximum for very close distances
            viewScale = viewScale.coerceIn(minScale, maxScale)

            Log.d("GPSTrackingView", "New scale: vertical=${"%.1f".format(viewRangeVertical)}m, horizontal=${"%.1f".format(viewRangeHorizontal)}m, scale=${"%.1f".format(viewScale)} (${zoomReason})")
        } else {
            Log.d("GPSTrackingView", "Using stable scale: ${"%.1f".format(viewScale)}")
        }

        // FIXED TARGET POSITIONING:
        // Target position should be stable and only move if absolutely necessary
        if (needsScaleChange || targetPosition.x == 0f) {
            // Calculate the raw positions first
            val rawTargetOffsetY = (targetDistance * viewScale).toFloat()
            val rawUserX = (crossTrackDistance * viewScale).toFloat()
            val rawUserY = -(alongTrackDistance * viewScale).toFloat()

            // Determine if both icons can fit with normal positioning
            val wouldTargetBeOffScreen = rawTargetOffsetY > availableHeight * 0.9f
            val wouldUserBeOffScreen = kotlin.math.abs(rawUserY) > availableHeight * 0.9f

            if (wouldTargetBeOffScreen || wouldUserBeOffScreen) {
                // ADAPTIVE POSITIONING: Position both icons to be visible
                Log.d("GPSTrackingView", "🔄 Adaptive positioning needed - target offset: ${rawTargetOffsetY}, user offset: ${rawUserY}")

                // Calculate the total vertical range needed
                val userAlongTrackAbs = kotlin.math.abs(alongTrackDistance).toFloat()
                val totalVerticalRange = kotlin.math.max(targetDistance.toFloat(), userAlongTrackAbs)

                // Position elements to use the FULL available space efficiently
                val topMargin = reservedTop + 40 * dpToPx      // Smaller top margin
                val bottomMargin = 40 * dpToPx                 // Smaller bottom margin
                val usableHeight = height - topMargin - bottomMargin

                if (userAlongTrack > targetDistance) {
                    // User past target - position target in lower portion, user above
                    val targetRatio = targetDistance / totalVerticalRange
                    targetPosition.set(
                        width / 2f,
                        topMargin + usableHeight * (1f - targetRatio * 0.8f)  // Use 80% of space efficiently
                    )

                    startPosition.set(
                        width / 2f,
                        targetPosition.y + (targetDistance * viewScale)
                    )
                } else {
                    // User behind start - position start higher, target at top
                    val userRatio = userAlongTrackAbs / totalVerticalRange
                    startPosition.set(
                        width / 2f,
                        topMargin + usableHeight * userRatio * 0.8f  // Position start based on user distance
                    )

                    targetPosition.set(
                        width / 2f,
                        kotlin.math.max(topMargin, startPosition.y - (targetDistance * viewScale))
                    )
                }
            } else {
                // NORMAL POSITIONING: Standard layout when both icons fit
                if (userAlongTrack > targetDistance * 1.1f) {
                    // User is past target - position target in middle, start below target
                    targetPosition.set(
                        width / 2f,
                        reservedTop + availableHeight * 0.5f
                    )

                    startPosition.set(
                        width / 2f,
                        targetPosition.y + (targetDistance * viewScale)
                    )
                } else {
                    // Normal case - start at bottom, target above it
                    startPosition.set(
                        width / 2f,
                        reservedTop + availableHeight - 60 * dpToPx
                    )

                    targetPosition.set(
                        width / 2f,
                        startPosition.y - (targetDistance * viewScale)
                    )
                }
            }

            Log.d("GPSTrackingView", "Updated positions: start(${startPosition.x}, ${startPosition.y}), target(${targetPosition.x}, ${targetPosition.y})")
        }

        // USER POSITION: Always calculated relative to fixed start position
        // Calculate raw position first
        val rawUserX = startPosition.x + (crossTrackDistance * viewScale).toFloat()
        val rawUserY = startPosition.y - (alongTrackDistance * viewScale).toFloat()

        // Clamp to screen bounds with padding - but also ensure it doesn't overlap with target
        val padding = 50f
        val minY = kotlin.math.max(reservedTop + padding, targetPosition.y + 100f) // Stay below target + margin
        val maxY = height - reservedBottom - padding

        userPosition.set(
            rawUserX.coerceIn(padding, width - padding),
            rawUserY.coerceIn(minY, maxY)
        )

        // Log if user was clamped
        if (rawUserX != userPosition.x || rawUserY != userPosition.y) {
            Log.d("GPSTrackingView", "User position clamped: raw(${rawUserX}, ${rawUserY}) -> clamped(${userPosition.x}, ${userPosition.y})")
        }

        // Create bearing line from start to target (these positions are now stable)
        bearingLine.reset()
        bearingLine.moveTo(startPosition.x, startPosition.y)
        bearingLine.lineTo(targetPosition.x, targetPosition.y)

        Log.d("GPSTrackingView", "Final positions: start(${startPosition.x}, ${startPosition.y}), user(${userPosition.x}, ${userPosition.y}), target(${targetPosition.x}, ${targetPosition.y})")
    }

    fun getUserPosition(): PointF {
        return userPosition  // This should be the actual calculated position
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        //Log.d("GPSTrackingView", "onDraw called - isTrackingActive: $isTrackingActive")
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

        // Draw bearing line
        canvas.drawPath(bearingLine, bearingLinePaint)

        // Draw breadcrumb trail BEFORE other elements
        drawBreadcrumbTrail(canvas)

        // Draw off-track indicator if needed
        if (!isOnTrack) {
            val perpX = userPosition.x
            val perpY = findPerpendicularPointOnLine(userPosition, startPosition, targetPosition)
            canvas.drawLine(userPosition.x, userPosition.y, perpX, perpY, offTrackPaint)
        }

        // Start position now represented by beginning of bearing line (removed circle)

        // Draw target area (custom icon)
        drawIcon(canvas, targetPosition.x, targetPosition.y, R.drawable.target_dot_icon, targetIconSize)

        // Draw user position (custom icon)
        drawIcon(canvas, userPosition.x, userPosition.y, R.drawable.user_dot_icon, userIconSize)

        // Draw distance remaining (left side, same height as target)
        val remainingText = "${distanceRemaining.toInt()}m remaining"
        canvas.drawText(remainingText, 40f, targetPosition.y + 15f, distancePaint)  // 40f from left edge, 15f below target center

        // Draw distance traveled (center, top)
        val distanceText = "${distanceFromStart.toInt()}m traveled"
        canvas.drawText(distanceText, width / 2f, 100f, textPaint)

        // Draw cross-track error
        if (!isOnTrack) {
            val errorText = "${abs(crossTrackError).toInt()}m ${if (crossTrackError > 0) "right" else "left"} of track"
            canvas.drawText(errorText, width / 2f, 160f, textPaint)
        }

        // Draw breadcrumb count
        if (breadcrumbs.isNotEmpty()) {
            val breadcrumbText = "${breadcrumbs.size} trail points"
            canvas.drawText(breadcrumbText, width / 2f, height - 50f, textPaint.apply {
                textSize = 32f
            })
            textPaint.textSize = 40f // Reset size
        }

    }

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

}