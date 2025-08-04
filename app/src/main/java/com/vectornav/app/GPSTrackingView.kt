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
        color = 0x88FFFFFF.toInt() // Semi-transparent white
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val breadcrumbTrailPaint = Paint().apply {
        color = 0x44FFFFFF.toInt() // Very faint white line
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
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

        // scaling logic
        val dpToPx = resources.displayMetrics.density
        val reservedTop = 80 * dpToPx
        val reservedBottom = 140 * dpToPx
        val availableHeight = height - reservedTop - reservedBottom
        val availableWidth = width.toFloat()

        // Calculate the maximum extent of all elements
        val maxDistance = maxOf(
            targetDistance.toFloat(),
            kotlin.math.abs(alongTrackDistance).toFloat(),
            kotlin.math.abs(crossTrackDistance).toFloat() * 2
        )

        // Scale to fit the largest element with 20% padding
        val scaleForHeight = (availableHeight * 0.8f) / maxDistance
        val scaleForWidth = (availableWidth * 0.8f) / maxDistance
        viewScale = kotlin.math.min(scaleForHeight, scaleForWidth)

        Log.d("GPSTrackingView", "Adaptive scaling: maxDistance=${maxDistance}m, viewScale=${viewScale}")

        // Fixed positions: start at bottom, target at top
        startPosition.set(
            width / 2f,
            reservedTop + availableHeight - 40 * dpToPx
        )

        targetPosition.set(
            width / 2f,
            reservedTop + 80 * dpToPx
        )

        // User position based on calculated distances
        userPosition.set(
            startPosition.x + (crossTrackDistance * viewScale).toFloat(),
            startPosition.y - (alongTrackDistance * viewScale).toFloat()
        )

        // Create bearing line
        bearingLine.reset()
        bearingLine.moveTo(startPosition.x, startPosition.y)
        bearingLine.lineTo(targetPosition.x, targetPosition.y)

        Log.d("GPSTrackingView", "Final positions: start(${startPosition.x}, ${startPosition.y}), user(${userPosition.x}, ${userPosition.y}), target(${targetPosition.x}, ${targetPosition.y})")
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
        if (breadcrumbs.size < 2) return

        val trailPath = Path()
        var isFirstPoint = true

        breadcrumbs.forEach { breadcrumb ->
            // Convert breadcrumb GPS coordinates to screen coordinates
            val screenPoint = convertGpsToScreen(breadcrumb.lat, breadcrumb.lon)

            if (isFirstPoint) {
                trailPath.moveTo(screenPoint.x, screenPoint.y)
                isFirstPoint = false
            } else {
                trailPath.lineTo(screenPoint.x, screenPoint.y)
            }

            // Draw individual breadcrumb dot
            val radius = when {
                breadcrumb.accuracy < 5f -> 6f  // High accuracy = larger dot
                breadcrumb.accuracy < 10f -> 4f
                else -> 2f // Low accuracy = smaller dot
            }

            canvas.drawCircle(screenPoint.x, screenPoint.y, radius, breadcrumbPaint)
        }

        // Draw the connecting trail
        canvas.drawPath(trailPath, breadcrumbTrailPaint)
    }
    private fun convertGpsToScreen(lat: Double, lon: Double): PointF {
        // Convert GPS coordinates to screen coordinates using the same logic as calculateViewCoordinates
        // This is a simplified version - you might want to use the full calculation

        // For now, just return current user position as placeholder
        // You'll want to implement proper GPS->screen coordinate conversion here
        return PointF(userPosition.x, userPosition.y)
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