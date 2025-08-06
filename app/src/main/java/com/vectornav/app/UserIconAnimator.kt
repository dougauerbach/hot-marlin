package com.vectornav.app

import android.animation.ValueAnimator
import android.graphics.PointF
import android.util.Log
import android.view.animation.DecelerateInterpolator
import kotlin.math.*

/**
 * Smoothly animates user icon position changes to reduce GPS jitter.
 * Calculates realistic movement speed and animates accordingly.
 */
class UserIconAnimator(
    private val callback: AnimationCallback
) {
    // Animation state
    private var animator: ValueAnimator? = null
    private var currentPosition = PointF(0f, 0f)
    private var targetPosition = PointF(0f, 0f)
    private var isInitialized = false
    private var lastUpdateTime = 0L

    // Movement analysis
    private var lastRealPosition = PointF(0f, 0f)
    private var estimatedSpeed = 0f // meters per second
    private val maxWalkingSpeed = 3f // m/s (~7 mph reasonable max)
    private val minAnimationTime = 200L // minimum animation duration
    private val maxAnimationTime = 2000L // maximum animation duration

    interface AnimationCallback {
        fun onPositionUpdated(x: Float, y: Float)
        fun requestRedraw()
    }

    /**
     * Update the target position for the user icon.
     * Calculates appropriate animation based on movement speed.
     */
    fun updatePosition(newX: Float, newY: Float, realWorldDistanceMeters: Float) {
        val newPosition = PointF(newX, newY)
        val currentTime = System.currentTimeMillis()

        if (!isInitialized) {
            // First position - no animation
            currentPosition.set(newPosition)
            targetPosition.set(newPosition)
            lastRealPosition.set(newPosition)
            lastUpdateTime = currentTime
            isInitialized = true
            callback.onPositionUpdated(newX, newY)
            return
        }

        // Calculate time delta
        val timeDeltaSeconds = (currentTime - lastUpdateTime) / 1000f
        if (timeDeltaSeconds <= 0) return

        // Calculate estimated movement speed
        estimatedSpeed = if (timeDeltaSeconds > 0.1f) { // Avoid division by very small numbers
            realWorldDistanceMeters / timeDeltaSeconds
        } else {
            estimatedSpeed // Keep previous speed for very quick updates
        }

        // Calculate screen distance to move
        val screenDistance = sqrt(
            (newX - currentPosition.x).pow(2) + (newY - currentPosition.y).pow(2)
        )

        Log.d("UserIconAnimator", "Movement: ${realWorldDistanceMeters}m in ${timeDeltaSeconds}s = ${estimatedSpeed}m/s, screen distance: ${screenDistance}px")

        // Determine animation duration based on movement characteristics
        val animationDuration = calculateAnimationDuration(
            realWorldDistanceMeters,
            estimatedSpeed,
            timeDeltaSeconds,
            screenDistance
        )

        if (animationDuration > 0) {
            startAnimation(newPosition, animationDuration)
        } else {
            // No animation needed - snap to position
            currentPosition.set(newPosition)
            callback.onPositionUpdated(newX, newY)
        }

        targetPosition.set(newPosition)
        lastRealPosition.set(newPosition)
        lastUpdateTime = currentTime
    }

    /**
     * Calculate appropriate animation duration based on movement characteristics
     */
    private fun calculateAnimationDuration(
        distanceMeters: Float,
        speedMps: Float,
        timeDelta: Float,
        screenDistance: Float
    ): Long {
        return when {
            // Very small movement - might be GPS jitter, animate quickly
            distanceMeters < 0.5f && screenDistance < 20f -> {
                200L
            }

            // Large jump that seems unrealistic for walking - GPS correction, animate moderately
            speedMps > maxWalkingSpeed -> {
                Log.d("UserIconAnimator", "GPS jump detected: ${speedMps}m/s > ${maxWalkingSpeed}m/s - using moderate animation")
                800L
            }

            // Normal walking speed - animate based on realistic movement time
            speedMps > 0.1f -> {
                val realisticTime = (distanceMeters / speedMps * 1000).toLong()
                realisticTime.coerceIn(minAnimationTime, maxAnimationTime)
            }

            // Very slow or stationary - quick animation
            else -> {
                300L
            }
        }
    }

    /**
     * Start smooth animation to new position
     */
    private fun startAnimation(target: PointF, duration: Long) {
        // Cancel any existing animation
        animator?.cancel()

        val startX = currentPosition.x
        val startY = currentPosition.y
        val targetX = target.x
        val targetY = target.y

        Log.d("UserIconAnimator", "Animating from (${startX}, ${startY}) to (${targetX}, ${targetY}) over ${duration}ms")

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator() // Smooth deceleration

            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                val currentX = startX + (targetX - startX) * progress
                val currentY = startY + (targetY - startY) * progress

                currentPosition.set(currentX, currentY)
                callback.onPositionUpdated(currentX, currentY)
                callback.requestRedraw()
            }

            start()
        }
    }

    /**
     * Get current animated position
     */
    fun getCurrentPosition(): PointF = currentPosition

    /**
     * Get target position (where animation is heading)
     */
    fun getTargetPosition(): PointF = targetPosition

    /**
     * Stop any running animation and snap to target
     */
    fun snapToTarget() {
        animator?.cancel()
        currentPosition.set(targetPosition)
        callback.onPositionUpdated(targetPosition.x, targetPosition.y)
    }

    /**
     * Check if animation is currently running
     */
    fun isAnimating(): Boolean = animator?.isRunning == true

    /**
     * Clean up resources
     */
    fun cleanup() {
        animator?.cancel()
        animator = null
    }
}