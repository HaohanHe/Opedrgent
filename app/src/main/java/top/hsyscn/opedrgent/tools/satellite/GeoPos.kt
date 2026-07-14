package top.hsyscn.opedrgent.tools.satellite

// Geodetic position data class.
// Algorithm source: PREDICT v2.2.5, ported from Look4Sat by Arty Bishop.

data class GeoPos(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val qthLocator: String = "null",
    val timestamp: Long = 0L
)
