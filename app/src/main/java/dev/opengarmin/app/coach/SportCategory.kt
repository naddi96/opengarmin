package dev.opengarmin.app.coach

enum class SportCategory(val label: String, val sports: Set<Int>) {
    Run("Run", setOf(1)),
    Ride("Ride", setOf(2, 21)),
    Cardio("Cardio", emptySet()),
    Strength("Strength", setOf(4, 62)),
    Walk("Walk", setOf(11, 17)),
    Other("Other", emptySet()),
}

/**
 * Maps a FIT sport/subSport pair to the filter used by the Activities tab.
 *
 * Garmin records its "Cardio" activity as sport 10 (training) + subSport 26
 * (cardio_training). Treating all of sport 10 as strength hid those sessions
 * behind the wrong filter, so sub-sports are part of the mapping now.
 */
fun categoryForSport(sport: Int, subSport: Int = 0): SportCategory = when {
    sport == 10 && subSport == 26 -> SportCategory.Cardio
    sport == 10 && subSport == 20 -> SportCategory.Strength
    else -> SportCategory.entries.firstOrNull { sport in it.sports } ?: SportCategory.Other
}

fun sportLabel(sport: Int): String = when (sport) {
    0 -> "Generic"
    1 -> "Running"
    2 -> "Cycling"
    3 -> "Transition"
    4 -> "Strength"
    5 -> "Swimming"
    6 -> "Basketball"
    7 -> "Soccer"
    8 -> "Tennis"
    10 -> "Training"
    11 -> "Walking"
    12 -> "Cross-country skiing"
    13 -> "Alpine skiing"
    14 -> "Snowboarding"
    15 -> "Rowing"
    16 -> "Mountaineering"
    17 -> "Hiking"
    18 -> "Multisport"
    19 -> "Paddling"
    21 -> "E-biking"
    25 -> "Golf"
    30 -> "Inline skating"
    31 -> "Rock climbing"
    32 -> "Sailing"
    35 -> "Snowshoeing"
    37 -> "Stand-up paddleboarding"
    38 -> "Surfing"
    41 -> "Kayaking"
    43 -> "Windsurfing"
    44 -> "Kitesurfing"
    53 -> "Diving"
    62 -> "HIIT"
    71 -> "Disc golf"
    79 -> "Mixed martial arts"
    else -> "Sport $sport"
}
