// 📄 GPSTesterUI.kt - FIXED VERSION with proper padding and copyable text
package com.vectornav.app.testing

import android.content.Context
import android.graphics.Typeface
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.vectornav.app.GPSTrackingView

class GPSTesterUI(
    private val context: AppCompatActivity,
    private val callbacks: GPSTesterUICallbacks
) {

    interface GPSTesterUICallbacks {
        fun onToggleStatusDisplays()
        fun onSwitchTab(tabId: String)
        fun onQuickTest()
        fun onRealGPS()
        fun onReset()
        fun onMoveUser(direction: Float, distance: Float)
        fun onJumpToTarget()
        fun onSimulateWalkingPath()
        fun onRunAutomaticMovementTest()
        fun onUseRealCompass()
        fun onStartCompassCalibration()
        fun onTestCompassAccuracy()
        fun onSetMockCompass(azimuth: Float)
        fun onRunTextVerificationTest()
        fun onRunBreadcrumbVerificationTest()
        fun onTestOffTrackScenario()
        fun onTestAllCompassDirections()
        fun onTestDifferentDistances()
        fun onCompareCalculationMethods()
        fun onSimulateShortMovement(distance: Float)
        fun onRunFullVerificationTest()
        fun onStartSensorFusionTest()
        fun onStopSensorFusionTest()
        fun onHelpFindNorth()
        fun onStartFigure8Calibration()
        fun onCompareCompassWithGPS()
        fun onStartCompassComparison()
        fun onGenerateFullTestReport()
        fun onExportTestData()
    }

    // UI Components - Initialize immediately to avoid lateinit issues
    val testingView: GPSTrackingView by lazy { GPSTrackingView(context) }
    val realCompassDisplay: TextView by lazy { createCopyableTextView() }
    val diagnosticDisplay: TextView by lazy { createCopyableTextView() }
    val coordinateDisplay: TextView by lazy { createCopyableTextView() }
    val sensorFusionDisplay: TextView by lazy { createCopyableTextView() }
    val movementDisplay: TextView by lazy { createCopyableTextView() }
    val textVerificationDisplay: TextView by lazy { createCopyableTextView() }
    val breadcrumbVerificationDisplay: TextView by lazy { createCopyableTextView() }
    val compassVerificationDisplay: TextView by lazy { createCopyableTextView() }
    val mockDistanceText: TextView by lazy { createCopyableTextView() }
    val mockInstructionText: TextView by lazy { createCopyableTextView() }

    private var activeControlTab = "basic"

    // Helper function to create copyable text views
    private fun createCopyableTextView(): TextView {
        return TextView(context).apply {
            // Enable text selection
            setTextIsSelectable(true)

            // Style it
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(8, 8, 8, 8)

            // Add copy button programmatically
            setOnClickListener {
                // Create and show a simple copy dialog
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("GPS Test Data", text.toString())
                clipboard.setPrimaryClip(clip)

                // Visual feedback - flash the background
                val originalBackground = background
                setBackgroundColor(0xFF004400.toInt()) // Dark green flash
                postDelayed({
                    setBackgroundColor(0xFF222222.toInt()) // Back to original
                }, 150)
                true
            }
        }
    }

    fun createMainLayout(): ScrollView {
        return ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )

            // Add top padding to avoid status bar overlap
            setPadding(10, getStatusBarHeight(), 10, 10)

            addView(createScrollableContent())
        }
    }

    // Calculate status bar height to avoid overlap
    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        // Add extra padding for safety
        return result + 16
    }

    private fun createScrollableContent(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(15, 15, 15, 15)

            addView(createHeaderSection())
            addView(createStatusDisplaysSection())
            addView(createTestingViewSection())
            addView(createControlPanelsSection())
        }
    }

    private fun createHeaderSection(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 15)

            // Title
            addView(TextView(context).apply {
                text = "🔍 GPS Navigation Tester"
                textSize = 18f
                setBackgroundColor(0xFF000000.toInt()) // Black background
                setTextColor(0xFFFFFFFF.toInt())       // White text
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 10)
            })

            // Quick status summary
            addView(createQuickStatusBar())
        }
    }

    private fun createQuickStatusBar(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 8, 10, 8)
            setBackgroundColor(0xFF333333.toInt())

            // Status text row (full width)
            val statusRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL

                val statusText = TextView(context).apply {
                    text = "🟢 Ready | 📱 Compass: OK | 📍 GPS: Waiting"
                    textSize = 10f
                    setTextColor(0xFFFFFFFF.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    tag = "quickStatus"
                    gravity = Gravity.CENTER
                    setPadding(0, 4, 0, 4)
                }
                addView(statusText)
            }
            addView(statusRow)

            // Button row (centered, below status, with proper padding from top)
            val buttonRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 12, 0, 4) // Extra top padding to move away from status bar

                val expandButton = Button(context).apply {
                    text = "📊 Show Details"
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        60
                    ).apply {
                        setMargins(0, 8, 0, 0) // Additional top margin
                    }
                    setPadding(16, 8, 16, 8)
                    setOnClickListener { callbacks.onToggleStatusDisplays() }
                    tag = "expandButton"
                    setBackgroundColor(0xFF555555.toInt())
                    setTextColor(0xFFFFFFFF.toInt())
                }
                addView(expandButton)
            }
            addView(buttonRow)
        }
    }

    private fun createStatusDisplaysSection(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "statusSection"
            visibility = View.GONE // Initially collapsed

            addView(createCompactDisplay("📱 Real Compass", realCompassDisplay))
            addView(createCompactDisplay("🧮 Diagnostics", diagnosticDisplay))
            addView(createCompactDisplay("🌍 Coordinates", coordinateDisplay))
            addView(createCompactDisplay("🔧 Sensor Fusion", sensorFusionDisplay))
            addView(createCompactDisplay("🎯 Movement", movementDisplay))
            addView(createCompactDisplay("📝 Text Tests", textVerificationDisplay))
            addView(createCompactDisplay("🍞 Breadcrumbs", breadcrumbVerificationDisplay))
            addView(createCompactDisplay("🧭 Compass Tests", compassVerificationDisplay))
        }
    }

    private fun createCompactDisplay(title: String, textView: TextView): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 6, 8, 6)
            setBackgroundColor(0xFF2A2A2A.toInt())

            val titleView = TextView(context).apply {
                text = "$title"
                textSize = 13f
                setTextColor(0xFFFFFF00.toInt())
                typeface = Typeface.DEFAULT_BOLD
                setPadding(4, 4, 4, 4)
            }
            addView(titleView)

            textView.apply {
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT  // Changed from fixed height to WRAP_CONTENT
                ).apply {
                    // Set minimum height but allow expansion
                    height = LinearLayout.LayoutParams.WRAP_CONTENT
                }
                // Remove line limits to show all content
                maxLines = Integer.MAX_VALUE  // Allow unlimited lines
                ellipsize = null  // Remove ellipsize so text doesn't get cut off
                setPadding(8, 8, 8, 8)
                setBackgroundColor(0xFF222222.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                typeface = Typeface.MONOSPACE

                // Additional copyable setup
                //setTextIsSelectable(true)
                //isClickable = true
                //isFocusable = true
                //isFocusableInTouchMode = true

                // Add copy button programmatically
                setOnClickListener {
                    // Create and show a simple copy dialog
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText(title, textView.text)
                    clipboard.setPrimaryClip(clip)

                    // Visual feedback - flash the background
                    val originalBackground = background
                    setBackgroundColor(0xFF004400.toInt()) // Dark green flash
                    postDelayed({
                        setBackgroundColor(0xFF222222.toInt()) // Back to original
                    }, 150)
                    true
                }
            }
            addView(textView)
        }
    }

    private fun createTestingViewSection(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 10)

            addView(TextView(context).apply {
                text = "📱 GPS Navigation View"
                textSize = 14f
                setTextColor(0xFF00FFFF.toInt())
                setPadding(0, 0, 0, 5)
            })

            val container = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    300
                )
                setBackgroundColor(0xFF2C2C2C.toInt())
            }

            testingView.apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            container.addView(testingView)
            addView(container)

            // Mock UI displays for testing
            addView(createMockTextDisplays())
        }
    }

    private fun createMockTextDisplays(): LinearLayout {
        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 10, 10, 10)
            setBackgroundColor(0xFF333333.toInt())
        }

        val title = TextView(context).apply {
            text = "📱 Mock UI Text Displays (mirrors MainActivity) - Copyable"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 5)
        }
        textContainer.addView(title)

        // Initialize mockDistanceText with proper styling and copyable features
        mockDistanceText.apply {
            text = "Distance: 50m"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(5, 8, 5, 8) // Increased padding for better touch targets
            setBackgroundColor(0xFF1A1A1A.toInt())
            minHeight = 48 // Minimum touch target size
        }
        textContainer.addView(mockDistanceText)

        // Initialize mockInstructionText with proper styling and copyable features
        mockInstructionText.apply {
            text = "Tap screen to set navigation target"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(5, 8, 5, 8) // Increased padding for better touch targets
            setBackgroundColor(0xFF1A1A1A.toInt())
            minHeight = 48 // Minimum touch target size
        }
        textContainer.addView(mockInstructionText)

        return textContainer
    }

    private fun createControlPanelsSection(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 15, 0, 0)

            addView(createControlTabs())
            addView(createActiveControlPanel())
        }
    }

    private fun createControlTabs(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(5, 5, 5, 5)
            setBackgroundColor(0xFF444444.toInt())

            val tabs = listOf(
                "basic" to "🎮 Basic",
                "compass" to "🧭 Compass",
                "tests" to "🔬 Tests",
                "advanced" to "⚙️ Advanced"
            )

            tabs.forEach { (id, label) ->
                val tab = Button(context).apply {
                    text = label
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(0, 70, 1f)
                    setOnClickListener { callbacks.onSwitchTab(id) }
                    tag = "tab_$id"
                    setPadding(4, 8, 4, 8)

                    if (id == activeControlTab) {
                        setBackgroundColor(0xFF666666.toInt())
                    }
                }
                addView(tab)
            }
        }
    }

    private fun createActiveControlPanel(): FrameLayout {
        return FrameLayout(context).apply {
            tag = "activeControlPanel"
            setPadding(10, 10, 10, 10)
            setBackgroundColor(0xFF2A2A2A.toInt())

            addView(createBasicControlPanel())
            addView(createCompassControlPanel())
            addView(createTestsControlPanel())
            addView(createAdvancedControlPanel())
        }
    }

    private fun createBasicControlPanel(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "panel_basic"

            addView(createCompactButtonRow("Main Controls", listOf(
                "📍 Real GPS" to { callbacks.onRealGPS() },
                "🔄 Reset" to { callbacks.onReset() },
                "🔬 Quick Test" to { callbacks.onQuickTest() }
            )))

            addView(createCompactButtonRow("Movement Simulation", listOf(
                "↑ N 5m" to { callbacks.onMoveUser(0f, 5f) },
                "→ E 5m" to { callbacks.onMoveUser(90f, 5f) },
                "↓ S 5m" to { callbacks.onMoveUser(180f, 5f) },
                "← W 5m" to { callbacks.onMoveUser(270f, 5f) }
            )))

            addView(createCompactButtonRow("Scenarios", listOf(
                "🎯 Go to Target" to { callbacks.onJumpToTarget() },
                "🚶 Walk Path" to { callbacks.onSimulateWalkingPath() },
                "🤖 Auto Test" to { callbacks.onRunAutomaticMovementTest() }
            )))
        }
    }

    private fun createCompassControlPanel(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "panel_compass"
            visibility = View.GONE

            addView(createCompactButtonRow("Real Compass", listOf(
                "📱 Use Phone" to { callbacks.onUseRealCompass() },
                "🧭 Calibrate" to { callbacks.onStartCompassCalibration() },
                "📊 Test Accuracy" to { callbacks.onTestCompassAccuracy() }
            )))

            addView(createCompactButtonRow("Set Direction", listOf(
                "↑ N" to { callbacks.onSetMockCompass(0f) },
                "→ E" to { callbacks.onSetMockCompass(90f) },
                "↓ S" to { callbacks.onSetMockCompass(180f) },
                "← W" to { callbacks.onSetMockCompass(270f) }
            )))

            addView(createCompactButtonRow("Diagonal", listOf(
                "↗ NE" to { callbacks.onSetMockCompass(45f) },
                "↘ SE" to { callbacks.onSetMockCompass(135f) },
                "↙ SW" to { callbacks.onSetMockCompass(225f) },
                "↖ NW" to { callbacks.onSetMockCompass(315f) }
            )))
        }
    }

    private fun createTestsControlPanel(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "panel_tests"
            visibility = View.GONE

            addView(createCompactButtonRow("UI Tests", listOf(
                "📝 Text Display" to { callbacks.onRunTextVerificationTest() },
                "🍞 Breadcrumbs" to { callbacks.onRunBreadcrumbVerificationTest() },
                "🎯 Off-Track" to { callbacks.onTestOffTrackScenario() }
            )))

            addView(createCompactButtonRow("Compass Tests", listOf(
                "🧭 All Directions" to { callbacks.onTestAllCompassDirections() },
                "📐 Distances" to { callbacks.onTestDifferentDistances() },
                "🔄 Compare Methods" to { callbacks.onCompareCalculationMethods() }
            )))

            addView(createCompactButtonRow("Movement Tests", listOf(
                "👣 1m Step" to { callbacks.onSimulateShortMovement(1f) },
                "🚶 2m Walk" to { callbacks.onSimulateShortMovement(2f) },
                "🏃 5m Jog" to { callbacks.onSimulateShortMovement(5f) }
            )))

            addView(createCompactButtonRow("Complete Tests", listOf(
                "✅ Full Verify" to { callbacks.onRunFullVerificationTest() },
                "🚀 Start Fusion" to { callbacks.onStartSensorFusionTest() },
                "⏹️ Stop Fusion" to { callbacks.onStopSensorFusionTest() }
            )))
        }
    }

    private fun createAdvancedControlPanel(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "panel_advanced"
            visibility = View.GONE

            addView(createCompactButtonRow("Calibration", listOf(
                "🌐 Find North" to { callbacks.onHelpFindNorth() },
                "🔄 Figure-8" to { callbacks.onStartFigure8Calibration() },
                "🗺️ GPS Compare" to { callbacks.onCompareCompassWithGPS() }
            )))

            addView(createCompactButtonRow("Analysis", listOf(
                "⚖️ Real/Mock" to { callbacks.onStartCompassComparison() },
                "📊 Generate Report" to { callbacks.onGenerateFullTestReport() },
                "💾 Export Data" to { callbacks.onExportTestData() }
            )))
        }
    }

    private fun createCompactButtonRow(title: String, buttons: List<Pair<String, () -> Unit>>): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)

            addView(TextView(context).apply {
                text = title
                textSize = 11f
                setTextColor(0xFFCCCCCC.toInt())
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, 4)
            })

            val buttonRows = buttons.chunked(3)
            buttonRows.forEach { rowButtons ->
                val buttonRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                rowButtons.forEach { (text, action) ->
                    val button = Button(context).apply {
                        this.text = text
                        textSize = 10f
                        layoutParams = LinearLayout.LayoutParams(0, 80, 1f).apply {
                            setMargins(3, 3, 3, 3)
                        }
                        setOnClickListener { action() }
                        setPadding(4, 8, 4, 8)
                    }
                    buttonRow.addView(button)
                }

                addView(buttonRow)
            }
        }
    }

    fun switchToTab(tabId: String) {
        activeControlTab = tabId

        // Update tab appearances
        val root = context.findViewById<View>(android.R.id.content) as ViewGroup
        updateTabAppearances(root, tabId)

        // Show/hide panels
        val panelContainer = findViewByTag<FrameLayout>("activeControlPanel")
        panelContainer?.let { container ->
            for (i in 0 until container.childCount) {
                val panel = container.getChildAt(i)
                panel.visibility = if (panel.tag == "panel_$tabId") View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateTabAppearances(viewGroup: ViewGroup, activeTabId: String) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child.tag?.toString()?.startsWith("tab_") == true) {
                child.setBackgroundColor(
                    if (child.tag == "tab_$activeTabId") 0xFF666666.toInt() else 0xFF444444.toInt()
                )
            }
            if (child is ViewGroup) {
                updateTabAppearances(child, activeTabId)
            }
        }
    }

    fun toggleStatusDisplays() {
        val statusSection = findViewByTag<LinearLayout>("statusSection")
        val expandButton = findViewByTag<Button>("expandButton")

        statusSection?.let { section ->
            if (section.visibility == View.GONE) {
                section.visibility = View.VISIBLE
                expandButton?.text = "📈 Hide Details"
            } else {
                section.visibility = View.GONE
                expandButton?.text = "📊 Show Details"
            }
        }
    }

    fun updateQuickStatus(compassStatus: String, gpsStatus: String, fusionStatus: String) {
        val root = context.findViewById<View>(android.R.id.content) as ViewGroup
        val quickStatusView = findViewByTagRecursive(root, "quickStatus") as? TextView
        quickStatusView?.text = "📱 Compass: $compassStatus | 📍 GPS: $gpsStatus | 🔧 Fusion: $fusionStatus"
    }

    private fun <T : View> findViewByTag(tag: String): T? {
        val root = context.findViewById<View>(android.R.id.content) as? ViewGroup ?: return null
        val foundView = findViewByTagRecursive(root, tag) ?: return null

        return try {
            @Suppress("UNCHECKED_CAST")
            foundView as T
        } catch (e: ClassCastException) {
            null
        }
    }

    private fun findViewByTagRecursive(parent: ViewGroup, tag: String): View? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.tag == tag) {
                return child
            }
            if (child is ViewGroup) {
                val found = findViewByTagRecursive(child, tag)
                if (found != null) return found
            }
        }
        return null
    }
}