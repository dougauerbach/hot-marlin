package com.vectornav.app

import android.location.Location
import kotlin.math.*

class NavigationCalculator {

    fun calculateBearing(startLat: Double, startLon: Double, endLat: Double, endLon: Double): Float {
        val startLatRad = Math.toRadians(startLat)
        val startLonRad = Math.toRadians(startLon)
        val endLatRad = Math.toRadians(endLat)
        val endLonRad = Math.toRadians(endLon)

        val deltaLon = endLonRad - startLonRad

        val y = sin(deltaLon) * cos(endLatRad)
        val x = cos(startLatRad) * sin(endLatRad) - sin(startLatRad) * cos(endLatRad) * cos(deltaLon)

        var bearing = Math.toDegrees(atan2(y, x))
        bearing = (bearing + 360) % 360 // Normalize to 0-360

        return bearing.toFloat()
    }

    fun calculateDistance(startLat: Double, startLon: Double, endLat: Double, endLon: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(startLat, startLon, endLat, endLon, results)
        return results[0]
    }

    fun calculateDestination(startLat: Double, startLon: Double, bearing: Float, distance: Float): Pair<Double, Double> {
        val earthRadius = 6371000.0 // Earth radius in meters
        val bearingRad = Math.toRadians(bearing.toDouble())
        val startLatRad = Math.toRadians(startLat)
        val startLonRad = Math.toRadians(startLon)

        val endLatRad = asin(
            sin(startLatRad) * cos(distance / earthRadius) +
                    cos(startLatRad) * sin(distance / earthRadius) * cos(bearingRad)
        )

        val endLonRad = startLonRad + atan2(
            sin(bearingRad) * sin(distance / earthRadius) * cos(startLatRad),
            cos(distance / earthRadius) - sin(startLatRad) * sin(endLatRad)
        )

        val endLat = Math.toDegrees(endLatRad)
        val endLon = Math.toDegrees(endLonRad)

        return Pair(endLat, endLon)
    }

    fun calculateCrossTrackError(
        currentLat: Double,
        currentLon: Double,
        startLat: Double,
        startLon: Double,
        targetBearing: Float
    ): Float {
        // Calculate the perpendicular distance from current position to the target bearing line
        val currentBearing = calculateBearing(startLat, startLon, currentLat, currentLon)
        val distance = calculateDistance(startLat, startLon, currentLat, currentLon)

        val bearingDiff = Math.toRadians((currentBearing - targetBearing).toDouble())
        return (distance * sin(bearingDiff)).toFloat()
    }
}