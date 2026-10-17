package dev.opengarmin.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.opengarmin.protocol.Fit
import dev.opengarmin.protocol.Gfdi
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

@Entity(
    tableName = "activities",
    indices = [Index(value = ["sourceKey"], unique = true)],
)
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceKey: String,
    val fileName: String,
    /** GDI ItemReference UID, used to skip re-downloading imported files. */
    val itemUid: String = "",
    /** Unix seconds. */
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
    val importedAt: Long,
)

@Entity(
    tableName = "records",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("activityId")],
)
data class RecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    /** Unix seconds. */
    val timestamp: Long,
    val distanceMeters: Double?,
    val heartRate: Int?,
    val speedMps: Double?,
    val altitudeMeters: Double?,
    val cadence: Int?,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

@Entity(
    tableName = "laps",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("activityId")],
)
data class LapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val lapIndex: Int,
    /** Unix seconds, or null when the FIT file has no start time. */
    val startTime: Long?,
    val durationSeconds: Double,
    val distanceMeters: Double,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val avgSpeedMps: Double?,
    val calories: Int?,
    val ascentMeters: Int?,
    val descentMeters: Int?,
)

/** Every FIT message/field, for the data explorer and cross-plots. */
@Entity(
    tableName = "fit_messages",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("activityId"), Index(value = ["activityId", "messageName"])],
)
data class FitMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val orderIndex: Int,
    val globalNumber: Int,
    val messageName: String,
    /** Unix seconds, or null. */
    val timestamp: Long?,
    /** JSON object: field name -> scaled value. */
    val fieldsJson: String,
    /** JSON object: field name -> units. */
    val unitsJson: String,
)

data class MessageCount(val messageName: String, val cnt: Int)

@Entity(
    tableName = "wellness_days",
    indices = [Index(value = ["date"], unique = true)],
)
data class WellnessDayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Local date, YYYY-MM-DD. */
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
    val updatedAt: Long,
)

