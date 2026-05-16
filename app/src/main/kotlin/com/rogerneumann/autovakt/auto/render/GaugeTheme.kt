package com.rogerneumann.autovakt.auto.render

import android.graphics.Color

data class GaugeTheme(
    val background: Int,
    val text: Int,
    val textSecondary: Int,
    val arcTrack: Int,
    val cardBackground: Int,
    val gridBarTrack: Int,
    val accent: Int,
    val pillActiveBg: Int,
    val pillActiveFg: Int,
    val pillInactiveBg: Int,
    val pillInactiveFg: Int,
    val dotActive: Int,
    val dotInactive: Int
) {
    companion object {
        val DARK = GaugeTheme(
            background      = Color.BLACK,
            text            = Color.WHITE,
            textSecondary   = Color.parseColor("#888888"),
            arcTrack        = Color.DKGRAY,
            cardBackground  = Color.parseColor("#1A1A1A"),
            gridBarTrack    = Color.parseColor("#2A2A2A"),
            accent          = Color.parseColor("#00E676"),
            pillActiveBg    = Color.WHITE,
            pillActiveFg    = Color.BLACK,
            pillInactiveBg  = Color.parseColor("#3A3A3A"),
            pillInactiveFg  = Color.parseColor("#999999"),
            dotActive       = Color.WHITE,
            dotInactive     = Color.parseColor("#666666")
        )

        val LIGHT = GaugeTheme(
            background      = Color.parseColor("#F2F2F2"),
            text            = Color.parseColor("#1A1A1A"),
            textSecondary   = Color.parseColor("#555555"),
            arcTrack        = Color.LTGRAY,
            cardBackground  = Color.parseColor("#E0E0E0"),
            gridBarTrack    = Color.parseColor("#CCCCCC"),
            accent          = Color.parseColor("#00897B"),
            pillActiveBg    = Color.parseColor("#1A1A1A"),
            pillActiveFg    = Color.WHITE,
            pillInactiveBg  = Color.parseColor("#CCCCCC"),
            pillInactiveFg  = Color.parseColor("#555555"),
            dotActive       = Color.parseColor("#1A1A1A"),
            dotInactive     = Color.parseColor("#AAAAAA")
        )
    }
}
