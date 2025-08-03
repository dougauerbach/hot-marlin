package com.vectornav.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Enhanced LargeTaperedLineView with self-contained navigation logic
class LargeTaperedLineView(context: Context) : View(context) {
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var isVisible = false

    private val linePaint = Paint().apply {
        color = 0xCCFFD700.toInt() // Gold color with transparency
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun updateNavigationLine(
        targetBearing: Float,
        currentDeviceAzimuth: Float
    ) {
        // Calculate how much to turn from current device heading to target bearing
        val relativeBearing = normalizeAngle(targetBearing - currentDeviceAzimuth)

        Log.d("VectorNav", "UpdateNavigationLine: target=${targetBearing}°, current=${currentDeviceAzimuth}°, relative=${relativeBearing}°")
        Log.d("VectorNav", "Line guidance: ${getGuidanceText(relativeBearing)}")

        // Calculate line positions on screen
        calculateLinePositions(relativeBearing)

        isVisible = true
        invalidate() // Trigger redraw
    }

    private fun calculateLinePositions(relativeBearing: Float) {
        val screenWidth = width.toFloat()
        val screenHeight = height.toFloat()

        if (screenWidth == 0f || screenHeight == 0f) {
            Log.w("VectorNav", "Screen dimensions not available yet")
            return
        }

        // Use a smooth anchor positioning system
        val normalizedBearing = kotlin.math.max(-45f, kotlin.math.min(45f, relativeBearing)) // Clamp to ±45°
        val anchorRatio = normalizedBearing / 45f  // -1.0 to 1.0

        // Smooth transition from left edge to center to right edge
        startX = (screenWidth / 2f) + (anchorRatio * screenWidth * 0.3f)  // Max 30% from center
        startY = screenHeight  // Always at bottom

        // Calculate tip position using rule of thirds (33% from top)
        val lineLength = screenHeight * 0.67f  // Line extends 67% up from bottom (33% from top)
        endX = (screenWidth / 2f) + sin(Math.toRadians(relativeBearing.toDouble())).toFloat() * lineLength
        endY = screenHeight - cos(Math.toRadians(relativeBearing.toDouble())).toFloat() * lineLength

        Log.d("VectorNav", "Road blade: bearing=${relativeBearing}°, anchor($startX,$startY) tip($endX,$endY)")
    }

    fun hideNavigation() {
        isVisible = false
        invalidate()
    }

    private fun normalizeAngle(angle: Float): Float {
        var normalized = angle
        while (normalized > 180f) normalized -= 360f
        while (normalized < -180f) normalized += 360f
        return normalized
    }

    private fun getGuidanceText(relativeBearing: Float): String {
        return when {
            relativeBearing > 5 -> "turn right"  // Removed degree numbers
            relativeBearing < -5 -> "turn left"  // Removed degree numbers
            else -> "on target"
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isVisible || (startX == 0f && startY == 0f && endX == 0f && endY == 0f)) return

        Log.d("VectorNav", "Drawing road blade from ($startX, $startY) to ($endX, $endY)")

        // Width calculations based on screen width
        val screenWidth = width.toFloat()
        val startWidth = screenWidth * 0.33f  // 33% of screen width at bottom
        val endWidth = 8f                     // Very narrow at tip for sharp blade effect

        // Create road blade path that emerges from bottom of screen
        val bladePath = Path()

        // Bottom edge spans the full width needed, anchored at screen bottom
        val bottomY = height.toFloat()  // Force bottom to be at screen edge
        val bottomLeftX = startX - startWidth/2
        val bottomRightX = startX + startWidth/2

        // Top converges to a sharp point
        val tipX = endX
        val tipY = endY

        // Create blade shape - wide at bottom, sharp point at top
        bladePath.moveTo(bottomLeftX, bottomY)   // Bottom left - screen bottom
        bladePath.lineTo(bottomRightX, bottomY)  // Bottom right - screen bottom
        bladePath.lineTo(tipX, tipY)             // Sharp tip
        bladePath.close()

        // Draw the golden road blade
        canvas.drawPath(bladePath, linePaint)

        Log.d("VectorNav", "Drew golden road blade with natural sharp tip")
    }
}