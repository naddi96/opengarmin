package dev.opengarmin.app.coach.tools

import dev.opengarmin.app.coach.DailyHealth
import dev.opengarmin.app.coach.ChartType
import dev.opengarmin.app.coach.GeoBounds
import dev.opengarmin.app.coach.GeoPoint
import dev.opengarmin.app.coach.GpsSummary
import dev.opengarmin.app.coach.HealthDataSource
import dev.opengarmin.app.coach.HealthSample
import dev.opengarmin.app.coach.LOCAL_PROFILE_ID
import dev.opengarmin.app.coach.LapDynamics
import dev.opengarmin.app.coach.LapInfo
import dev.opengarmin.app.coach.RecordPoint
import dev.opengarmin.app.coach.RunningDynamicsSummary
import dev.opengarmin.app.coach.ToolScope
import dev.opengarmin.app.coach.WorkoutDetail
import dev.opengarmin.app.coach.WorkoutSummary
import dev.opengarmin.app.coach.WorkoutWriter
import dev.opengarmin.app.data.PlannedStep
import dev.opengarmin.app.data.PlannedTargetType
import dev.opengarmin.app.data.PlannedWorkoutEntity
import dev.opengarmin.app.data.StepRole
import dev.opengarmin.app.data.WorkoutPlanCodec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class HealthToolsTest {
    private val today = LocalDate.of(2026, 10, 17)
    private val days = listOf(
        day("2026-10-11", steps = 12_000, resting = 58),
        day("2026-10-12", steps = 8_000, resting = 57),
        day("2026-10-13", steps = 10_500, resting = 56),
    )
    private val workouts = listOf(
        workout(1, "2026-10-12", sport = 1, avgHr = 150),
        workout(2, "2026-10-11", sport = 2, avgHr = 132),
    )
    private val source = FakeSource(days, workouts)
    private val registry = ToolRegistry(source) { today }

    @Test
    fun `rejects a non-local profile scope`() = runBlocking {
        val error = runCatching {
            registry.execute("get_steps", "{}", ToolScope("someone-else"))
        }.exceptionOrNull()
        assertTrue(error is ToolException)
        assertTrue(error?.message?.contains("scope") == true)
    }

    @Test
    fun `daily summary does not zero-fill missing days`() = runBlocking {
        val result = registry.execute(
            "get_daily_summary",
            """{"from":"2026-10-10","to":"2026-10-17"}""",
            ToolScope(),
        )
        val json = JSONObject(result.json)
        assertEquals(8, json.getInt("daysRequested"))
        assertEquals(3, json.getInt("daysWithData"))
        assertEquals(3, json.getJSONArray("days").length())
        assertTrue(result.evidence.first().contains("3 days"))
    }

    @Test
    fun `steps enforces the daily maximum range`() = runBlocking {
        val error = runCatching {
            registry.execute(
                "get_steps",
                """{"from":"2026-01-01","to":"2026-10-17","granularity":"day"}""",
                ToolScope(),
            )
        }.exceptionOrNull()
        assertTrue(error is ToolException)
    }

    @Test
    fun `steps returns real day buckets and totals`() = runBlocking {
        val result = registry.execute(
            "get_steps",
            """{"from":"2026-10-11","to":"2026-10-13"}""",
            ToolScope(),
        )
        val json = JSONObject(result.json)
        assertEquals(3, json.getInt("daysWithData"))
        assertEquals(30_500, json.getInt("totalSteps"))
        assertEquals(3, json.getJSONArray("buckets").length())
    }

    @Test
    fun `heart rate samples are capped`() = runBlocking {
        val base = LocalDate.parse("2026-10-11").atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()
        val manySamples = (0 until 400).map {
            HealthSample("hr", base + it * 60L, 60.0 + it % 40)
        }
        val manySource = FakeSource(days, workouts, samples = manySamples)
        val manyRegistry = ToolRegistry(manySource) { today }
        val result = manyRegistry.execute(
            "get_heart_rate",
            """{"from":"2026-10-11","to":"2026-10-13","include_samples":true}""",
            ToolScope(),
        )
        val samples = JSONObject(result.json).getJSONArray("samples")
        assertTrue(samples.length() <= 168)
    }

    @Test
    fun `list activities filters and caps the page`() = runBlocking {
        val result = registry.execute(
            "list_activities",
            """{"from":"2026-10-11","to":"2026-10-13","type":"Run","limit":1}""",
            ToolScope(),
        )
        val json = JSONObject(result.json)
        assertEquals(1, json.getInt("totalMatching"))
        assertEquals(1, json.getJSONArray("activities").length())
        assertEquals("Running", json.getJSONArray("activities").getJSONObject(0).getString("sport"))
    }

    @Test
    fun `activity zones use supplied or configured max heart rate`() = runBlocking {
        val withoutZones = registry.execute("get_activity", """{"id":1}""", ToolScope())
        assertFalse(JSONObject(withoutZones.json).getBoolean("zonesAvailable"))

        val withZones = registry.execute(
            "get_activity",
            """{"id":1,"max_heart_rate":190}""",
            ToolScope(),
        )
        val zones = JSONObject(withZones.json)
        assertTrue(zones.getBoolean("zonesAvailable"))
        val percentages = zones.getJSONObject("zonePercent")
        val sum = (1..5).sumOf { percentages.getDouble("z$it") }
        assertTrue(sum in 99.0..101.0)

        val configured = registry.execute(
            "get_activity",
            """{"id":1}""",
            ToolScope(maxHeartRate = 190),
        )
        assertTrue(JSONObject(configured.json).getBoolean("zonesAvailable"))
        assertEquals("max_heart_rate=190", JSONObject(configured.json).getString("zoneBasis"))
    }

    @Test
    fun `activity exposes gps and running dynamics`() = runBlocking {
        val result = registry.execute(
            "get_activity",
            """{"id":1,"include_route":true,"max_heart_rate":190}""",
            ToolScope(),
        )
        val json = JSONObject(result.json)
        val gps = json.getJSONObject("gps")
        assertTrue(gps.getBoolean("available"))
        assertEquals(3, gps.getInt("pointCount"))
        assertEquals(2, gps.getJSONArray("route").length())
        assertEquals(43.712586, gps.getDouble("startLat"), 0.000001)

        val dynamics = json.getJSONObject("runningDynamics")
        assertTrue(dynamics.getBoolean("available"))
        assertEquals(134.9, dynamics.getDouble("cadenceSpm"), 0.1)
        assertEquals(313.6, dynamics.getDouble("groundContactTimeMs"), 0.1)
        assertEquals(94.1, dynamics.getDouble("verticalOscillationMm"), 0.1)
        assertEquals(979.3, dynamics.getDouble("strideLengthMm"), 0.1)
        assertEquals(2.5, dynamics.getDouble("trainingEffect"), 0.1)
        assertEquals("Run", json.getString("category"))
    }

    @Test
    fun `compare periods reports averages and deltas`() = runBlocking {
        val result = registry.execute(
            "compare_periods",
            """{"metric":"steps","range_a_start":"2026-10-12","range_a_end":"2026-10-12","range_b_start":"2026-10-13","range_b_end":"2026-10-13"}""",
            ToolScope(),
        )
        val json = JSONObject(result.json)
        assertFalse(json.getBoolean("insufficientData"))
        assertEquals(2500.0, json.getDouble("delta"), 0.2)
        assertEquals(31.25, json.getDouble("percentChange"), 0.2)
    }

    @Test
    fun `sleep is explicitly unavailable`() = runBlocking {
        val result = registry.execute("get_sleep", "{}", ToolScope())
        val json = JSONObject(result.json)
        assertFalse(json.getBoolean("available"))
        assertTrue(result.evidence.first().contains("not synced"))
    }

    @Test
    fun `sleep and hrv use real stored nights`() = runBlocking {
        val withSleep = days + DailyHealth(
            date = "2026-10-14",
            steps = 9_000,
            distanceMeters = 6_750.0,
            activeCalories = 300,
            activeSeconds = 2_400,
            restingHeartRate = 56,
            avgHeartRate = 80,
            maxHeartRate = 140,
            stressAvg = 28,
            spo2Avg = 97,
            respirationAvg = 14.2,
            sleepSeconds = 7 * 3600 + 30 * 60,
            sleepScore = 82,
            deepSleepSeconds = 3600,
            lightSleepSeconds = 4 * 3600,
            remSleepSeconds = 2 * 3600 + 30 * 60,
            hrvLastNight = 48.0,
            hrvStatus = "Balanced",
        )
        val sleepRegistry = ToolRegistry(FakeSource(withSleep, workouts)) { today }

        val result = sleepRegistry.execute(
            "get_sleep",
            """{"from":"2026-10-14","to":"2026-10-14"}""",
            ToolScope(),
        )
        val json = JSONObject(result.json)

        assertTrue(json.getBoolean("available"))
        val session = json.getJSONArray("sessions").getJSONObject(0)
        assertEquals(7.5, session.getDouble("sleepHours"), 0.01)
        assertEquals(82, session.getInt("sleepScore"))
        assertEquals(48.0, session.getDouble("hrvMs"), 0.01)
    }

    @Test
    fun `snapshot is compact and labels unavailable metrics`() = runBlocking {
        val snapshot = registry.snapshot(ToolScope(), days = 7)
        assertTrue(snapshot.json.length < 6_000)
        assertTrue(snapshot.json.contains("unavailable"))
        assertTrue(snapshot.evidence.isNotEmpty())
    }

    @Test
    fun `unknown tools and bad arguments fail safely`() = runBlocking {
        assertTrue(
            runCatching { registry.execute("drop_tables", "{}", ToolScope()) }.exceptionOrNull() is ToolException,
        )
        assertTrue(
            runCatching { registry.execute("get_daily_summary", "{not json", ToolScope()) }.exceptionOrNull() is ToolException,
        )
    }

    @Test
    fun `current datetime reports the device clock and timezone`() = runBlocking {
        val fixed = ZonedDateTime.of(2026, 10, 28, 18, 42, 5, 0, ZoneId.of("Europe/Rome"))
        val clockRegistry = ToolRegistry(source, now = { fixed }) { today }

        val result = clockRegistry.execute("get_current_datetime", "{}", ToolScope())
        val json = JSONObject(result.json)

        assertEquals("2026-10-28", json.getString("date"))
        assertEquals("18:42", json.getString("time"))
        assertEquals("Wednesday", json.getString("weekday"))
        assertEquals("Europe/Rome", json.getString("timeZone"))
        assertEquals("+01:00", json.getString("utcOffset"))
        assertEquals("evening", json.getString("partOfDay"))
        assertFalse(json.getBoolean("isWeekend"))
        assertTrue(result.evidence.single().startsWith("Date & time"))
    }

    @Test
    fun `daily chart is built from stored days and carries evidence`() = runBlocking {
        val result = registry.execute(
            "render_chart",
            """{"source":"daily","metric":"steps","from":"2026-10-11","to":"2026-10-13","chart_type":"bar"}""",
            ToolScope(),
        )

        val chart = result.charts.single()
        assertEquals(ChartType.Bar, chart.type)
        assertEquals("steps", chart.unit)
        assertEquals(3, chart.series.single().points.size)
        assertEquals(12_000f, chart.series.single().points.first().y, 0.01f)
        assertEquals(listOf("Oct 11", "Oct 12", "Oct 13"), chart.categories)
        assertTrue(result.evidence.single().contains("Chart"))
        assertEquals(3, JSONObject(result.json).getInt("points"))
    }

    @Test
    fun `activity chart overlays multiple series and caps points`() = runBlocking {
        val result = registry.execute(
            "render_chart",
            """{"source":"activities","activity_ids":[1,2],"metric":"heart_rate","max_points":12}""",
            ToolScope(),
        )

        val chart = result.charts.single()
        assertEquals(ChartType.Line, chart.type)
        assertEquals(2, chart.series.size)
        assertTrue(chart.series.all { it.points.size in 2..12 })
        assertEquals("bpm", chart.unit)
        assertEquals("Elapsed (min)", chart.xLabel)
        assertEquals(2, JSONObject(result.json).getJSONArray("series").length())
    }

    @Test
    fun `activity chart converts speed and pace honestly`() = runBlocking {
        val speed = registry.execute(
            "render_chart",
            """{"source":"activities","activity_ids":[1],"metric":"speed","x_axis":"distance"}""",
            ToolScope(),
        ).charts.single()
        assertEquals("km/h", speed.unit)
        assertEquals("Distance (km)", speed.xLabel)
        assertEquals(10.08f, speed.series.single().points.first().y, 0.01f)

        val pace = registry.execute(
            "render_chart",
            """{"source":"activities","activity_ids":[1],"metric":"pace"}""",
            ToolScope(),
        ).charts.single()
        assertEquals("min/km", pace.unit)
        assertTrue(pace.series.single().points.first().y > 0f)
    }

    @Test
    fun `activity chart fails cleanly for unknown or sparse data`() = runBlocking {
        val missing = runCatching {
            registry.execute(
                "render_chart",
                """{"source":"activities","activity_ids":[99],"metric":"heart_rate"}""",
                ToolScope(),
            )
        }.exceptionOrNull()
        assertTrue(missing is ToolException)

        val sparse = runCatching {
            registry.execute(
                "render_chart",
                """{"source":"activities","activity_ids":[1],"metric":"cadence"}""",
                ToolScope(),
            )
        }.exceptionOrNull()
        assertTrue(sparse is ToolException)
        assertTrue(sparse?.message?.contains("Not enough") == true)
    }

    @Test
    fun `chart tool rejects out of range bar windows`() = runBlocking {
        val error = runCatching {
            registry.execute(
                "render_chart",
                """{"source":"daily","metric":"steps","from":"2026-01-01","to":"2026-10-17","chart_type":"bar"}""",
                ToolScope(),
            )
        }.exceptionOrNull()
        assertTrue(error is ToolException)
    }

    @Test
    fun `create workout parses steps and repeat blocks`() = runBlocking {
        val writer = FakeWorkoutWriter()
        val workoutRegistry = ToolRegistry(source, writer) { today }

        val result = workoutRegistry.execute(
            "create_workout",
            """
            {
              "name": "Tempo Intervals",
              "sport": "run",
              "scheduled_date": "2026-10-18",
              "notes": "Keep it controlled",
              "steps": [
                {"type": "step", "role": "warmup", "minutes": 10},
                {"type": "repeat", "count": 4, "steps": [
                  {"role": "interval", "duration_type": "distance", "distance_km": 1.0},
                  {"role": "recovery", "minutes": 2}
                ]},
                {"type": "step", "role": "cooldown", "duration_type": "open"}
              ]
            }
            """.trimIndent(),
            ToolScope(),
        )

        val json = JSONObject(result.json)
        assertTrue(json.getBoolean("created"))
        assertEquals(1L, json.getLong("id"))
        assertEquals("Run", json.getString("sport"))
        assertEquals(5, json.getInt("stepCount"))
        assertEquals(false, json.getBoolean("sentToWatch"))
        assertTrue(result.evidence.single().contains("Workout created"))

        val saved = writer.created.single()
        assertEquals("Tempo Intervals", saved.name)
        assertEquals("2026-10-18", saved.scheduledDate)
        val steps = WorkoutPlanCodec.decode(saved.stepsJson)
        assertEquals(3, steps.size)
        assertEquals(StepRole.WARM_UP, (steps[0] as PlannedStep.Single).role)
        val repeat = steps[1] as PlannedStep.Repeat
        assertEquals(4, repeat.count)
        assertEquals(2, repeat.children.size)
        assertEquals(1000, repeat.children[0].meters)
        assertEquals(StepRole.RECOVERY, repeat.children[1].role)
        assertEquals("Open", (steps[2] as PlannedStep.Single).summary())
    }

    @Test
    fun `create workout parses pace and zone targets`() = runBlocking {
        val writer = FakeWorkoutWriter()
        val workoutRegistry = ToolRegistry(source, writer) { today }

        workoutRegistry.execute(
            "create_workout",
            """
            {
              "name": "Ripetute 5x1200",
              "sport": "run",
              "steps": [
                {"role": "warmup", "minutes": 15, "target": "custom_heart_rate", "target_low": "130", "target_high": "145"},
                {"type": "repeat", "count": 5, "steps": [
                  {"role": "interval", "duration_type": "distance", "distance_km": 1.2, "target": "pace", "target_low": "4:05", "target_high": "4:15/km"},
                  {"role": "recovery", "duration_type": "distance", "distance_km": 0.4, "target": "heart_rate_zone", "target_zone": 2}
                ]}
              ]
            }
            """.trimIndent(),
            ToolScope(),
        )

        val steps = WorkoutPlanCodec.decode(writer.created.single().stepsJson)
        val warmUp = steps[0] as PlannedStep.Single
        assertEquals(PlannedTargetType.CUSTOM_HEART_RATE, warmUp.targetType)
        assertEquals(130, warmUp.targetLow)
        assertEquals(145, warmUp.targetHigh)

        val repeat = steps[1] as PlannedStep.Repeat
        val interval = repeat.children[0]
        assertEquals(PlannedTargetType.PACE, interval.targetType)
        assertEquals(245, interval.targetLow)
        assertEquals(255, interval.targetHigh)
        assertEquals("4:05–4:15 /km", interval.targetSummary())
        assertEquals(PlannedTargetType.HEART_RATE_ZONE, repeat.children[1].targetType)
        assertEquals(2, repeat.children[1].targetZone)
    }

    @Test
    fun `create workout rejects targets without bounds`() = runBlocking {
        val writer = FakeWorkoutWriter()
        val workoutRegistry = ToolRegistry(source, writer) { today }

        val error = runCatching {
            workoutRegistry.execute(
                "create_workout",
                """{"name":"Bad","steps":[{"role":"active","minutes":20,"target":"pace"}]}""",
                ToolScope(),
            )
        }.exceptionOrNull()
        assertTrue(error is ToolException)
        assertTrue(error?.message?.contains("target_low") == true)
        assertTrue(writer.created.isEmpty())
    }

    @Test
    fun `create workout rejects nested repeats and past dates`() = runBlocking {
        val writer = FakeWorkoutWriter()
        val workoutRegistry = ToolRegistry(source, writer) { today }

        val nested = runCatching {
            workoutRegistry.execute(
                "create_workout",
                """{"name":"Bad","steps":[{"type":"repeat","count":2,"steps":[{"type":"repeat","count":2,"steps":[{"role":"active","minutes":1}]}]}]}""",
                ToolScope(),
            )
        }.exceptionOrNull()
        assertTrue(nested is ToolException)
        assertTrue(nested?.message?.contains("Nested repeat") == true)

        val past = runCatching {
            workoutRegistry.execute(
                "create_workout",
                """{"name":"Old","scheduled_date":"2026-10-16","steps":[{"role":"active","minutes":5}]}""",
                ToolScope(),
            )
        }.exceptionOrNull()
        assertTrue(past is ToolException)
        assertTrue(past?.message?.contains("past") == true)
        assertTrue(writer.created.isEmpty())
    }

    @Test
    fun `list planned workouts reports saved sessions`() = runBlocking {
        val writer = FakeWorkoutWriter()
        val workoutRegistry = ToolRegistry(source, writer) { today }
        workoutRegistry.execute(
            "create_workout",
            """{"name":"Easy Run","sport":"run","steps":[{"role":"active","minutes":30}]}""",
            ToolScope(),
        )

        val result = workoutRegistry.execute("list_planned_workouts", "{}", ToolScope())
        val json = JSONObject(result.json)
        assertEquals(1, json.getInt("count"))
        val workout = json.getJSONArray("workouts").getJSONObject(0)
        assertEquals("Easy Run", workout.getString("name"))
        assertFalse(workout.getBoolean("sentToWatch"))
    }

    private class FakeWorkoutWriter : WorkoutWriter {
        val created = mutableListOf<PlannedWorkoutEntity>()
        private var nextId = 1L

        override suspend fun createWorkout(
            scope: ToolScope,
            workout: PlannedWorkoutEntity,
        ): PlannedWorkoutEntity {
            if (scope.profileId != LOCAL_PROFILE_ID) throw IllegalArgumentException("scope")
            val saved = workout.copy(id = nextId++)
            created += saved
            return saved
        }

        override suspend fun listWorkouts(scope: ToolScope): List<PlannedWorkoutEntity> {
            if (scope.profileId != LOCAL_PROFILE_ID) throw IllegalArgumentException("scope")
            return created
        }
    }

    private fun day(date: String, steps: Int, resting: Int) = DailyHealth(
        date = date,
        steps = steps,
        distanceMeters = steps * 0.75,
        activeCalories = 300,
        activeSeconds = 2_400,
        restingHeartRate = resting,
        avgHeartRate = 80,
        maxHeartRate = 140,
        stressAvg = 28,
        spo2Avg = 97,
        respirationAvg = 14.2,
    )

    private fun workout(id: Long, date: String, sport: Int, avgHr: Int) = WorkoutSummary(
        id = id,
        startTime = LocalDate.parse(date).atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond(),
        sport = sport,
        subSport = 0,
        distanceMeters = 5_000.0,
        durationSeconds = 1_800.0,
        elapsedSeconds = 1_900.0,
        avgHeartRate = avgHr,
        maxHeartRate = avgHr + 15,
        calories = 400,
        ascentMeters = 30,
        descentMeters = 30,
    )

    private class FakeSource(
        private val days: List<DailyHealth>,
        private val workouts: List<WorkoutSummary>,
        private val samples: List<HealthSample> = emptyList(),
    ) : HealthDataSource {
        override suspend fun daily(scope: ToolScope, start: LocalDate, end: LocalDate): List<DailyHealth> {
            if (scope.profileId != LOCAL_PROFILE_ID) return emptyList()
            return days.filter {
                val date = LocalDate.parse(it.date)
                !date.isBefore(start) && !date.isAfter(end)
            }
        }

        override suspend fun samples(
            scope: ToolScope,
            kind: String,
            startEpoch: Long,
            endEpoch: Long,
        ): List<HealthSample> {
            if (scope.profileId != LOCAL_PROFILE_ID) return emptyList()
            return samples.filter { it.kind == kind && it.timestamp in startEpoch..endEpoch }
        }

        override suspend fun workouts(
            scope: ToolScope,
            startEpoch: Long,
            endEpoch: Long,
            limit: Int,
            offset: Int,
        ): List<WorkoutSummary> {
            if (scope.profileId != LOCAL_PROFILE_ID) return emptyList()
            return workouts.filter { it.startTime in startEpoch..endEpoch }.drop(offset).take(limit)
        }

        override suspend fun workout(scope: ToolScope, id: Long): WorkoutDetail? {
            if (scope.profileId != LOCAL_PROFILE_ID) return null
            val summary = workouts.firstOrNull { it.id == id } ?: return null
            val records = (0 until 100).map { index ->
                RecordPoint(
                    timestamp = summary.startTime + index,
                    heartRate = 120 + index % 50,
                    distanceMeters = index * 50.0,
                    speedMps = 2.8,
                    altitudeMeters = 20.0,
                    latitude = null,
                    longitude = null,
                )
            }
            return WorkoutDetail(
                summary = summary,
                laps = listOf(LapInfo(0, 900.0, 2_500.0, 145, 160, 2.8, 200)),
                records = records,
                gps = GpsSummary(
                    available = true,
                    pointCount = 3,
                    start = GeoPoint(43.712586, 10.407903),
                    end = GeoPoint(43.712814, 10.407887),
                    bounds = GeoBounds(43.7129, 43.7125, 10.4080, 10.4078),
                    route = listOf(
                        GeoPoint(43.712586, 10.407903),
                        GeoPoint(43.712814, 10.407887),
                    ),
                ),
                dynamics = RunningDynamicsSummary(
                    available = true,
                    cadenceRaw = 67.4296875,
                    cadenceSpm = 134.859375,
                    maxCadenceSpm = 169.0,
                    groundContactTimeMs = 313.6,
                    verticalOscillationMm = 94.1,
                    verticalRatioPct = 9.64,
                    strideLengthMm = 979.3,
                    trainingEffect = 2.5,
                    anaerobicTrainingEffect = 0.1,
                    trainingLoadPeak = 47.07,
                ),
                lapDynamics = listOf(
                    LapDynamics(
                        index = 0,
                        cadenceSpm = 132.0,
                        groundContactTimeMs = 318.0,
                        verticalOscillationMm = 92.0,
                        strideLengthMm = 970.0,
                    ),
                ),
            )
        }
    }
}