@Entity(
    tableName = "wellness_samples",
    foreignKeys = [
        ForeignKey(
            entity = WellnessDayEntity::class,
            parentColumns = ["id"],
            childColumns = ["dayId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("dayId"), Index(value = ["dayId", "timestamp"])],
)
data class WellnessSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayId: Long,
    /** Unix seconds. */
    val timestamp: Long,
    /** hr, stress, spo2 or respiration. */
    val kind: String,
    val value: Double,
)

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities ORDER BY startTime DESC")
    fun observeAll(): Flow<List<ActivityEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActivity(activity: ActivityEntity): Long

    @Insert
    suspend fun insertRecords(records: List<RecordEntity>)

    @Insert
    suspend fun insertLaps(laps: List<LapEntity>)

    @Insert
    suspend fun insertMessages(messages: List<FitMessageEntity>)

    @Query("SELECT * FROM records WHERE activityId = :activityId ORDER BY timestamp")
    suspend fun records(activityId: Long): List<RecordEntity>

    @Query("SELECT messageName, COUNT(*) AS cnt FROM fit_messages WHERE activityId = :activityId GROUP BY messageName ORDER BY cnt DESC")
    suspend fun messageCounts(activityId: Long): List<MessageCount>

    @Query("SELECT * FROM fit_messages WHERE activityId = :activityId ORDER BY orderIndex")
    suspend fun allMessages(activityId: Long): List<FitMessageEntity>

    @Query("SELECT COUNT(*) FROM fit_messages WHERE activityId = :activityId")
    suspend fun messageCount(activityId: Long): Int

    @Query("SELECT * FROM wellness_days ORDER BY date DESC")
    fun observeWellnessDays(): Flow<List<WellnessDayEntity>>

    @Query("SELECT * FROM wellness_days WHERE date BETWEEN :start AND :end ORDER BY date")
    suspend fun wellnessDaysBetween(start: String, end: String): List<WellnessDayEntity>

    @Query("SELECT * FROM wellness_samples WHERE kind = :kind AND timestamp BETWEEN :start AND :end ORDER BY timestamp")
    suspend fun wellnessSamplesBetween(kind: String, start: Long, end: Long): List<WellnessSampleEntity>

    @Query("SELECT * FROM wellness_days WHERE date = :date")
    suspend fun wellnessDay(date: String): WellnessDayEntity?

    @Insert
    suspend fun insertWellnessDay(day: WellnessDayEntity): Long

    @Update
    suspend fun updateWellnessDay(day: WellnessDayEntity)

    @Query("DELETE FROM wellness_samples WHERE dayId = :dayId")
    suspend fun deleteWellnessSamples(dayId: Long)

    @Insert
    suspend fun insertWellnessSamples(samples: List<WellnessSampleEntity>)

    @Query("SELECT * FROM wellness_samples WHERE dayId = :dayId ORDER BY timestamp")
    suspend fun wellnessSamples(dayId: Long): List<WellnessSampleEntity>

    @Query("SELECT * FROM laps WHERE activityId = :activityId ORDER BY lapIndex")
    suspend fun laps(activityId: Long): List<LapEntity>

    @Query("SELECT * FROM activities")
    suspend fun all(): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun activityById(id: Long): ActivityEntity?

    @Query("SELECT * FROM activities WHERE startTime BETWEEN :start AND :end ORDER BY startTime DESC LIMIT :limit")
    suspend fun activitiesBetween(start: Long, end: Long, limit: Int): List<ActivityEntity>

    @Query("SELECT COUNT(*) FROM laps WHERE activityId = :activityId")
    suspend fun lapCount(activityId: Long): Int

    @Query("SELECT COUNT(*) FROM records WHERE activityId = :activityId AND latitude IS NOT NULL")
    suspend fun coordCount(activityId: Long): Int

    @Query("DELETE FROM records WHERE activityId = :activityId")
    suspend fun deleteRecords(activityId: Long)

    @Query("UPDATE activities SET itemUid = :uid WHERE sourceKey = :sourceKey AND itemUid = ''")
    suspend fun backfillItemUid(sourceKey: String, uid: String)

    @Query("DELETE FROM activities")
    suspend fun clear()
}

@Database(
    entities = [
        ActivityEntity::class,
        RecordEntity::class,
        LapEntity::class,
        FitMessageEntity::class,
        WellnessDayEntity::class,
        WellnessSampleEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activities ADD COLUMN itemUid TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `laps` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`activityId` INTEGER NOT NULL, " +
                        "`lapIndex` INTEGER NOT NULL, " +
                        "`startTime` INTEGER, " +
                        "`durationSeconds` REAL NOT NULL, " +
                        "`distanceMeters` REAL NOT NULL, " +
                        "`avgHeartRate` INTEGER, " +
                        "`maxHeartRate` INTEGER, " +
                        "`avgSpeedMps` REAL, " +
                        "`calories` INTEGER, " +
                        "`ascentMeters` INTEGER, " +
                        "`descentMeters` INTEGER, " +
                        "FOREIGN KEY(`activityId`) REFERENCES `activities`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_laps_activityId` ON `laps` (`activityId`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE records ADD COLUMN longitude REAL")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fit_messages` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`activityId` INTEGER NOT NULL, " +
                        "`orderIndex` INTEGER NOT NULL, " +
                        "`globalNumber` INTEGER NOT NULL, " +
                        "`messageName` TEXT NOT NULL, " +
                        "`timestamp` INTEGER, " +
                        "`fieldsJson` TEXT NOT NULL, " +
                        "`unitsJson` TEXT NOT NULL, " +
                        "FOREIGN KEY(`activityId`) REFERENCES `activities`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_fit_messages_activityId` " +
                        "ON `fit_messages` (`activityId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_fit_messages_activityId_messageName` " +
                        "ON `fit_messages` (`activityId`, `messageName`)",
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wellness_days` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`date` TEXT NOT NULL, " +
                        "`steps` INTEGER NOT NULL, " +
                        "`distanceMeters` REAL NOT NULL, " +
                        "`activeCalories` INTEGER NOT NULL, " +
                        "`activeSeconds` INTEGER NOT NULL, " +
                        "`restingHeartRate` INTEGER, " +
                        "`avgHeartRate` INTEGER, " +
                        "`maxHeartRate` INTEGER, " +
                        "`stressAvg` INTEGER, " +
                        "`spo2Avg` INTEGER, " +
                        "`respirationAvg` REAL, " +
                        "`updatedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_wellness_days_date` " +
                        "ON `wellness_days` (`date`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wellness_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`dayId` INTEGER NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`value` REAL NOT NULL, " +
                        "FOREIGN KEY(`dayId`) REFERENCES `wellness_days`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wellness_samples_dayId` " +
                        "ON `wellness_samples` (`dayId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wellness_samples_dayId_timestamp` " +
                        "ON `wellness_samples` (`dayId`, `timestamp`)",
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "opengarmin.db",
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                ).build().also { instance = it }
            }
    }
}

