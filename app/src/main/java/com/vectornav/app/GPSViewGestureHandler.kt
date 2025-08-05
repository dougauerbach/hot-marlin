package com.vectornav.app

import android.content.Context
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/**
 * Handles all gesture interactions for the GPS tracking view.
 * Encapsulates pinch-to-zoom, long-press-to-drag, and view manipulation logic.
 */
class GPSViewGestureHandler(
    context: Context,
    private val callback: GestureCallback
) {
    // Gesture detectors
    private val scaleGestureDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    // View state
    private var currentScale: Float = 50f  // pixels per meter (default walking scale)
    private var panOffsetX: Float = 0f     // horizontal pan offset
    private var panOffsetY: Float = 0f     // vertical pan offset

    // Scale bounds
    private val minScale: Float = 10f      // Zoomed out (10 pixels per meter)
    private val maxScale: Float = 200f     // Zoomed in (200 pixels per meter)

    // Drag state
    private var isDragging = false
    private var lastDragX = 0f
    private var lastDragY = 0f

    // Callback interface for communicating back to GPSTrackingView
    interface GestureCallback {
        fun onScaleChanged(newScale: Float)
        fun onPanChanged(offsetX: Float, offsetY: Float)
        fun onViewReset()
        fun onViewInvalidate() // Request redraw
    }

    init {
        // Set up scale gesture detector for pinch-to-zoom
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val newScale = (currentScale * scaleFactor).coerceIn(minScale, maxScale)

                if (newScale != currentScale) {
                    Log.d("GPSGestures", "Scale: $currentScale -> $newScale (factor: $scaleFactor)")
                    currentScale = newScale
                    callback.onScaleChanged(currentScale)
                    callback.onViewInvalidate()
                }

                return true
            }

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                Log.d("GPSGestures", "Pinch-to-zoom started")
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                Log.d("GPSGestures", "Pinch-to-zoom ended at scale: $currentScale")
            }
        })

        // Set up gesture detector for short-press and drag (like Google Maps)
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // Start drag mode immediately on touch down (like Google Maps)
                Log.d("GPSGestures", "Touch down - ready for drag")
                lastDragX = e.x
                lastDragY = e.y
                // Don't set isDragging = true yet, wait for actual movement
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                Log.d("GPSGestures", "Double tap - resetting view")
                resetView()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // Let single taps pass through to parent for navigation control
                Log.d("GPSGestures", "Single tap - letting parent handle")
                return false
            }
        })
    }

    /**
     * Process touch events. Call this from the view's onTouchEvent.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = false

        // Let scale detector handle pinch gestures first
        handled = scaleGestureDetector.onTouchEvent(event) || handled

        // Let gesture detector handle taps and long press
        handled = gestureDetector.onTouchEvent(event) || handled

        // Handle drag after touch down
        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                // Start dragging if we've moved enough and not pinching
                if (!isDragging && !scaleGestureDetector.isInProgress) {
                    val deltaX = event.x - lastDragX
                    val deltaY = event.y - lastDragY
                    val distance = kotlin.math.sqrt(deltaX * deltaX + deltaY * deltaY)

                    // Start dragging if moved more than 10 pixels
                    if (distance > 10f) {
                        isDragging = true
                        Log.d("GPSGestures", "Started dragging after ${distance}px movement")
                    }
                }

                if (isDragging && !scaleGestureDetector.isInProgress) {
                    val deltaX = event.x - lastDragX
                    val deltaY = event.y - lastDragY

                    panOffsetX += deltaX
                    panOffsetY += deltaY

                    Log.d("GPSGestures", "Dragging: offset=(${panOffsetX}, ${panOffsetY})")

                    callback.onPanChanged(panOffsetX, panOffsetY)
                    callback.onViewInvalidate()

                    lastDragX = event.x
                    lastDragY = event.y
                    handled = true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    Log.d("GPSGestures", "Drag ended")
                    isDragging = false
                    handled = true
                }
            }
        }

        return handled
    }

    /**
     * Reset view to default scale and position
     */
    private fun resetView() {
        currentScale = 50f  // Default walking scale
        panOffsetX = 0f
        panOffsetY = 0f

        callback.onScaleChanged(currentScale)
        callback.onPanChanged(panOffsetX, panOffsetY)
        callback.onViewReset()
        callback.onViewInvalidate()
    }

    /**
     * Get current scale (pixels per meter)
     */
    fun getCurrentScale(): Float = currentScale

    /**
     * Get current pan offset
     */
    fun getPanOffset(): Pair<Float, Float> = Pair(panOffsetX, panOffsetY)

    /**
     * Set scale programmatically (with bounds checking)
     */
    fun setScale(scale: Float) {
        val newScale = scale.coerceIn(minScale, maxScale)
        if (newScale != currentScale) {
            currentScale = newScale
            callback.onScaleChanged(currentScale)
        }
    }

    /**
     * Set pan offset programmatically
     */
    fun setPanOffset(offsetX: Float, offsetY: Float) {
        panOffsetX = offsetX
        panOffsetY = offsetY
        callback.onPanChanged(panOffsetX, panOffsetY)
    }

    /**
     * Get scale bounds for UI display
     */
    fun getScaleBounds(): Pair<Float, Float> = Pair(minScale, maxScale)

    /**
     * Check if currently dragging
     */
    fun isDragging(): Boolean = isDragging
}