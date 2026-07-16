package top.hsyscn.opedrgent.tools.satellite

// TLE orbital data wrapper with deep-space classification.
// Algorithm source: PREDICT v2.2.5, ported from Look4Sat by Arty Bishop.
// Look4Sat is licensed under GPL-3.0 (https://github.com/rt-bishop/Look4Sat).

data class OrbitalData(
    val name: String,
    val epoch: Double,
    val meanmo: Double,
    val eccn: Double,
    val incl: Double,
    val raan: Double,
    val argper: Double,
    val meanan: Double,
    val catnum: Int,
    val bstar: Double,
    val ndot: Double = 0.0
) {
    val xincl: Double = incl * DEG2RAD
    val xnodeo: Double = raan * DEG2RAD
    val omegao: Double = argper * DEG2RAD
    val xmo: Double = meanan * DEG2RAD
    val xno: Double = meanmo * TWO_PI / MIN_PER_DAY
    val orbitalPeriod: Double = MIN_PER_DAY / meanmo
    val isDeepSpace: Boolean = orbitalPeriod >= 225.0 // NearEarth (period < 225 min) or DeepSpace (period >= 225 min)
    fun getObject(): OrbitalObject = if (isDeepSpace) DeepSpaceObject(this) else NearEarthObject(this)
}