class ActivityRepository(private val dao: ActivityDao) {
    val activities: Flow<List<ActivityEntity>> = dao.observeAll()
    val wellnessDays: Flow<List<WellnessDayEntity>> = dao.observeWellnessDays()

    suspend fun records(activityId: Long): List<RecordEntity> = dao.records(activityId)

    suspend fun laps(activityId: Long): List<LapEntity> = dao.laps(activityId)

    suspend fun messageCounts(activityId: Long): List<MessageCount> = dao.messageCounts(activityId)

    suspend fun allMessages(activityId: Long): List<FitMessageEntity> = dao.allMessages(activityId)

    suspend fun wellnessSamples(dayId: Long): List<WellnessSampleEntity> = dao.wellnessSamples(dayId)

    suspend fun wellnessDaysBetween(start: String, end: String): List<WellnessDayEntity> =
        dao.wellnessDaysBetween(start, end)

    suspend fun wellnessSamplesBetween(kind: String, start: Long, end: Long): List<WellnessSampleEntity> =
        dao.wellnessSamplesBetween(kind, start, end)

    suspend fun activitiesBetween(start: Long, end: Long, limit: Int): List<ActivityEntity> =
        dao.activitiesBetween(start, end, limit)

    suspend fun activity(id: Long): ActivityEntity? = dao.activityById(id)

    suspend fun clear() = dao.clear()

    /**
     * Adds laps and GPS coordinates to activities imported before those
     * columns existed, using the decoded FIT files still in the app's files
     * directory.
     */
    suspend fun backfillDerived(filesDir: File) {
        for (activity in dao.all()) {
            val needLaps = dao.lapCount(activity.id) == 0
            val needCoords = dao.coordCount(activity.id) == 0
            val needMessages = dao.messageCount(activity.id) == 0
            if (!needLaps && !needCoords && !needMessages) continue
            val file = File(filesDir, activity.fileName)
            if (!file.exists()) continue
            val parsed = runCatching { Fit.parse(file.readBytes()) }.getOrNull() ?: continue
            if (needLaps) {
                val laps = lapEntities(activity.id, parsed)
                if (laps.isNotEmpty()) dao.insertLaps(laps)
            }
            if (needCoords) {
                val records = recordEntities(activity.id, parsed)
                if (records.isNotEmpty()) {
                    dao.deleteRecords(activity.id)
                    dao.insertRecords(records)
                }
            }
            if (needMessages) {
                val messages = messageEntities(activity.id, parsed)
                if (messages.isNotEmpty()) dao.insertMessages(messages)
            }
        }
    }

    /**
     * Parses a decoded FIT file and stores the activity plus its record
     * samples. Returns null when the file has no session or was imported
     * before (same start time, distance and sport).
     */
    suspend fun importFit(fileName: String, data: ByteArray, itemUid: String = ""): ActivityEntity? {
        val parsed = Fit.parse(data)
        val session = parsed.session ?: return null
        val startRaw = session.startTime ?: session.timestamp ?: return null
        val startUnix = startRaw + Gfdi.GARMIN_EPOCH_UNIX
        val distance = session.distanceMeters
            ?: parsed.records.lastOrNull()?.distanceMeters
            ?: 0.0
        val sport = session.sport ?: 0
        val sourceKey = "$startUnix-${(distance * 100).roundToInt()}-$sport"

        val entity = ActivityEntity(
            sourceKey = sourceKey,
            fileName = fileName,
            itemUid = itemUid,
            startTime = startUnix,
            sport = sport,
            subSport = session.subSport ?: 0,
            distanceMeters = distance,
            durationSeconds = session.totalTimerSeconds
                ?: session.totalElapsedSeconds
                ?: parsed.activity?.totalTimerSeconds
                ?: 0.0,
            elapsedSeconds = session.totalElapsedSeconds ?: session.totalTimerSeconds ?: 0.0,
            avgHeartRate = session.avgHeartRate,
            maxHeartRate = session.maxHeartRate,
            calories = session.calories,
            ascentMeters = session.ascentMeters,
            descentMeters = session.descentMeters,
            importedAt = System.currentTimeMillis() / 1000,
        )

        val id = dao.insertActivity(entity)
        if (id == -1L) {
            // Already imported: remember the GDI UID so the next sync can
            // skip the download entirely.
            if (itemUid.isNotEmpty()) dao.backfillItemUid(sourceKey, itemUid)
            return null
        }
        val records = recordEntities(id, parsed)
        if (records.isNotEmpty()) dao.insertRecords(records)
        val laps = lapEntities(id, parsed)
        if (laps.isNotEmpty()) dao.insertLaps(laps)
        val messages = messageEntities(id, parsed)
        if (messages.isNotEmpty()) dao.insertMessages(messages)
        return entity.copy(id = id)
    }

