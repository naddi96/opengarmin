package dev.opengarmin.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Palette extracted from docs/design/tokens.md. */
object PulseColors {
    val Background = Color(0xFF0E1311)
    val Surface = Color(0xFF161D1A)
    val SurfaceElevated = Color(0xFF1E2723)
    val Border = Color(0xFF2A3530)
    val BorderElevated = Color(0xFF2F3F38)

    /** Higher-contrast border for control boundaries (WCAG non-text 3:1). */
    val BorderStrong = Color(0xFF6B7C73)
    val RingTrack = Color(0xFF26322D)
    val GoalLine = Color(0xFF5E6F66)

    val Text = Color(0xFFEAF1EC)
    val TextMuted = Color(0xFF8FA096)
    val TextOnAccent = Color(0xFF0E1311)

    val Accent = Color(0xFFC7F26B)
    val Teal = Color(0xFF5ED6C1)
    val Coral = Color(0xFFFF7A66)
    val Blue = Color(0xFF8FA8FF)
    val Orange = Color(0xFFFFB45E)
    val Yellow = Color(0xFFFFD166)
    val Zone4 = Color(0xFFFF9F5E)
    val Zone5 = Color(0xFFFF6B57)

    val AccentMuted = Color(0x61C7F26B)
    val CoralMuted = Color(0x24FF7A66)

    val SportRun = Coral
    val SportRide = Teal
    val SportCardio = Orange
    val SportStrength = Blue
    val SportWalk = Accent

    val ZoneColors = listOf(Teal, Accent, Yellow, Zone4, Zone5)

    fun sportColor(sport: Int, subSport: Int = 0): Color = when {
        sport == 10 && subSport == 26 -> SportCardio
        sport in setOf(4, 62) || (sport == 10 && subSport == 20) -> SportStrength
        sport == 1 -> SportRun
        sport in setOf(2, 21) -> SportRide
        sport in setOf(9, 11, 17) -> SportWalk
        else -> TextMuted
    }
}
