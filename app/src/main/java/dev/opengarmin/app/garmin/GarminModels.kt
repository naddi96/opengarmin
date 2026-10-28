package dev.opengarmin.app.garmin

/**
 * One activity row from the Garmin Connect activity list.
 *
 * The unofficial Garmin API reports distance in metres and duration in
 * seconds. The label helpers keep the Compose layer free of formatting rules.
 */
data class GarminActivity(
    val id: String,
    val name: String,
    val startTimeLocal: String,
    val sport: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
) {
    val displayTitle: String
        get() = name.ifBlank { sport.replaceFirstChar { it.uppercase() }.ifBlank { "Activity" } }

    val displayDate: String
        get() = startTimeLocal.take(16).replace('T', ' ').ifBlank { "Unknown date" }

    val displayDistance: String
        get() = if (distanceMeters > 0) "%.2f km".format(distanceMeters / 1000.0) else ""

    val displayDuration: String
        get() {
            val seconds = durationSeconds.toLong().coerceAtLeast(0)
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            return if (hours > 0) "%d:%02d h".format(hours, minutes) else "%d min".format(minutes)
        }
}

/**
 * Tokens returned by Garmin's DI OAuth2 service. Only these values are stored;
 * the account password is used once for the SSO exchange and then discarded.
 */
data class GarminTokens(
    val accessToken: String,
    val refreshToken: String?,
    val clientId: String,
    val expiresAtMillis: Long,
)

sealed interface GarminLoginResult {
    data class Success(val tokens: GarminTokens) : GarminLoginResult

    /** The account has MFA enabled; call [GarminConnectClient.completeMfa]. */
    data class NeedsMfa(val method: String) : GarminLoginResult
}

/** Persistence seam so the HTTP client can be unit-tested without Android. */
interface GarminTokenStore {
    fun load(): GarminTokens?
    fun save(tokens: GarminTokens)
    fun clear()
}

class GarminException(
    val kind: Kind,
    override val message: String,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        NotSignedIn,
        Auth,
        RateLimit,
        Blocked,
        Network,
        Server,
        Malformed,
    }
}