    private fun messageEntities(activityId: Long, parsed: Fit.Parsed): List<FitMessageEntity> =
        parsed.messages.mapIndexed { index, message ->
            val fields = JSONObject()
            val units = JSONObject()
            for (field in message.fields) {
                when (val value = field.value) {
                    is Double -> if (value.isFinite()) fields.put(field.name, value)
                    is Float -> if (value.isFinite()) fields.put(field.name, value.toDouble())
                    is Number -> fields.put(field.name, value)
                    null -> Unit
                    else -> fields.put(field.name, value.toString())
                }
                field.units?.let { units.put(field.name, it) }
            }
            FitMessageEntity(
                activityId = activityId,
                orderIndex = index,
                globalNumber = message.globalNumber,
                messageName = message.name,
                timestamp = message.timestamp,
                fieldsJson = fields.toString(),
                unitsJson = units.toString(),
            )
        }

    /**
     * Imports a watch wellness file (monitoring_b / monitoring_daily) into the
     * daily metrics tables. Re-cutting the same day updates the row and
     * replaces its samples.
     */
    suspend fun importWellness(data: ByteArray): WellnessDayEntity? {
        val parsed = runCatching { Fit.parse(data) }.getOrNull() ?: return null
        val messages = parsed.messages

        val baseTs = messages.mapNotNull { it.timestamp }.maxOrNull() ?: return null

        val monitoring = messages.filter { message ->
            message.name == "monitoring" && (
                msgField(message, "steps") != null ||
                    msgField(message, "cycles_16") != null ||
                    msgField(message, "distance") != null ||
                    msgField(message, "active_time") != null ||
                    msgField(message, "active_calories") != null ||
                    msgField(message, "calories") != null
                )
        }
        val hrSamples = messages.filter { it.name == "monitoring" }.mapNotNull { message ->
            val hr = msgInt(message, "heart_rate")?.takeIf { it in 1..250 } ?: return@mapNotNull null
            val ts = message.timestamp
                ?: msgInt(message, "timestamp_16")?.let { resolveTimestamp16(baseTs, it) }
                ?: baseTs
            ts to hr
        }
        if (monitoring.isEmpty() && hrSamples.isEmpty()) return null

        val date = Instant.ofEpochSecond(baseTs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()

        val lastByType = LinkedHashMap<Int, Fit.Message>()
        for (message in monitoring) {
            lastByType[msgInt(message, "activity_type") ?: 0] = message
        }
        var steps = 0
        var distance = 0.0
        var activeCalories = 0
        var activeSeconds = 0
        for (message in lastByType.values) {
            steps += msgInt(message, "steps")
                ?: msgInt(message, "cycles_16")?.times(2)
                ?: msgDouble(message, "cycles")?.times(2)?.toInt()
                ?: 0
            distance += msgDouble(message, "distance") ?: 0.0
            activeCalories += msgInt(message, "active_calories") ?: msgInt(message, "calories") ?: 0
            activeSeconds += msgDouble(message, "active_time")?.toInt() ?: 0
        }

        val restingHeartRate = messages.filter { it.name == "monitoring_hr_data" }
            .lastOrNull()
            ?.let {
                (msgInt(it, "current_day_resting_heart_rate") ?: msgInt(it, "resting_heart_rate"))
                    ?.takeIf { value -> value in 1..250 }
            }

        val stress = messages.filter { it.name == "stress_level" }.mapNotNull { message ->
            val value = msgInt(message, "stress_level_value")?.takeIf { it in 0..100 }
                ?: return@mapNotNull null
            val ts = msgDouble(message, "stress_level_time")?.toLong()
                ?: message.timestamp
                ?: baseTs
            WellnessSampleEntity(dayId = 0, timestamp = ts, kind = "stress", value = value.toDouble())
        }
        val spo2 = messages.filter { it.name == "spo2_data" }.mapNotNull { message ->
            val value = msgInt(message, "reading_spo2")?.takeIf { it in 1..100 }
                ?: return@mapNotNull null
            WellnessSampleEntity(
                dayId = 0,
                timestamp = message.timestamp ?: baseTs,
                kind = "spo2",
                value = value.toDouble(),
            )
        }
        val respiration = messages.filter { it.name == "respiration_rate" }.mapNotNull { message ->
            val value = msgDouble(message, "respiration_rate")?.takeIf { it > 0.0 }
                ?: return@mapNotNull null
            WellnessSampleEntity(
                dayId = 0,
                timestamp = message.timestamp ?: baseTs,
                kind = "respiration",
                value = value,
            )
        }
        val heartRateSamples = hrSamples.map { (ts, value) ->
            WellnessSampleEntity(dayId = 0, timestamp = ts, kind = "hr", value = value.toDouble())
        }
        val samples = (heartRateSamples + stress + spo2 + respiration).sortedBy { it.timestamp }

        val hrValues = hrSamples.map { it.second }
        val entity = WellnessDayEntity(
            date = date,
            steps = steps,
            distanceMeters = distance,
            activeCalories = activeCalories,
            activeSeconds = activeSeconds,
            restingHeartRate = restingHeartRate,
            avgHeartRate = hrValues.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            maxHeartRate = hrValues.maxOrNull(),
            stressAvg = stress.map { it.value }.average().takeIf { !it.isNaN() }?.roundToInt(),
            spo2Avg = spo2.map { it.value }.average().takeIf { !it.isNaN() }?.roundToInt(),
            respirationAvg = respiration.map { it.value }.average().takeIf { !it.isNaN() },
            updatedAt = System.currentTimeMillis() / 1000,
        )

        val existing = dao.wellnessDay(date)
        val id = if (existing == null) {
            dao.insertWellnessDay(entity)
        } else {
            dao.updateWellnessDay(entity.copy(id = existing.id))
            existing.id
        }
        val existingKeys = dao.wellnessSamples(id).map { it.kind to it.timestamp }.toHashSet()
        val freshSamples = samples
            .filter { (it.kind to it.timestamp) !in existingKeys }
            .map { it.copy(dayId = id) }
        if (freshSamples.isNotEmpty()) dao.insertWellnessSamples(freshSamples)
        return entity.copy(id = id)
    }

    private fun resolveTimestamp16(reference: Long, raw: Int): Long {
        val mask = 0xFFFFL
        var value = (reference and mask.inv()) or (raw.toLong() and mask)
        if (value < reference - 0x8000L) value += 0x10000L
        else if (value > reference + 0x8000L) value -= 0x10000L
        return value
    }

    private fun msgField(message: Fit.Message, name: String): Any? =
        message.fields.firstOrNull { it.name == name }?.value

    private fun msgInt(message: Fit.Message, name: String): Int? =
        (msgField(message, name) as? Number)?.toInt()

    private fun msgDouble(message: Fit.Message, name: String): Double? =
        (msgField(message, name) as? Number)?.toDouble()

    private fun recordEntities(activityId: Long, parsed: Fit.Parsed): List<RecordEntity> =
        parsed.records.map { record ->
            RecordEntity(
                activityId = activityId,
                timestamp = record.timestamp + Gfdi.GARMIN_EPOCH_UNIX,
                distanceMeters = record.distanceMeters,
                heartRate = record.heartRate,
                speedMps = record.speedMps,
                altitudeMeters = record.altitudeMeters,
                cadence = record.cadence,
                latitude = record.latitude,
                longitude = record.longitude,
            )
        }

    private fun lapEntities(activityId: Long, parsed: Fit.Parsed): List<LapEntity> =
        parsed.laps.mapIndexed { index, lap ->
            LapEntity(
                activityId = activityId,
                lapIndex = index,
                startTime = (lap.startTime ?: lap.timestamp)?.plus(Gfdi.GARMIN_EPOCH_UNIX),
                durationSeconds = lap.totalTimerSeconds ?: lap.totalElapsedSeconds ?: 0.0,
                distanceMeters = lap.distanceMeters ?: 0.0,
                avgHeartRate = lap.avgHeartRate,
                maxHeartRate = lap.maxHeartRate,
                avgSpeedMps = lap.avgSpeedMps,
                calories = lap.calories,
                ascentMeters = lap.ascentMeters,
                descentMeters = lap.descentMeters,
            )
        }
}
