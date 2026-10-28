package dev.opengarmin.app.garmin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Minimal Garmin Connect client for the unofficial mobile API.
 *
 * Authentication follows the official mobile apps: mobile SSO with the
 * `GCM_IOS_DARK` client, then a service-ticket exchange for DI OAuth2 tokens.
 * Only the access/refresh token pair reaches [GarminTokenStore]; the password
 * stays in memory for the length of the login.
 *
 * The class keeps no Android dependencies so the HTTP flow can be covered by
 * JVM tests with MockWebServer.
 */
class GarminConnectClient(
    private val tokenStore: GarminTokenStore,
    baseUrl: String = DEFAULT_API_URL,
    ssoUrl: String = DEFAULT_SSO_URL,
    tokenUrl: String = DEFAULT_TOKEN_URL,
    serviceUrl: String = DEFAULT_SERVICE_URL,
    private val clientIds: List<String> = DEFAULT_CLIENT_IDS,
    private val loginPageDelayMillis: Long = 2_000,
    private val downloadDelayMillis: Long = 1_200,
    private val okHttpClient: OkHttpClient = defaultHttpClient(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val apiBase = baseUrl.trimEnd('/')
    private val ssoBase = ssoUrl.trimEnd('/')
    private val tokenEndpoint = tokenUrl
    private val service = serviceUrl.trimEnd('/')
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val downloadMutex = Mutex()
    private var lastDownloadAtMillis = 0L
    private var pendingMfa: PendingMfa? = null

    suspend fun login(username: String, password: String): GarminLoginResult {
        val email = username.trim()
        if (email.isBlank() || password.isBlank()) {
            throw GarminException(
                GarminException.Kind.Auth,
                "Enter your Garmin email and password.",
            )
        }
        pendingMfa = null

        val signInUrl = "$ssoBase/mobile/sso/en_US/sign-in".toHttpUrl().newBuilder()
            .addQueryParameter("clientId", IOS_CLIENT_ID)
            .addQueryParameter("service", service)
            .build()
        val page = call(
            Request.Builder()
                .url(signInUrl)
                .header("User-Agent", IOS_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build(),
        )
        when (page.code) {
            429 -> throw rateLimited("Garmin rate-limited the sign-in page.", page.retryAfterSeconds)
            403 -> throw GarminException(
                GarminException.Kind.Blocked,
                "Garmin blocked this sign-in request (HTTP 403). Try again later.",
            )
            in 500..599 -> throw GarminException(
                GarminException.Kind.Server,
                "Garmin sign-in is unavailable right now (HTTP ${page.code}).",
            )
        }

        // Garmin's WAF treats an immediate GET→POST as bot traffic. The mobile
        // app pauses before submitting the form; mirror that.
        if (loginPageDelayMillis > 0) delay(loginPageDelayMillis)

        val params = mapOf(
            "clientId" to IOS_CLIENT_ID,
            "locale" to "en-US",
            "service" to service,
        )
        val response = call(loginRequest(params, signInUrl.toString(), email, password))
        return handleLoginResponse(response, params, signInUrl.toString())
    }

    suspend fun completeMfa(code: String): GarminTokens {
        val pending = pendingMfa ?: throw GarminException(
            GarminException.Kind.Auth,
            "Start the Garmin sign-in again before entering a verification code.",
        )
        if (code.isBlank()) {
            throw GarminException(GarminException.Kind.Auth, "Enter the verification code from Garmin.")
        }
        val url = "$ssoBase/mobile/api/mfa/verifyCode".toHttpUrl().newBuilder().apply {
            pending.params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val body = JSONObject().apply {
            put("mfaMethod", pending.method)
            put("mfaVerificationCode", code.trim())
            put("rememberMyBrowser", true)
            put("reconsentList", JSONArray())
            put("mfaSetup", false)
        }
        val response = call(
            Request.Builder()
                .url(url)
                .header("User-Agent", IOS_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", ssoBase)
                .header("Referer", pending.referer)
                .post(body.toString().toRequestBody(jsonMediaType))
                .build(),
        )
        if (response.code == 429) {
            throw rateLimited("Garmin rate-limited the verification code.", response.retryAfterSeconds)
        }
        val json = parseJson(response.body)
            ?: throw GarminException(
                GarminException.Kind.Malformed,
                "Garmin returned an unexpected MFA response.",
            )
        if (json.optJSONObject("responseStatus")?.optString("type") != "SUCCESSFUL") {
            throw GarminException(
                GarminException.Kind.Auth,
                "Garmin did not accept the verification code.",
            )
        }
        val ticket = json.optString("serviceTicketId").takeIf { it.isNotBlank() }
            ?: throw GarminException(
                GarminException.Kind.Malformed,
                "Garmin did not return a service ticket.",
            )
        pendingMfa = null
        return exchangeServiceTicket(ticket)
    }

    /** Newest-first activity list from Garmin Connect. */
    suspend fun recentActivities(limit: Int = 20): List<GarminActivity> {
        val safeLimit = limit.coerceIn(1, 100)
        val url = "$apiBase/activitylist-service/activities/search/activities".toHttpUrl().newBuilder()
            .addQueryParameter("start", "0")
            .addQueryParameter("limit", safeLimit.toString())
            .build()
        val payload = authorizedGet(url)
        val root = runCatching { JSONTokener(String(payload, Charsets.UTF_8)).nextValue() }
            .getOrNull()
            ?: throw GarminException(
                GarminException.Kind.Malformed,
                "Garmin returned an unexpected activity list.",
            )
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("activityList") ?: JSONArray()
            else -> JSONArray()
        }
        val activities = mutableListOf<GarminActivity>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optLong("activityId", 0L).takeIf { it > 0L }?.toString()
                ?: item.optString("activityId").takeIf { it.isNotBlank() }
                ?: continue
            activities += GarminActivity(
                id = id,
                name = item.optString("activityName"),
                startTimeLocal = item.optString("startTimeLocal")
                    .ifBlank { item.optString("startTimeGMT") },
                sport = item.optJSONObject("activityType")?.optString("typeKey").orEmpty(),
                distanceMeters = item.optDouble("distance", 0.0).takeIf { it.isFinite() } ?: 0.0,
                durationSeconds = item.optDouble("duration", 0.0).takeIf { it.isFinite() } ?: 0.0,
            )
        }
        return activities
    }

    /**
     * Download the original activity file and return the FIT payload.
     *
     * Garmin serves the original as a ZIP; a few activities come back as a bare
     * FIT. Both shapes are accepted. Downloads are serialised and spaced out to
     * stay below Garmin's undocumented rate limit.
     */
    suspend fun downloadOriginalFit(activityId: String): ByteArray {
        val id = activityId.filter(Char::isDigit)
        if (id.isBlank()) {
            throw GarminException(GarminException.Kind.Malformed, "Invalid Garmin activity id.")
        }
        throttleDownloads()
        val url = "$apiBase/download-service/files/activity/$id".toHttpUrl()
        return extractFit(authorizedGet(url, accept = "*/*"))
    }

    private suspend fun handleLoginResponse(
        response: HttpResult,
        params: Map<String, String>,
        referer: String,
    ): GarminLoginResult {
        if (response.code == 429) {
            throw rateLimited("Garmin rate-limited the sign-in request.", response.retryAfterSeconds)
        }
        if (response.code == 403) {
            throw GarminException(
                GarminException.Kind.Blocked,
                "Garmin blocked this sign-in request (HTTP 403). Try again later.",
            )
        }
        val json = parseJson(response.body) ?: throw GarminException(
            GarminException.Kind.Malformed,
            "Garmin returned an unexpected sign-in response (HTTP ${response.code}).",
        )
        if (json.optJSONObject("error")?.optString("status-code") == "429") {
            throw rateLimited("Garmin rate-limited the sign-in request.", response.retryAfterSeconds)
        }
        return when (val status = json.optJSONObject("responseStatus")?.optString("type").orEmpty()) {
            "SUCCESSFUL" -> {
                val ticket = json.optString("serviceTicketId").takeIf { it.isNotBlank() }
                    ?: throw GarminException(
                        GarminException.Kind.Malformed,
                        "Garmin did not return a service ticket.",
                    )
                GarminLoginResult.Success(exchangeServiceTicket(ticket))
            }
            "MFA_REQUIRED" -> {
                val method = json.optJSONObject("customerMfaInfo")
                    ?.optString("mfaLastMethodUsed")
                    .orEmpty()
                    .ifBlank { "email" }
                pendingMfa = PendingMfa(method = method, params = params, referer = referer)
                GarminLoginResult.NeedsMfa(method)
            }
            "INVALID_USERNAME_PASSWORD" -> throw GarminException(
                GarminException.Kind.Auth,
                "Garmin rejected the email or password.",
            )
            "CAPTCHA_REQUIRED" -> throw GarminException(
                GarminException.Kind.Blocked,
                "Garmin requires a CAPTCHA. Try again later or sign in from the Garmin Connect app first.",
            )
            else -> throw GarminException(
                GarminException.Kind.Auth,
                "Garmin sign-in failed (${status.ifBlank { "HTTP ${response.code}" }}).",
            )
        }
    }

    private suspend fun exchangeServiceTicket(ticket: String): GarminTokens {
        var lastStatus = 0
        for (clientId in clientIds) {
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("service_ticket", ticket)
                .add("grant_type", DI_GRANT_TYPE)
                .add("service_url", service)
                .build()
            val request = Request.Builder()
                .url(tokenEndpoint)
                .header("Authorization", basicAuth(clientId))
                .header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
                .header("Cache-Control", "no-cache")
                .apply { nativeHeaders().forEach { (name, value) -> header(name, value) } }
                .post(form)
                .build()
            val response = call(request)
            if (response.code == 429) {
                throw rateLimited(
                    "Garmin rate-limited the token exchange.",
                    response.retryAfterSeconds,
                )
            }
            if (response.code !in 200..299) {
                lastStatus = response.code
                continue
            }
            val json = parseJson(response.body) ?: continue
            val access = json.optString("access_token")
            if (access.isBlank()) continue
            val tokens = GarminTokens(
                accessToken = access,
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                clientId = clientId,
                expiresAtMillis = jwtExpiryMillis(access) ?: (clock() + DEFAULT_TOKEN_LIFETIME_MS),
            )
            tokenStore.save(tokens)
            return tokens
        }
        throw GarminException(
            GarminException.Kind.Auth,
            "Garmin rejected the sign-in exchange (HTTP $lastStatus). Try again later.",
        )
    }

    private suspend fun authorizedGet(url: HttpUrl, accept: String = "application/json"): ByteArray {
        var tokens = tokenStore.load() ?: throw GarminException(
            GarminException.Kind.NotSignedIn,
            "Sign in to Garmin Connect first.",
        )
        if (tokens.refreshToken != null && tokens.expiresAtMillis - clock() < TOKEN_REFRESH_WINDOW_MS) {
            tokens = refreshTokens(tokens)
        }
        var response = call(apiRequest(url, tokens.accessToken, accept))
        if ((response.code == 401 || response.code == 403) && tokens.refreshToken != null) {
            tokens = refreshTokens(tokens)
            response = call(apiRequest(url, tokens.accessToken, accept))
        }
        return when {
            response.code == 429 -> throw rateLimited(
                "Garmin is rate-limiting requests.",
                response.retryAfterSeconds,
            )
            response.code in 200..299 -> response.body
            response.code == 401 || response.code == 403 -> {
                tokenStore.clear()
                throw GarminException(
                    GarminException.Kind.NotSignedIn,
                    "The Garmin session expired. Sign in again.",
                )
            }
            else -> throw GarminException(
                GarminException.Kind.Server,
                "Garmin returned HTTP ${response.code}.",
            )
        }
    }

    private suspend fun refreshTokens(tokens: GarminTokens): GarminTokens {
        val refreshToken = tokens.refreshToken ?: throw GarminException(
            GarminException.Kind.NotSignedIn,
            "The Garmin session expired. Sign in again.",
        )
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", tokens.clientId)
            .add("refresh_token", refreshToken)
            .build()
        val request = Request.Builder()
            .url(tokenEndpoint)
            .header("Authorization", basicAuth(tokens.clientId))
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .apply { nativeHeaders().forEach { (name, value) -> header(name, value) } }
            .post(form)
            .build()
        val response = call(request)
        if (response.code == 429) {
            throw rateLimited("Garmin rate-limited the token refresh.", response.retryAfterSeconds)
        }
        val json = if (response.code in 200..299) parseJson(response.body) else null
        val access = json?.optString("access_token").orEmpty()
        if (access.isBlank()) {
            tokenStore.clear()
            throw GarminException(
                GarminException.Kind.NotSignedIn,
                "The Garmin session expired. Sign in again.",
            )
        }
        val refreshed = GarminTokens(
            accessToken = access,
            refreshToken = json?.optString("refresh_token")?.takeIf { it.isNotBlank() } ?: refreshToken,
            clientId = tokens.clientId,
            expiresAtMillis = jwtExpiryMillis(access) ?: (clock() + DEFAULT_TOKEN_LIFETIME_MS),
        )
        tokenStore.save(refreshed)
        return refreshed
    }

    private fun apiRequest(url: HttpUrl, accessToken: String, accept: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", accept)
            .apply { nativeHeaders().forEach { (name, value) -> header(name, value) } }
            .get()
            .build()

    private fun loginRequest(
        params: Map<String, String>,
        referer: String,
        username: String,
        password: String,
    ): Request {
        val url = "$ssoBase/mobile/api/login".toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
            put("rememberMe", true)
            put("captchaToken", "")
        }
        return Request.Builder()
            .url(url)
            .header("User-Agent", IOS_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", ssoBase)
            .header("Referer", referer)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
    }

    private suspend fun throttleDownloads() {
        downloadMutex.withLock {
            val sinceLast = clock() - lastDownloadAtMillis
            if (lastDownloadAtMillis > 0L && sinceLast < downloadDelayMillis) {
                delay(downloadDelayMillis - sinceLast)
            }
            lastDownloadAtMillis = clock()
        }
    }

    private fun extractFit(payload: ByteArray): ByteArray {
        if (payload.isEmpty()) {
            throw GarminException(
                GarminException.Kind.Malformed,
                "Garmin returned an empty activity file.",
            )
        }
        if (payload.size > MAX_DOWNLOAD_BYTES) {
            throw GarminException(
                GarminException.Kind.Malformed,
                "The Garmin activity file is too large to import.",
            )
        }
        if (isZip(payload)) {
            ZipInputStream(ByteArrayInputStream(payload)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.endsWith(".fit", ignoreCase = true)) {
                        continue
                    }
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FIT_BYTES) {
                            throw GarminException(
                                GarminException.Kind.Malformed,
                                "The downloaded FIT file is too large to import.",
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                    val fit = output.toByteArray()
                    if (fit.isNotEmpty()) return fit
                }
            }
            throw GarminException(
                GarminException.Kind.Malformed,
                "The downloaded Garmin archive contains no FIT activity.",
            )
        }
        if (isFit(payload)) return payload
        throw GarminException(
            GarminException.Kind.Malformed,
            "Garmin returned an unexpected file format instead of a FIT activity.",
        )
    }

    private fun isZip(payload: ByteArray): Boolean =
        payload.size >= 2 && payload[0] == 'P'.code.toByte() && payload[1] == 'K'.code.toByte()

    private fun isFit(payload: ByteArray): Boolean =
        payload.size >= 12 &&
            payload[8] == '.'.code.toByte() &&
            payload[9] == 'F'.code.toByte() &&
            payload[10] == 'I'.code.toByte() &&
            payload[11] == 'T'.code.toByte()

    private fun jwtExpiryMillis(token: String): Long? = runCatching {
        val parts = token.split('.')
        if (parts.size < 2) return@runCatching null
        val payload = parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)
        val json = JSONObject(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8))
        json.optLong("exp", 0L).takeIf { it > 0L }?.times(1000L)
    }.getOrNull()

    private fun basicAuth(clientId: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$clientId:".toByteArray(Charsets.UTF_8))

    private fun rateLimited(message: String, retryAfterSeconds: Long?): GarminException {
        val suffix = retryAfterSeconds?.let { " Try again in about ${it}s." }
            ?: " Wait a minute and try again."
        return GarminException(
            GarminException.Kind.RateLimit,
            message + suffix,
            retryAfterSeconds = retryAfterSeconds,
        )
    }

    private fun parseJson(body: ByteArray): JSONObject? = runCatching {
        if (body.isEmpty()) null else JSONObject(String(body, Charsets.UTF_8))
    }.getOrNull()

    private suspend fun call(request: Request): HttpResult = withContext(Dispatchers.IO) {
        try {
            okHttpClient.newCall(request).execute().use { response ->
                HttpResult(
                    code = response.code,
                    body = response.body?.bytes() ?: ByteArray(0),
                    retryAfterSeconds = response.header("Retry-After")?.trim()?.toLongOrNull(),
                )
            }
        } catch (error: IOException) {
            throw GarminException(
                GarminException.Kind.Network,
                "Could not reach Garmin. Check the connection and try again.",
                cause = error,
            )
        }
    }

    private fun nativeHeaders(): Map<String, String> = linkedMapOf(
        "User-Agent" to NATIVE_USER_AGENT,
        "X-Garmin-User-Agent" to NATIVE_X_GARMIN_USER_AGENT,
        "X-Garmin-Paired-App-Version" to "10861",
        "X-Garmin-Client-Platform" to "Android",
        "X-App-Ver" to "10861",
        "X-Lang" to "en",
        "X-GCExperience" to "GC5",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private data class HttpResult(
        val code: Int,
        val body: ByteArray,
        val retryAfterSeconds: Long?,
    )

    private data class PendingMfa(
        val method: String,
        val params: Map<String, String>,
        val referer: String,
    )

    private class MemoryCookieJar : CookieJar {
        private val cookies = mutableMapOf<String, MutableList<Cookie>>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val existing = this.cookies.getOrPut(host) { mutableListOf() }
            cookies.forEach { cookie ->
                existing.removeAll { it.name == cookie.name && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) existing += cookie
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return cookies[url.host]?.filter { it.expiresAt > now }.orEmpty()
        }
    }

    companion object {
        private const val DEFAULT_API_URL = "https://connectapi.garmin.com"
        private const val DEFAULT_SSO_URL = "https://sso.garmin.com"
        private const val DEFAULT_TOKEN_URL =
            "https://diauth.garmin.com/di-oauth2-service/oauth/token"
        private const val DEFAULT_SERVICE_URL = "https://mobile.integration.garmin.com/gcm/ios"
        private const val IOS_CLIENT_ID = "GCM_IOS_DARK"
        private const val IOS_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
        private const val NATIVE_USER_AGENT = "GCM-Android-5.23"
        private const val NATIVE_X_GARMIN_USER_AGENT =
            "com.garmin.android.apps.connectmobile/5.23; ; " +
                "Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0"
        private const val DI_GRANT_TYPE =
            "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"
        private const val TOKEN_REFRESH_WINDOW_MS = 5 * 60 * 1000L
        private const val DEFAULT_TOKEN_LIFETIME_MS = 60 * 60 * 1000L
        private const val MAX_FIT_BYTES = 64L * 1024 * 1024
        private const val MAX_DOWNLOAD_BYTES = 96L * 1024 * 1024

        private val DEFAULT_CLIENT_IDS = listOf(
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI",
            "GARMIN_CONNECT_MOBILE_IOS_DI",
        )

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .cookieJar(MemoryCookieJar())
            .build()
    }
}
