package dev.opengarmin.protocol

/**
 * Names and scales for Garmin-proprietary FIT messages/fields that are not in
 * the public FIT SDK profile.
 *
 * These values were recovered from files produced by the watch itself and
 * cross-checked against the FIT File Viewer community profile:
 * `activity_metrics` (140) and `user_metrics` (79) carry the training metrics
 * (VO2 max, recovery time, lactate threshold, training load/effect, body
 * battery), while `stress_level` (227) field 3 and `record` (20) field 143
 * carry Body Battery.
 *
 * Keeping this overlay separate from [FitProfile] makes it obvious which
 * mappings are official and which are reverse-engineered. Unknown messages and
 * fields still fall back to `msg_<n>` / `field_<n>`.
 */
object GarminProfile {
    fun messageName(number: Int): String? = when (number) {
        79 -> "user_metrics"
        140 -> "activity_metrics"
        310 -> "pace_band"
        311 -> "pace_band_split"
        else -> null
    }

    fun field(message: Int, number: Int): FitProfile.FieldInfo? = when (message) {
        20 -> recordFields[number]
        79 -> userMetricFields[number]
        140 -> activityMetricFields[number]
        227 -> stressFields[number]
        else -> null
    }

    /** `record` extension used during activities. */
    private val recordFields = mapOf(
        143 to FitProfile.FieldInfo("body_battery", "percent", 1.0, 0.0),
    )

    /** `stress_level` extension. Field 3 is the current Body Battery level. */
    private val stressFields = mapOf(
        2 to FitProfile.FieldInfo("stress_level_value_raw", null, 1.0, 0.0),
        3 to FitProfile.FieldInfo("body_battery", "percent", 1.0, 0.0),
    )

    /** Firstbeat/Garmin metrics recorded once at the end of an activity. */
    private val activityMetricFields = mapOf(
        1 to FitProfile.FieldInfo("new_hr_max", "bpm", 1.0, 0.0),
        4 to FitProfile.FieldInfo("aerobic_training_effect", null, 10.0, 0.0),
        7 to FitProfile.FieldInfo("vo2_max", "ml/kg/min", 18724.571428571428, 0.0),
        9 to FitProfile.FieldInfo("recovery_time", "min", 1.0, 0.0),
        14 to FitProfile.FieldInfo("lthr", "bpm", 1.0, 0.0),
        15 to FitProfile.FieldInfo("ltpower", "watts", 1.0, 0.0),
        16 to FitProfile.FieldInfo("ltspeed", "km/h", 10.0, 0.0),
        17 to FitProfile.FieldInfo("final_performance_condition", null, 1.0, 0.0),
        20 to FitProfile.FieldInfo("anaerobic_training_effect", null, 10.0, 0.0),
        25 to FitProfile.FieldInfo("final_body_battery", "percent", 1.0, 0.0),
        29 to FitProfile.FieldInfo("first_vo2_max", "ml/kg/min", 18724.571428571428, 0.0),
        35 to FitProfile.FieldInfo("timer_time", "s", 1000.0, 0.0),
        36 to FitProfile.FieldInfo("distance", "m", 100.0, 0.0),
        37 to FitProfile.FieldInfo("training_load_peak_1", null, 65536.0, 0.0),
        39 to FitProfile.FieldInfo("training_load_peak_2", null, 65536.0, 0.0),
        41 to FitProfile.FieldInfo("primary_benefit", null, 1.0, 0.0),
        48 to FitProfile.FieldInfo("local_timestamp", "s", 1.0, 0.0),
        50 to FitProfile.FieldInfo("ending_potential", "percent", 1.0, 0.0),
        60 to FitProfile.FieldInfo("total_ascent", "m", 1.0, 0.0),
        61 to FitProfile.FieldInfo("total_descent", "m", 1.0, 0.0),
        62 to FitProfile.FieldInfo("average_power", "watts", 1.0, 0.0),
        63 to FitProfile.FieldInfo("average_heartrate", "bpm", 1.0, 0.0),
    )

    /** User/profile and training snapshot recorded once per activity. */
    private val userMetricFields = mapOf(
        0 to FitProfile.FieldInfo("vo2_max", "ml/kg/min", 292.57142857142856, 0.0),
        1 to FitProfile.FieldInfo("age", "years", 1.0, 0.0),
        2 to FitProfile.FieldInfo("height", "m", 100.0, 0.0),
        3 to FitProfile.FieldInfo("weight", "kg", 10.0, 0.0),
        4 to FitProfile.FieldInfo("gender", null, 1.0, 0.0),
        5 to FitProfile.FieldInfo("activity_class", null, 1.0, 0.0),
        6 to FitProfile.FieldInfo("max_hr", "bpm", 1.0, 0.0),
        8 to FitProfile.FieldInfo("remaining_recovery_time", "min", 1.0, 0.0),
        11 to FitProfile.FieldInfo("lthr", "bpm", 1.0, 0.0),
        12 to FitProfile.FieldInfo("ltpower", "watts", 1.0, 0.0),
        13 to FitProfile.FieldInfo("ltspeed", "km/h", 10.0, 0.0),
        15 to FitProfile.FieldInfo("initial_body_battery", "percent", 1.0, 0.0),
        16 to FitProfile.FieldInfo("start_of_activity", "s", 1.0, 0.0),
        19 to FitProfile.FieldInfo("first_vo2_max", "ml/kg/min", 18724.571428571428, 0.0),
        32 to FitProfile.FieldInfo("beginning_potential", "percent", 1.0, 0.0),
        35 to FitProfile.FieldInfo("end_of_previous_activity", "s", 1.0, 0.0),
        39 to FitProfile.FieldInfo("wake_up_time", "s", 1.0, 0.0),
    )
}
