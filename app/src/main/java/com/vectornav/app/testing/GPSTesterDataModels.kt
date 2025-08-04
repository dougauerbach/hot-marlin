package com.vectornav.app.testing

import android.graphics.PointF

// Movement tracking data classes
data class MovementRecord(
    val timestamp: Long,
    val expectedPos: PointF,
    val actualPos: PointF,
    val shouldMove: Boolean,
    val actuallyMoved: Boolean,
    val movementDistance: Float
)

data class TextVerificationRecord(
    val timestamp: Long,
    val scenario: String,
    val expectedText: String,
    val actualText: String,
    val textMatches: Boolean,
    val colorCorrect: Boolean
)

data class BreadcrumbVerificationRecord(
    val timestamp: Long,
    val expectedBreadcrumbs: Int,
    val actualBreadcrumbs: Int,
    val breadcrumbsMatch: Boolean,
    val renderingWorking: Boolean
)

data class CompassVerificationRecord(
    val timestamp: Long,
    val startLat: Double,
    val startLon: Double,
    val compassAzimuth: Float,
    val targetDistance: Int,
    val expectedDestLat: Double,
    val expectedDestLon: Double,
    val actualDestLat: Double,
    val actualDestLon: Double,
    val destinationAccurate: Boolean,
    val distanceError: Float,
    val bearingError: Float
)

data class CompassCalibrationRecord(
    val timestamp: Long,
    val realAzimuth: Float,
    val mockAzimuth: Float,
    val difference: Float,
    val phoneVertical: Boolean,
    val accuracy: Int
)

data class BreadcrumbPoint(
    val lat: Double,
    val lon: Double,
    val timestamp: Long,
    val distanceFromStart: Float = 0f,
    val accuracy: Float = 0f
)