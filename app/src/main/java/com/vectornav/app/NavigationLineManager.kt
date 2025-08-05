package com.vectornav.app

import android.util.Log

/**
 * Manages the lifecycle and updates of the navigation line view.
 * Handles creating, updating, and destroying the tapered line that guides users to their destination.
 */
class NavigationLineManager(private val context: MainActivity) {
    private var taperedLineView: LargeTaperedLineView? = null

    /**
     * Creates or updates the navigation line to guide back to the target bearing.
     *
     * @param targetBearing The original bearing set when user tapped (0-360°)
     * @param currentDeviceAzimuth Current device compass heading (0-360°)
     */
    fun createOrUpdateNavigationLine(
        targetBearing: Float,
        currentDeviceAzimuth: Float
    ) {
        if (taperedLineView == null) {
            createNavigationView()
        }

        taperedLineView?.updateNavigationLine(targetBearing, currentDeviceAzimuth)
    }

    /**
     * Creates the navigation line view and adds it to the activity layout.
     */
    private fun createNavigationView() {
        val parentLayout = context.binding.root

        // Remove existing view if any
        taperedLineView?.let { parentLayout.removeView(it) }

        // Create new view
        taperedLineView = LargeTaperedLineView(context).apply {
            tag = "largeTaperedLine"
        }

        val params = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT,
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT
        ).apply {
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        }

        parentLayout.addView(taperedLineView, params)
        // Don't bring to front - let other UI elements appear on top

        Log.d("VectorNav", "Created new navigation line view")
    }

    /**
     * Hides the navigation line without removing it from the layout.
     */
    fun hideNavigationLine() {
        taperedLineView?.hideNavigation()
    }

    /**
     * Completely removes the navigation line view from the layout.
     * Should be called when navigation ends or activity is destroyed.
     */
    fun removeNavigationView() {
        taperedLineView?.let { view ->
            val parentLayout = context.binding.root
            parentLayout.removeView(view)
        }
        taperedLineView = null
    }

}