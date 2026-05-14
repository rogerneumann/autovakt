package com.rogerneumann.vakt.util

import kotlin.math.*

object SunriseSunsetCalculator {

    /**
     * Returns (sunriseHour, sunsetHour) as local decimal hours (e.g. 6.5 = 6:30 AM),
     * or null if polar day/night (sun never rises or never sets).
     *
     * @param lat          latitude in degrees, north positive
     * @param lon          longitude in degrees, east positive
     * @param utcOffsetHours  local UTC offset in hours (e.g. -5 for EST, +1 for CET)
     */
    fun getSunriseSunset(lat: Double, lon: Double, utcOffsetHours: Double): Pair<Double, Double>? {
        val now = java.util.Calendar.getInstance()
        val year  = now.get(java.util.Calendar.YEAR)
        val month = now.get(java.util.Calendar.MONTH) + 1
        val day   = now.get(java.util.Calendar.DAY_OF_MONTH)

        val jd = julianDay(year, month, day)
        val jc = (jd - 2451545.0) / 36525.0

        val geomMeanLonSun = (280.46646 + jc * (36000.76983 + jc * 0.0003032)) % 360.0
        val geomMeanAnomSun = 357.52911 + jc * (35999.05029 - 0.0001537 * jc)
        val eccentOrbit = 0.016708634 - jc * (0.000042037 + 0.0000001267 * jc)

        val eqOfCtrRad = Math.toRadians(geomMeanAnomSun)
        val eqOfCtr = sin(eqOfCtrRad) * (1.914602 - jc * (0.004817 + 0.000014 * jc)) +
                sin(2 * eqOfCtrRad) * (0.019993 - 0.000101 * jc) +
                sin(3 * eqOfCtrRad) * 0.000289

        val sunTrueLon = geomMeanLonSun + eqOfCtr
        val sunAppLon  = sunTrueLon - 0.00569 - 0.00478 * sin(Math.toRadians(125.04 - 1934.136 * jc))

        val meanObliqEcliptic = 23.0 + (26.0 + ((21.448 - jc * (46.8150 + jc * (0.00059 - jc * 0.001813)))) / 60.0) / 60.0
        val obliqCorr = meanObliqEcliptic + 0.00256 * cos(Math.toRadians(125.04 - 1934.136 * jc))

        val declinRad = asin(sin(Math.toRadians(obliqCorr)) * sin(Math.toRadians(sunAppLon)))
        val declin    = Math.toDegrees(declinRad)

        val y = tan(Math.toRadians(obliqCorr / 2)).pow(2)
        val eot = 4.0 * Math.toDegrees(
            y * sin(2 * Math.toRadians(geomMeanLonSun)) -
            2 * eccentOrbit * sin(Math.toRadians(geomMeanAnomSun)) +
            4 * eccentOrbit * y * sin(Math.toRadians(geomMeanAnomSun)) * cos(2 * Math.toRadians(geomMeanLonSun)) -
            0.5 * y * y * sin(4 * Math.toRadians(geomMeanLonSun)) -
            1.25 * eccentOrbit * eccentOrbit * sin(2 * Math.toRadians(geomMeanAnomSun))
        )

        // Solar noon in minutes from midnight UTC
        val solarNoon = 720.0 - 4.0 * lon - eot

        // Hour angle for sunrise/sunset (-0.833° accounts for refraction + solar disc)
        val latRad  = Math.toRadians(lat)
        val cosHA   = (sin(Math.toRadians(-0.8333)) - sin(latRad) * sin(declinRad)) /
                      (cos(latRad) * cos(declinRad))

        if (cosHA < -1.0) return null  // polar day (sun never sets)
        if (cosHA > 1.0)  return null  // polar night (sun never rises)

        val haDeg = Math.toDegrees(acos(cosHA))

        val sunriseUtcMin = solarNoon - haDeg * 4.0
        val sunsetUtcMin  = solarNoon + haDeg * 4.0

        val sunriseLocal = sunriseUtcMin / 60.0 + utcOffsetHours
        val sunsetLocal  = sunsetUtcMin  / 60.0 + utcOffsetHours

        return Pair(sunriseLocal.coerceIn(0.0, 24.0), sunsetLocal.coerceIn(0.0, 24.0))
    }

    private fun julianDay(year: Int, month: Int, day: Int): Double {
        val a = (14 - month) / 12
        val y = year + 4800 - a
        val m = month + 12 * a - 3
        return day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045.0
    }
}
