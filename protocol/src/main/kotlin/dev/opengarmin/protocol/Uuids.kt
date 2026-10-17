package dev.opengarmin.protocol

/** Garmin BLE UUIDs and Multi-Link service ids (recovered from the APK). */
object Uuids {
    const val BASE_SUFFIX = "-667B-11E3-949A-0800200C9A66"
    fun garmin(short: Int): String = "6A4E%04X%s".format(short, BASE_SUFFIX)

    /** Multi-Link service. */
    val MULTI_LINK = garmin(0x2800)
    val ML_CONTROL = garmin(0x2803)

    /** ML characteristic pairs: you write to `WRITE[i]` and notify on `READ[i]`. */
    val ML_READ = intArrayOf(0x2810, 0x2811, 0x2812, 0x2813, 0x2814).map { garmin(it) }
    val ML_WRITE = intArrayOf(0x2820, 0x2821, 0x2822, 0x2823, 0x2824).map { garmin(it) }

    /** Legacy GFDI v1 service / characteristic. */
    val GFDI_V1_SERVICE = garmin(0x2401)
    val GFDI_V1_CHAR = garmin(0x2403)

    /** GFDI-era dedicated service (write/notify). */
    val GFDI_DEDICATED_SERVICE = garmin(0x8022)
    val GFDI_DEDICATED_WRITE = garmin(0x4C80)
    val GFDI_DEDICATED_READ = garmin(0xCD28)

    /** Standard SIG services of interest. */
    const val SIG_DEVICE_INFORMATION = "0000180A-0000-1000-8000-00805F9B34FB"
    const val SIG_BATTERY = "0000180F-0000-1000-8000-00805F9B34FB"
    const val SIG_HEART_RATE = "0000180D-0000-1000-8000-00805F9B34FB"
    const val SIG_RUNNING_SPEED_CADENCE = "00001814-0000-1000-8000-00805F9B34FB"
    const val GARMIN_VENDOR_16BIT = "0000FE1F-0000-1000-8000-00805F9B34FB"
}

/** Multi-Link logical service ids. */
object ServiceId {
    const val GFDI = 0x0001
    const val NFC = 0x0002
    const val HEALTH_SDK = 0x0003
    const val REGISTRATION = 0x0004
    const val CONNEXT = 0x0005
    const val REAL_TIME_HR = 0x0006
    const val REAL_TIME_STEPS = 0x0007
    const val REAL_TIME_CALORIES = 0x0008
    const val REAL_TIME_FLOORS = 0x0009
    const val REAL_TIME_INTENSITY = 0x000A
    const val REAL_TIME_HRV = 0x000C
    const val REAL_TIME_STRESS = 0x000D
    const val AUTH_STATUS = 0x000E
    const val ECHO = 0x000F
    const val REAL_TIME_ACCELEROMETER = 0x0010
    const val REAL_TIME_SPO2 = 0x0013
    const val REAL_TIME_BODY_BATTERY = 0x0014
    const val REAL_TIME_RESPIRATION = 0x0015
    const val KEEP_ALIVE = 0x0016
    const val REAL_TIME_ACTIVE_TIME = 0x001A
}
