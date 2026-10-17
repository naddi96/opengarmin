package dev.opengarmin.app.coach

import dev.opengarmin.app.data.ActivityRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

const val LOCAL_PROFILE_ID = "local"

data class ToolScope(val profileId: String = LOCAL_PROFILE_ID)

data class DailyHealth(
    val date: String,
    val steps: Int,
    val distanceMeters: Double,
    val activeCalories: Int,
    val activeSeconds: Int,
    val restingHeartRate: Int?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val stressAvg: Int?,
    val spo2Avg: Int?,
    val respirationAvg: Double?,
)

data class HealthSample(
    val kind: String,
    val timestamp: Long,
    val value: Double,
)

data class WorkoutSummary(
    val id: Long,
    val startTime: Long,
    val sport: Int,
    val subSport: Int,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val elapsedSeconds: Double,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val calories: Int?,
    val ascentMeters: Int?,
    val descentMeters: Int?,
)

data class LapInfo(
    val index: Int,
    val durationSeconds: Double,
    val distanceMeters: Double,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val avgSpeedMps: Double?,
    val calories: Int?,
)

data class RecordPoint(
    val timestamp: Long,
    val heartRate: Int?,
    val distanceMeters: Double?,
    val speedMps: Double?,
    val altitudeMeters: Double?,
    val latitude: Double?,
    val longitude: Double?,
    val cadence: Int? = null,
    val bodyBattery: Int? = null,
)

data class WorkoutDetail(
    val summary: WorkoutSummary,
    val laps: List<LapInfo>,
    val records: List<RecordPoint>,
    val gps: GpsSummary? = null,
    val dynamics: RunningDynamicsSummary? = null,
    val lapDynamics: List<LapDynamics> = emptyList(),
)

/**
 * Read-only, profile-scoped access to health data. The Room implementation is
 * local-only today; the scope parameter keeps the tool layer honest for a
 * future multi-profile/server version and makes scoping unit-testable.
 */
interface HealthDataSource {
    suspend fun daily(scope: ToolScope, start: LocalDate, end: LocalDate): List<DailyHealth>
    suspend fun samples(scope: ToolScope, kind: String, startEpoch: Long, endEpoch: Long): List<HealthSample>
    suspend fun workouts(
        scope: ToolScope,
        startEpoch: Long,
        endEpoch: Long,
        limit: Int,
        offset: Int,
    ): List<WorkoutSummary>
    suspend fun workout(scope: ToolScope, id: Long): WorkoutDetail?
}

class RepositoryHealthDataSource(
    private val repository: ActivityRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : HealthDataSource {
    override suspend fun daily(scope: ToolScope, start: LocalDate, end: LocalDate): List<DailyHealth> {
        if (!scopeAllowed(scope)) return emptyList()
        val startText = start.toString()
        val endText = end.toString()
        return repository.wellnessDaysBetween(startText, endText).map { day ->
            DailyHealth(
                date = day.date,
                steps = day.steps,
                distanceMeters = day.distanceMeters,
                activeCalories = day.activeCalories,
                activeSeconds = day.activeSeconds,
                restingHeartRate = day.restingHeartRate?.takeIf { it in 1..250 },
                avgHeartRate = day.avgHeartRate?.takeIf { it in 1..250 },
                maxHeartRate = day.maxHeartRate?.takeIf { it in 1..250 },
                stressAvg = day.stressAvg?.takeIf { it in 0..100 },
                spo2Avg = day.spo2Avg?.takeIf { it in 1..100 },
                respirationAvg = day.respirationAvg?.takeIf { it > 0.0 },
            )
        }
    }

    override suspend fun samples(
        scope: ToolScope,
        kind: String,
        startEpoch: Long,
        endEpoch: Long,
    ): List<HealthSample> {
        if (!scopeAllowed(scope)) return emptyList()
        return repository.wellnessSamplesBetween(kind, startEpoch, endEpoch).map {
            HealthSample(it.kind, it.timestamp, it.value)
        }
    }

    override suspend fun workouts(
        scope: ToolScope,
        startEpoch: Long,
        endEpoch: Long,
        limit: Int,
        offset: Int,
    ): List<WorkoutSummary> {
        if (!scopeAllowed(scope)) return emptyList()
        return repository.activitiesBetween(startEpoch, endEpoch, limit.coerceIn(1, 500))
            .drop(offset.coerceAtLeast(0))
            .map { it.toSummary() }
    }

    override suspend fun workout(scope: ToolScope, id: Long): WorkoutDetail? {
        if (!scopeAllowed(scope)) return null
        val activity = repository.activity(id) ?: return null
        val records = repository.records(id).map {
            RecordPoint(
                timestamp = it.timestamp,
                heartRate = it.heartRate,
                distanceMeters = it.distanceMeters,
                speedMps = it.speedMps,
                altitudeMeters = it.altitudeMeters,
                latitude = it.latitude,
                longitude = it.longitude,
                cadence = it.cadence,
                bodyBattery = it.bodyBattery,
            )
        }
        val laps = repository.laps(id).map {
            LapInfo(
                index = it.lapIndex,
                durationSeconds = it.durationSeconds,
                distanceMeters = it.distanceMeters,
                avgHeartRate = it.avgHeartRate,
                maxHeartRate = it.maxHeartRate,
                avgSpeedMps = it.avgSpeedMps,
                calories = it.calories,
            )
        }
        val isRunning = categoryForSport(activity.sport, activity.subSport) == SportCategory.Run
        val sessionJson = repository.messagesByName(id, "session").lastOrNull()?.fieldsJson
        val recordJson = repository.messagesByName(id, "record").map { it.fieldsJson }
        val lapJson = repository.messagesByName(id, "lap").map { it.fieldsJson }
        val gps = FitInsights.gps(
            points = records.mapNotNull { point ->
                val lat = point.latitude ?: return@mapNotNull null
                val lon = point.longitude ?: return@mapNotNull null
                GeoPoint(lat, lon)
            },
            sessionJson = sessionJson,
        )
        return WorkoutDetail(
            summary = activity.toSummary(),
            laps = laps,
            records = records,
            gps = gps,
            dynamics = FitInsights.dynamics(recordJson, sessionJson, isRunning),
            lapDynamics = FitInsights.lapDynamics(lapJson, isRunning),
        )
    }

    private fun scopeAllowed(scope: ToolScope): Boolean = scope.profileId == LOCAL_PROFILE_ID

    private fun dev.opengarmin.app.data.ActivityEntity.toSummary() = WorkoutSummary(
        id = id,
        startTime = startTime,
        sport = sport,
        subSport = subSport,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        elapsedSeconds = elapsedSeconds,
        avgHeartRate = avgHeartRate,
        maxHeartRate = maxHeartRate,
        calories = calories,
        ascentMeters = ascentMeters,
        descentMeters = descentMeters,
    )
}

fun epochRange(start: LocalDate, end: LocalDate, zone: ZoneId = ZoneId.systemDefault()): LongRange {
    val from = start.atStartOfDay(zone).toEpochSecond()
    val to = end.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1
    return from..to
}

fun garminEpochToUnix(seconds: Long): Long = seconds + 631_065_600L

fun unixToLocalDate(seconds: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochSecond(seconds).atZone(zone).toLocalDate()
