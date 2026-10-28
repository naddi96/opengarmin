package dev.opengarmin.app.garmin

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GarminConnectClientTest {
    private lateinit var server: MockWebServer
    private lateinit var tokens: FakeTokenStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokens = FakeTokenStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): GarminConnectClient = GarminConnectClient(
        tokenStore = tokens,
        baseUrl = server.url("/api").toString().trimEnd('/'),
        ssoUrl = server.url("/").toString().trimEnd('/'),
        tokenUrl = server.url("/token").toString(),
        serviceUrl = "https://mobile.integration.garmin.com/gcm/ios",
        loginPageDelayMillis = 0,
        downloadDelayMinMillis = 0,
        downloadDelayMaxMillis = 0,
        summaryDelayMillis = 0,
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build(),
    )

    @Test
    fun `login exchanges the service ticket and lists activities`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>sign in</html>"))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"responseStatus":{"type":"SUCCESSFUL"},"serviceTicketId":"ST-123"}""",
                ),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"access-token","refresh_token":"refresh-token"}"""),
        )

        val result = client().login("runner@example.com", "secret")
        assertTrue(result is GarminLoginResult.Success)
        assertEquals("access-token", tokens.tokens?.accessToken)
        assertEquals("refresh-token", tokens.tokens?.refreshToken)
        val pageRequest = server.takeRequest()
        assertTrue(pageRequest.path.orEmpty().startsWith("/mobile/sso/en_US/sign-in"))
        val login = server.takeRequest()
        assertEquals("/mobile/api/login?clientId=GCM_IOS_DARK&locale=en-US&service=" +
            "https%3A%2F%2Fmobile.integration.garmin.com%2Fgcm%2Fios", login.path)
        val loginBody = JSONObject(login.body.readUtf8())
        assertEquals("runner@example.com", loginBody.getString("username"))
        assertEquals("secret", loginBody.getString("password"))

        val exchange = server.takeRequest()
        assertEquals("/token", exchange.path)
        assertTrue(exchange.getHeader("Authorization").orEmpty().startsWith("Basic "))

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    JSONArray()
                        .put(
                            JSONObject().apply {
                                put("activityId", 987654321L)
                                put("activityName", "Morning Run")
                                put("startTimeLocal", "2026-10-27T07:15:00.0")
                                put("activityType", JSONObject().put("typeKey", "running"))
                                put("distance", 10000.0)
                                put("duration", 3000.0)
                            },
                        )
                        .toString(),
                ),
        )
        val activities = client().recentActivities(limit = 5)
        assertEquals(1, activities.size)
        assertEquals("987654321", activities.first().id)
        assertEquals("Morning Run", activities.first().displayTitle)
        assertEquals("running", activities.first().sport)
        val listRequest = server.takeRequest()
        assertTrue(listRequest.path.orEmpty().startsWith("/api/activitylist-service/activities/search/activities"))
        assertEquals("Bearer access-token", listRequest.getHeader("Authorization"))
    }

    @Test
    fun `mfa login continues on the same session`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>sign in</html>"))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"responseStatus":{"type":"MFA_REQUIRED"},""" +
                        """"customerMfaInfo":{"mfaLastMethodUsed":"email"}}""",
                ),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"responseStatus":{"type":"SUCCESSFUL"},"serviceTicketId":"ST-MFA"}""",
                ),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"access-mfa","refresh_token":"refresh-mfa"}"""),
        )

        val client = client()
        val first = client.login("runner@example.com", "secret")
        assertEquals("email", (first as GarminLoginResult.NeedsMfa).method)

        val second = client.completeMfa("123456")
        assertEquals("access-mfa", second.accessToken)
        assertEquals("access-mfa", tokens.tokens?.accessToken)

        server.takeRequest()
        server.takeRequest()
        val mfa = server.takeRequest()
        assertTrue(mfa.path.orEmpty().startsWith("/mobile/api/mfa/verifyCode"))
        assertEquals("123456", JSONObject(mfa.body.readUtf8()).getString("mfaVerificationCode"))
    }

    @Test
    fun `download extracts the fit entry from the original zip`() = runBlocking {
        tokens.tokens = GarminTokens(
            accessToken = "access-token",
            refreshToken = null,
            clientId = "client",
            expiresAtMillis = Long.MAX_VALUE,
        )
        val fit = ByteArray(32) { 1 }
        "FIT".toByteArray().copyInto(fit, 8)
        val zip = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { stream ->
                stream.putNextEntry(ZipEntry("987654321.fit"))
                stream.write(fit)
                stream.closeEntry()
            }
        }.toByteArray()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/zip")
                .setBody(okio.Buffer().write(zip)),
        )

        val downloaded = client().downloadOriginalFit("987654321")
        assertTrue(downloaded.contentEquals(fit))
        val request = server.takeRequest()
        assertEquals("/api/download-service/files/activity/987654321", request.path)
        assertEquals("*/*", request.getHeader("Accept"))
    }

    @Test
    fun `daily summary uses the profile display name and parses wellness totals`() = runBlocking {
        tokens.tokens = GarminTokens(
            accessToken = "access-token",
            refreshToken = null,
            clientId = "client",
            expiresAtMillis = Long.MAX_VALUE,
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"displayName":"runner42","fullName":"Runner"}"""),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{
                        "totalSteps": 12843,
                        "totalDistanceMeters": 10320.5,
                        "activeKilocalories": 742,
                        "activeSeconds": 5400,
                        "restingHeartRate": 54,
                        "averageStressLevel": 27,
                        "bodyBatteryLowestValue": 21,
                        "bodyBatteryHighestValue": 88,
                        "bodyBatteryMostRecentValue": 55
                    }""",
                ),
        )

        val summary = client().dailySummary("2026-10-27")

        assertEquals(
            GarminDailySummary(
                date = "2026-10-27",
                steps = 12843,
                distanceMeters = 10320.5,
                activeCalories = 742,
                activeSeconds = 5400,
                restingHeartRate = 54,
                stressAvg = 27,
                bodyBatteryMin = 21,
                bodyBatteryMax = 88,
                bodyBatteryLatest = 55,
            ),
            summary,
        )
        val profile = server.takeRequest()
        assertEquals("/api/userprofile-service/socialProfile", profile.path)
        val daily = server.takeRequest()
        assertEquals(
            "/api/usersummary-service/usersummary/daily/runner42?calendarDate=2026-10-27",
            daily.path,
        )
    }

    @Test
    fun `missing daily summary is skipped and empty days are not invented`() = runBlocking {
        tokens.tokens = GarminTokens(
            accessToken = "access-token",
            refreshToken = null,
            clientId = "client",
            expiresAtMillis = Long.MAX_VALUE,
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"displayName":"runner42"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"totalSteps":0,"activeSeconds":0}"""),
        )

        val progress = mutableListOf<Pair<Int, Int>>()
        val summaries = client().dailySummaries(
            dates = listOf("2026-10-26", "2026-10-25"),
            onProgress = { done, total -> progress += done to total },
        )

        assertTrue(summaries.isEmpty())
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
    }

    @Test
    fun `rate limit surfaces the retry window`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>sign in</html>"))
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "30"),
        )

        val error = runCatching { client().login("runner@example.com", "secret") }
            .exceptionOrNull() as GarminException
        assertEquals(GarminException.Kind.RateLimit, error.kind)
        assertEquals(30L, error.retryAfterSeconds)
    }

    @Test
    fun `expired access token refreshes before the request`() = runBlocking {
        tokens.tokens = GarminTokens(
            accessToken = "expired",
            refreshToken = "refresh-token",
            clientId = "client",
            expiresAtMillis = 1L,
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"fresh","refresh_token":"refresh-2"}"""),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[]"),
        )

        val activities = client().recentActivities()
        assertTrue(activities.isEmpty())
        assertEquals("fresh", tokens.tokens?.accessToken)
        assertEquals("refresh-2", tokens.tokens?.refreshToken)
        server.takeRequest()
        val list = server.takeRequest()
        assertEquals("Bearer fresh", list.getHeader("Authorization"))
    }

    @Test
    fun `activity pages use the requested offset and count comes from Garmin`() = runBlocking {
        tokens.tokens = GarminTokens(
            accessToken = "access-token",
            refreshToken = null,
            clientId = "client",
            expiresAtMillis = Long.MAX_VALUE,
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"totalCount":42}"""),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    JSONArray()
                        .put(
                            JSONObject().apply {
                                put("activityId", 111L)
                                put("activityName", "Older run")
                                put("startTimeLocal", "2025-01-02T08:00:00.0")
                                put("activityType", JSONObject().put("typeKey", "running"))
                                put("distance", 5000.0)
                                put("duration", 1500.0)
                            },
                        )
                        .toString(),
                ),
        )

        val client = client()
        assertEquals(42, client.activityCount())
        val page = client.activities(start = 20, limit = 10)
        assertEquals("111", page.single().id)

        val countRequest = server.takeRequest()
        assertEquals("/api/activitylist-service/activities/count", countRequest.path)
        val pageRequest = server.takeRequest()
        assertEquals(
            "/api/activitylist-service/activities/search/activities?start=20&limit=10",
            pageRequest.path,
        )
    }

    private class FakeTokenStore(var tokens: GarminTokens? = null) : GarminTokenStore {
        override fun load(): GarminTokens? = tokens
        override fun save(tokens: GarminTokens) {
            this.tokens = tokens
        }
        override fun clear() {
            tokens = null
        }
    }
}
