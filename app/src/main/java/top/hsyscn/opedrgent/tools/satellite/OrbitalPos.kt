package top.hsyscn.opedrgent.tools.satellite

// Orbital position result data class.
// Algorithm source: PREDICT v2.2.5, ported from Look4Sat by Arty Bishop.
// Look4Sat is licensed under GPL-3.0 (https://github.com/rt-bishop/Look4Sat).

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class OrbitalPos(
    var azimuth: Double = 0.0,
    var elevation: Double = 0.0,
    var latitude: Double = 0.0,
    var longitude: Double = 0.0,
    var altitude: Double = 0.0,
    var distance: Double = 0.0,
    var distanceRate: Double = 0.0,
    var theta: Double = 0.0,
    var time: Long = 0L,
    var phase: Double = 0.0,
    var eclipseDepth: Double = 0.0,
    var eclipsed: Boolean = false,
    var aboveHorizon: Boolean = false
) {

    fun getDownlinkFreq(freq: Long): Long {
        return (freq.toDouble() * (SPEED_OF_LIGHT - distanceRate * 1000.0) / SPEED_OF_LIGHT).toLong()
    }

    fun getUplinkFreq(freq: Long): Long {
        return (freq.toDouble() * (SPEED_OF_LIGHT + distanceRate * 1000.0) / SPEED_OF_LIGHT).toLong()
    }

    fun getOrbitalVelocity(): Double {
        val radius = EARTH_RADIUS_M + altitude * 1000.0
        return sqrt(GM_EARTH / radius) / 1000.0
    }

    fun getRangeCircle(): List<GeoPos> {
        val pointCount = 721
        val rangeCirclePoints = ArrayList<GeoPos>(pointCount)
        val beta = acos(EARTH_RADIUS / (EARTH_RADIUS + altitude))
        val sinLat = sin(latitude)
        val cosLat = cos(latitude)
        val cosBeta = cos(beta)
        val sinBeta = sin(beta)
        for (azimuth in 0..720) {
            val rads = azimuth * DEG2RAD
            val lat = asin(sinLat * cosBeta + cosLat * sinBeta * cos(rads))
            val lon = longitude + atan2(sin(rads) * sinBeta * cosLat, cosBeta - sinLat * sin(lat))
            rangeCirclePoints.add(GeoPos(lat * RAD2DEG, lon * RAD2DEG))
        }
        return rangeCirclePoints
    }

    companion object {
        // Pre-computed constants for orbital velocity calculation
        private const val GM_EARTH = 3.986004418E14 // m^3/s^2
        private const val EARTH_RADIUS_M = 6.37E6 // meters
    }
}
