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
    /** Garmin Body Battery (undocumented FIT record field 143), 0-100. */
    val bodyBattery: Int? = null,
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

data class MessageCount(val messageName: String, val count: Int)

data class DailySampleAverage(val date: String, val value: Double)

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
    // Body Battery (from stress_level field 3 and/or activity records).
    val bodyBatteryStart: Int? = null,
    val bodyBatteryMin: Int? = null,
    val bodyBatteryMax: Int? = null,
    val bodyBatteryLatest: Int? = null,
    // Sleep (from a SLEEP_DATA file when the watch exposes one).
    val sleepStart: Long? = null,
    val sleepEnd: Long? = null,
    val sleepScore: Int? = null,
    val sleepQuality: String? = null,
    val deepSleepSeconds: Int? = null,
    val lightSleepSeconds: Int? = null,
    val remSleepSeconds: Int? = null,
    val awakeSleepSeconds: Int? = null,
    val sleepAvgSpo2: Int? = null,
    val sleepLowestSpo2: Int? = null,
    val sleepAvgRespiration: Double? = null,
    val sleepLowestHeartRate: Int? = null,
    // HRV status (from hrv_status_summary / hrv_value).
    val hrvLastNight: Double? = null,
    val hrvWeeklyAvg: Double? = null,
    val hrvStatus: String? = null,
    val hrvBaselineLow: Double? = null,
    val hrvBaselineHigh: Double? = null,
    // Intensity minutes reported by the watch monitoring file, when available.
    val moderateMinutes: Int? = null,
    val vigorousMinutes: Int? = null,
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
    /** hr, stress, spo2, respiration, body_battery or hrv. */
    val kind: String,
    val value: Double,
)

/** One sleep-stage transition from a watch SLEEP_DATA file. */
@Entity(
    tableName = "sleep_stages",
    indices = [Index("date")],
)
data class SleepStageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Local wake-up date, YYYY-MM-DD. */
    val date: String,
    /** Unix seconds. */
    val startTime: Long,
    /** Unix seconds. */
    val endTime: Long,
    /** awake, light, deep, rem or unmeasurable. */
    val stage: String,
)

/** Files already imported so a sync can skip them without re-downloading. */
@Entity(tableName = "imported_wellness_files")
data class ImportedWellnessFileEntity(
    @PrimaryKey val itemUid: String,
    val type: String,
    val importedAt: Long,
)

/**
 * Every activity file UID this app has pulled, including UIDs that belong to an
 * activity already stored under a different UID. Keeping the ledger separate
 * from `activities.itemUid` means a watch re-index or a second watch cannot
 * make the app re-download an activity it already has: the new UID is added
 * alongside the old one instead of replacing it.
 */
@Entity(tableName = "imported_activity_files")
data class ImportedActivityFileEntity(
    @PrimaryKey val itemUid: String,
    val importedAt: Long,
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

    @Query("SELECT messageName, COUNT(*) AS count FROM fit_messages WHERE activityId = :activityId GROUP BY messageName ORDER BY count DESC")
    suspend fun messageCounts(activityId: Long): List<MessageCount>

    @Query("SELECT * FROM fit_messages WHERE activityId = :activityId ORDER BY orderIndex")
    suspend fun allMessages(activityId: Long): List<FitMessageEntity>

    @Query("SELECT * FROM fit_messages WHERE activityId = :activityId AND messageName = :messageName ORDER BY orderIndex")
    suspend fun messagesByName(activityId: Long, messageName: String): List<FitMessageEntity>

    @Query("SELECT * FROM fit_messages WHERE activityId = :activityId AND messageName IN (:messageNames) ORDER BY orderIndex")
    suspend fun messagesByNames(activityId: Long, messageNames: List<String>): List<FitMessageEntity>

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

    @Query(
        "SELECT d.date AS date, AVG(s.value) AS value FROM wellness_samples s " +
            "JOIN wellness_days d ON d.id = s.dayId " +
            "WHERE s.kind = :kind AND s.value > 0 GROUP BY d.date",
    )
    suspend fun dailySampleAverages(kind: String): List<DailySampleAverage>

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

    @Query("SELECT * FROM wellness_samples WHERE dayId = :dayId AND kind = :kind ORDER BY timestamp")
    suspend fun wellnessSamples(dayId: Long, kind: String): List<WellnessSampleEntity>

    @Query("DELETE FROM sleep_stages WHERE date = :date")
    suspend fun deleteSleepStages(date: String)

    @Insert
    suspend fun insertSleepStages(stages: List<SleepStageEntity>)

    @Query("SELECT * FROM sleep_stages WHERE date = :date ORDER BY startTime")
    suspend fun sleepStages(date: String): List<SleepStageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImportedWellnessFile(file: ImportedWellnessFileEntity)

    @Query("SELECT itemUid FROM imported_wellness_files")
    suspend fun importedWellnessFileIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImportedActivityFile(file: ImportedActivityFileEntity)

    @Query("SELECT itemUid FROM imported_activity_files")
    suspend fun importedActivityFileIds(): List<String>

    @Query("SELECT * FROM laps WHERE activityId = :activityId ORDER BY lapIndex")
    suspend fun laps(activityId: Long): List<LapEntity>

    @Query("SELECT * FROM activities")
    suspend fun all(): List<ActivityEntity>

    @Query("SELECT itemUid FROM activities WHERE itemUid != ''")
    suspend fun activityItemUids(): List<String>

    @Query(
        "SELECT a.id AS activityId, a.startTime AS startTime, " +
            "f.messageName AS messageName, f.fieldsJson AS fieldsJson " +
            "FROM fit_messages f JOIN activities a ON a.id = f.activityId " +
            "WHERE f.messageName IN ('activity_metrics','msg_140','user_metrics','msg_79','session','zones_target') " +
            "ORDER BY a.startTime",
    )
    suspend fun trainingRows(): List<TrainingRow>

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

    /**
     * Stores the watch's current UID for an activity. Called for new imports and
     * for duplicates: a watch re-index or a different watch can report a new UID
     * for an activity that is already in Room, and keeping the stale UID would
     * make every sync download that activity again.
     */
    @Query("UPDATE activities SET itemUid = :uid WHERE sourceKey = :sourceKey")
    suspend fun rememberItemUid(sourceKey: String, uid: String)

    @Query("DELETE FROM activities")
    suspend fun clear()
}

@Dao
interface PacePlanDao {
    @Query("SELECT * FROM pace_plans ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PacePlanEntity>>

    @Query("SELECT * FROM pace_plans WHERE id = :id")
    suspend fun byId(id: Long): PacePlanEntity?

    @Query("SELECT * FROM pace_plans ORDER BY updatedAt DESC")
    suspend fun all(): List<PacePlanEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(plan: PacePlanEntity): Long

    @Update
    suspend fun update(plan: PacePlanEntity)

    @Query("DELETE FROM pace_plans WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pace_plans SET lastSentAt = :timestamp, updatedAt = :timestamp WHERE id = :id")
    suspend fun markSent(id: Long, timestamp: Long)
}

@Dao
interface WorkoutDao {
    @Query(
        "SELECT * FROM planned_workouts " +
            "ORDER BY CASE WHEN scheduledDate IS NULL THEN 1 ELSE 0 END, scheduledDate, updatedAt DESC",
    )
    fun observeAll(): Flow<List<PlannedWorkoutEntity>>

    @Query("SELECT * FROM planned_workouts WHERE id = :id")
    suspend fun byId(id: Long): PlannedWorkoutEntity?

    @Query(
        "SELECT * FROM planned_workouts " +
            "ORDER BY CASE WHEN scheduledDate IS NULL THEN 1 ELSE 0 END, scheduledDate, updatedAt DESC",
    )
    suspend fun all(): List<PlannedWorkoutEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(workout: PlannedWorkoutEntity): Long

    @Update
    suspend fun update(workout: PlannedWorkoutEntity)

    @Query("DELETE FROM planned_workouts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE planned_workouts SET lastSentAt = :timestamp, updatedAt = :timestamp WHERE id = :id")
    suspend fun markSent(id: Long, timestamp: Long)
}

@Database(
    entities = [
        ActivityEntity::class,
        RecordEntity::class,
        LapEntity::class,
        FitMessageEntity::class,
        WellnessDayEntity::class,
        WellnessSampleEntity::class,
        SleepStageEntity::class,
        ImportedWellnessFileEntity::class,
        ImportedActivityFileEntity::class,
        PlannedWorkoutEntity::class,
        PacePlanEntity::class,
    ],
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun pacePlanDao(): PacePlanDao

    companion object {
        const val DB_NAME = "opengarmin.db"
        const val SCHEMA_VERSION = 10

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

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "bodyBatteryStart INTEGER",
                    "bodyBatteryMin INTEGER",
                    "bodyBatteryMax INTEGER",
                    "bodyBatteryLatest INTEGER",
                    "sleepStart INTEGER",
                    "sleepEnd INTEGER",
                    "sleepScore INTEGER",
                    "sleepQuality TEXT",
                    "deepSleepSeconds INTEGER",
                    "lightSleepSeconds INTEGER",
                    "remSleepSeconds INTEGER",
                    "awakeSleepSeconds INTEGER",
                    "sleepAvgSpo2 INTEGER",
                    "sleepLowestSpo2 INTEGER",
                    "sleepAvgRespiration REAL",
                    "sleepLowestHeartRate INTEGER",
                    "hrvLastNight REAL",
                    "hrvWeeklyAvg REAL",
                    "hrvStatus TEXT",
                    "hrvBaselineLow REAL",
                    "hrvBaselineHigh REAL",
                    "moderateMinutes INTEGER",
                    "vigorousMinutes INTEGER",
                ).forEach { column ->
                    db.execSQL("ALTER TABLE `wellness_days` ADD COLUMN $column")
                }
                db.execSQL("ALTER TABLE `records` ADD COLUMN `bodyBattery` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sleep_stages` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`date` TEXT NOT NULL, " +
                        "`startTime` INTEGER NOT NULL, " +
                        "`endTime` INTEGER NOT NULL, " +
                        "`stage` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sleep_stages_date` ON `sleep_stages` (`date`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `imported_wellness_files` (" +
                        "`itemUid` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`importedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`itemUid`))",
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `planned_workouts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`sport` INTEGER NOT NULL, " +
                        "`subSport` INTEGER NOT NULL, " +
                        "`notes` TEXT NOT NULL, " +
                        "`scheduledDate` TEXT, " +
                        "`stepsJson` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`lastSentAt` INTEGER)",
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pace_plans` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`race` TEXT NOT NULL, " +
                        "`distanceMeters` REAL NOT NULL, " +
                        "`goalSeconds` INTEGER NOT NULL, " +
                        "`courseName` TEXT NOT NULL, " +
                        "`courseActivityId` INTEGER, " +
                        "`splitSlider` INTEGER NOT NULL, " +
                        "`hillSlider` INTEGER NOT NULL, " +
                        "`alert` TEXT NOT NULL, " +
                        "`splitsJson` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`lastSentAt` INTEGER)",
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `imported_activity_files` (" +
                        "`itemUid` TEXT NOT NULL, " +
                        "`importedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`itemUid`))",
                )
                // Seed the ledger with the UID already stored on each activity.
                db.execSQL(
                    "INSERT OR IGNORE INTO `imported_activity_files` (`itemUid`, `importedAt`) " +
                        "SELECT `itemUid`, `importedAt` FROM `activities` WHERE `itemUid` != ''",
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
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                ).build().also { instance = it }
            }

        /**
         * Replaces the live database with [source] and reopens it, running any
         * required migrations. The previous database is kept until the new one
         * opens successfully, then removed. Callers must stop observing flows
         * before calling this and restart them afterwards.
         */
        fun replaceDatabase(context: Context, source: File) {
            synchronized(this) {
                val dbFile = context.getDatabasePath(DB_NAME)
                dbFile.parentFile?.mkdirs()
                instance?.close()
                instance = null

                val safety = File(dbFile.path + ".restore")
                if (dbFile.exists()) dbFile.copyTo(safety, overwrite = true)
                try {
                    File(dbFile.path + "-wal").delete()
                    File(dbFile.path + "-shm").delete()
                    source.copyTo(dbFile, overwrite = true)
                    // Force Room to open the file now, so migrations and the
                    // schema identity are validated before the old file is gone.
                    get(context).openHelper.writableDatabase
                } catch (t: Throwable) {
                    runCatching { instance?.close() }
                    instance = null
                    if (safety.exists()) {
                        runCatching { safety.copyTo(dbFile, overwrite = true) }
                        File(dbFile.path + "-wal").delete()
                        File(dbFile.path + "-shm").delete()
                        runCatching { get(context) }
                    }
                    throw t
                } finally {
                    safety.delete()
                }
            }
        }
    }
}

class ActivityRepository(private val database: () -> AppDatabase) {
    private val dao: ActivityDao get() = database().activityDao()
    private val workoutDao: WorkoutDao get() = database().workoutDao()
    private val pacePlanDao: PacePlanDao get() = database().pacePlanDao()

    val activities: Flow<List<ActivityEntity>> get() = dao.observeAll()
    val wellnessDays: Flow<List<WellnessDayEntity>> get() = dao.observeWellnessDays()
    val plannedWorkouts: Flow<List<PlannedWorkoutEntity>> get() = workoutDao.observeAll()
    val pacePlans: Flow<List<PacePlanEntity>> get() = pacePlanDao.observeAll()

    suspend fun records(activityId: Long): List<RecordEntity> = dao.records(activityId)

    suspend fun laps(activityId: Long): List<LapEntity> = dao.laps(activityId)

    suspend fun messageCounts(activityId: Long): List<MessageCount> = dao.messageCounts(activityId)

    suspend fun allMessages(activityId: Long): List<FitMessageEntity> = dao.allMessages(activityId)

    suspend fun messagesByName(activityId: Long, messageName: String): List<FitMessageEntity> =
        dao.messagesByName(activityId, messageName)

    suspend fun messagesByNames(activityId: Long, messageNames: List<String>): List<FitMessageEntity> =
        dao.messagesByNames(activityId, messageNames)

    /**
     * Builds the Garmin Connect-style detail model for one activity. Only the
     * messages needed for statistics and charts are loaded.
     */
    suspend fun activityInsights(activityId: Long): ActivityInsights? {
        val activity = dao.activityById(activityId) ?: return null
        val records = dao.records(activityId)
        val laps = dao.laps(activityId)
        val messages = dao.messagesByNames(
            activityId,
            listOf("session", "activity_metrics", "msg_140", "user_metrics", "msg_79", "time_in_zone", "lap", "record"),
        )
        return ActivityAnalysis.build(activity, records, laps, messages)
    }

    suspend fun wellnessSamples(dayId: Long): List<WellnessSampleEntity> = dao.wellnessSamples(dayId)

    suspend fun wellnessSamples(dayId: Long, kind: String): List<WellnessSampleEntity> =
        dao.wellnessSamples(dayId, kind)

    suspend fun sleepStages(date: String): List<SleepStageEntity> = dao.sleepStages(date)

    suspend fun importedWellnessFileIds(): Set<String> = dao.importedWellnessFileIds().toHashSet()

    suspend fun activityItemUids(): Set<String> =
        (dao.activityItemUids() + dao.importedActivityFileIds()).toHashSet()

    suspend fun wellnessDaysBetween(start: String, end: String): List<WellnessDayEntity> =
        dao.wellnessDaysBetween(start, end)

    suspend fun dailySampleAverages(kind: String): List<DailySampleAverage> =
        dao.dailySampleAverages(kind)

    /**
     * Fills `hrvLastNight` for days that only have `hrv_value` samples. Older
     * imports stored those samples without promoting them to the daily row, so
     * the HRV card appeared empty even though the data was present.
     */
    suspend fun backfillHrvFromSamples() {
        for (row in dao.dailySampleAverages("hrv")) {
            val day = dao.wellnessDay(row.date) ?: continue
            if (day.hrvLastNight == null && row.value > 0.0) {
                dao.updateWellnessDay(day.copy(hrvLastNight = row.value))
            }
        }
    }

    suspend fun wellnessSamplesBetween(kind: String, start: Long, end: Long): List<WellnessSampleEntity> =
        dao.wellnessSamplesBetween(kind, start, end)

    suspend fun activitiesBetween(start: Long, end: Long, limit: Int): List<ActivityEntity> =
        dao.activitiesBetween(start, end, limit)

    suspend fun trainingRows(): List<TrainingRow> = dao.trainingRows()

    suspend fun activity(id: Long): ActivityEntity? = dao.activityById(id)

    suspend fun saveWorkout(workout: PlannedWorkoutEntity): PlannedWorkoutEntity {
        val now = System.currentTimeMillis() / 1000
        return if (workout.id == 0L) {
            val id = workoutDao.insert(workout.copy(createdAt = now, updatedAt = now))
            workout.copy(id = id, createdAt = now, updatedAt = now)
        } else {
            workoutDao.update(workout.copy(updatedAt = now))
            workout.copy(updatedAt = now)
        }
    }

    suspend fun deleteWorkout(id: Long) = workoutDao.delete(id)

    suspend fun markWorkoutSent(id: Long) {
        workoutDao.markSent(id, System.currentTimeMillis() / 1000)
    }

    suspend fun workout(id: Long): PlannedWorkoutEntity? = workoutDao.byId(id)

    suspend fun plannedWorkoutsOnce(): List<PlannedWorkoutEntity> = workoutDao.all()

    suspend fun savePacePlan(plan: PacePlanEntity): PacePlanEntity {
        val now = System.currentTimeMillis() / 1000
        return if (plan.id == 0L) {
            val id = pacePlanDao.insert(plan.copy(createdAt = now, updatedAt = now))
            plan.copy(id = id, createdAt = now, updatedAt = now)
        } else {
            pacePlanDao.update(plan.copy(updatedAt = now))
            plan.copy(updatedAt = now)
        }
    }

    suspend fun deletePacePlan(id: Long) = pacePlanDao.delete(id)

    suspend fun markPacePlanSent(id: Long) {
        pacePlanDao.markSent(id, System.currentTimeMillis() / 1000)
    }

    suspend fun pacePlan(id: Long): PacePlanEntity? = pacePlanDao.byId(id)

    suspend fun pacePlansOnce(): List<PacePlanEntity> = pacePlanDao.all()

    /** Builds an elevation profile from the records of one recorded run. */
    suspend fun paceCourse(activityId: Long): PaceCourse? {
        val activity = dao.activityById(activityId) ?: return null
        val records = dao.records(activityId)
        return PacePlanCalculator.courseFromActivity(activity, records)
    }

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
            // skip the download entirely. The watch may report a different UID
            // than the one stored earlier (re-index, firmware update, or a
            // different watch), so keep both: update the activity row and add
            // the UID to the imported-file ledger.
            if (itemUid.isNotEmpty()) {
                dao.rememberItemUid(sourceKey, itemUid)
                markActivityFileImported(itemUid)
            }
            return null
        }
        val records = recordEntities(id, parsed)
        if (records.isNotEmpty()) dao.insertRecords(records)
        val laps = lapEntities(id, parsed)
        if (laps.isNotEmpty()) dao.insertLaps(laps)
        val messages = messageEntities(id, parsed)
        if (messages.isNotEmpty()) dao.insertMessages(messages)
        markActivityFileImported(itemUid)
        return entity.copy(id = id)
    }

    private suspend fun markActivityFileImported(itemUid: String) {
        if (itemUid.isEmpty()) return
        dao.insertImportedActivityFile(
            ImportedActivityFileEntity(itemUid = itemUid, importedAt = nowSeconds()),
        )
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
     * Imports any watch wellness file: monitoring (steps/HR/stress/SpO2/
     * respiration/Body Battery), sleep data, or HRV. Re-importing a file
     * updates the same day and merges samples by (kind, timestamp).
     *
     * Returns the days that were created or updated.
     */
    suspend fun importWellness(data: ByteArray): List<WellnessDayEntity> {
        val parsed = runCatching { Fit.parse(data) }.getOrNull() ?: return emptyList()
        val drafts = WellnessParser.parse(parsed.messages)
        if (drafts.isEmpty()) return emptyList()

        return drafts.map { draft -> importWellnessDay(draft) }
    }

    private suspend fun importWellnessDay(draft: WellnessDayDraft): WellnessDayEntity {
        val existing = dao.wellnessDay(draft.date)
        val entity = existing?.mergedWith(draft) ?: draft.toEntity()
        val id = if (existing == null) {
            dao.insertWellnessDay(entity)
        } else {
            dao.updateWellnessDay(entity)
            existing.id
        }
        mergeWellnessSamples(id, draft.samples)
        replaceSleepStages(draft)
        return recomputeWellnessAggregates(id, entity)
    }

    private fun WellnessDayDraft.toEntity(): WellnessDayEntity = WellnessDayEntity(
        date = date,
        steps = steps,
        distanceMeters = distanceMeters,
        activeCalories = activeCalories,
        activeSeconds = activeSeconds,
        restingHeartRate = restingHeartRate,
        avgHeartRate = avgHeartRate,
        maxHeartRate = maxHeartRate,
        stressAvg = stressAvg,
        spo2Avg = spo2Avg,
        respirationAvg = respirationAvg,
        updatedAt = nowSeconds(),
        bodyBatteryStart = bodyBatteryStart,
        bodyBatteryMin = bodyBatteryMin,
        bodyBatteryMax = bodyBatteryMax,
        bodyBatteryLatest = bodyBatteryLatest,
        sleepStart = sleepStart,
        sleepEnd = sleepEnd,
        sleepScore = sleepScore,
        sleepQuality = sleepQuality,
        deepSleepSeconds = deepSleepSeconds,
        lightSleepSeconds = lightSleepSeconds,
        remSleepSeconds = remSleepSeconds,
        awakeSleepSeconds = awakeSleepSeconds,
        sleepAvgSpo2 = sleepAvgSpo2,
        sleepLowestSpo2 = sleepLowestSpo2,
        sleepAvgRespiration = sleepAvgRespiration,
        sleepLowestHeartRate = sleepLowestHeartRate,
        hrvLastNight = hrvLastNight,
        hrvWeeklyAvg = hrvWeeklyAvg,
        hrvStatus = hrvStatus,
        hrvBaselineLow = hrvBaselineLow,
        hrvBaselineHigh = hrvBaselineHigh,
        moderateMinutes = moderateMinutes,
        vigorousMinutes = vigorousMinutes,
    )

    /**
     * Separate wellness files carry separate metrics. Only replace a value
     * when this file actually contains one; never zero-fill.
     */
    private fun WellnessDayEntity.mergedWith(draft: WellnessDayDraft): WellnessDayEntity = copy(
        steps = if (draft.hasMonitoring) draft.steps else steps,
        distanceMeters = if (draft.hasMonitoring) draft.distanceMeters else distanceMeters,
        activeCalories = if (draft.hasMonitoring) draft.activeCalories else activeCalories,
        activeSeconds = if (draft.hasMonitoring) draft.activeSeconds else activeSeconds,
        restingHeartRate = draft.restingHeartRate ?: restingHeartRate,
        avgHeartRate = draft.avgHeartRate ?: avgHeartRate,
        maxHeartRate = draft.maxHeartRate ?: maxHeartRate,
        stressAvg = draft.stressAvg ?: stressAvg,
        spo2Avg = draft.spo2Avg ?: spo2Avg,
        respirationAvg = draft.respirationAvg ?: respirationAvg,
        bodyBatteryStart = draft.bodyBatteryStart ?: bodyBatteryStart,
        bodyBatteryMin = draft.bodyBatteryMin ?: bodyBatteryMin,
        bodyBatteryMax = draft.bodyBatteryMax ?: bodyBatteryMax,
        bodyBatteryLatest = draft.bodyBatteryLatest ?: bodyBatteryLatest,
        sleepStart = draft.sleepStart ?: sleepStart,
        sleepEnd = draft.sleepEnd ?: sleepEnd,
        sleepScore = draft.sleepScore ?: sleepScore,
        sleepQuality = draft.sleepQuality ?: sleepQuality,
        deepSleepSeconds = draft.deepSleepSeconds ?: deepSleepSeconds,
        lightSleepSeconds = draft.lightSleepSeconds ?: lightSleepSeconds,
        remSleepSeconds = draft.remSleepSeconds ?: remSleepSeconds,
        awakeSleepSeconds = draft.awakeSleepSeconds ?: awakeSleepSeconds,
        sleepAvgSpo2 = draft.sleepAvgSpo2 ?: sleepAvgSpo2,
        sleepLowestSpo2 = draft.sleepLowestSpo2 ?: sleepLowestSpo2,
        sleepAvgRespiration = draft.sleepAvgRespiration ?: sleepAvgRespiration,
        sleepLowestHeartRate = draft.sleepLowestHeartRate ?: sleepLowestHeartRate,
        hrvLastNight = draft.hrvLastNight ?: hrvLastNight,
        hrvWeeklyAvg = draft.hrvWeeklyAvg ?: hrvWeeklyAvg,
        hrvStatus = draft.hrvStatus ?: hrvStatus,
        hrvBaselineLow = draft.hrvBaselineLow ?: hrvBaselineLow,
        hrvBaselineHigh = draft.hrvBaselineHigh ?: hrvBaselineHigh,
        moderateMinutes = draft.moderateMinutes ?: moderateMinutes,
        vigorousMinutes = draft.vigorousMinutes ?: vigorousMinutes,
        updatedAt = nowSeconds(),
    )

    private suspend fun mergeWellnessSamples(dayId: Long, samples: List<WellnessSampleDraft>) {
        if (samples.isEmpty()) return
        val existingKeys = dao.wellnessSamples(dayId).map { it.kind to it.timestamp }.toHashSet()
        val fresh = samples
            .filter { (it.kind to it.timestamp) !in existingKeys }
            .map { WellnessSampleEntity(dayId = dayId, timestamp = it.timestamp, kind = it.kind, value = it.value) }
        if (fresh.isNotEmpty()) dao.insertWellnessSamples(fresh)
    }

    private suspend fun replaceSleepStages(draft: WellnessDayDraft) {
        if (draft.sleepStages.isEmpty()) return
        dao.deleteSleepStages(draft.date)
        dao.insertSleepStages(
            draft.sleepStages.map {
                SleepStageEntity(
                    date = draft.date,
                    startTime = it.startTime,
                    endTime = it.endTime,
                    stage = it.stage,
                )
            },
        )
    }

    /**
     * Recomputes sample-derived aggregates across every file imported for this
     * day, so the result does not depend on import order.
     */
    private suspend fun recomputeWellnessAggregates(
        dayId: Long,
        entity: WellnessDayEntity,
    ): WellnessDayEntity {
        val samples = dao.wellnessSamples(dayId)
        val bodyBattery = samples
            .filter { it.kind == "body_battery" }
            .sortedBy { it.timestamp }
            .map { it.value.roundToInt() }
        val stress = samples.filter { it.kind == "stress" }.map { it.value }
        val spo2 = samples.filter { it.kind == "spo2" }.map { it.value }
        val respiration = samples.filter { it.kind == "respiration" }.map { it.value }
        val hr = samples.filter { it.kind == "hr" }.map { it.value }
        val hrvSamples = samples.filter { it.kind == "hrv" && it.value > 0.0 }.map { it.value }

        val merged = entity.copy(
            id = dayId,
            avgHeartRate = hr.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
                ?: entity.avgHeartRate,
            maxHeartRate = hr.maxOrNull()?.roundToInt() ?: entity.maxHeartRate,
            stressAvg = stress.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
                ?: entity.stressAvg,
            spo2Avg = spo2.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
                ?: entity.spo2Avg,
            respirationAvg = respiration.takeIf { it.isNotEmpty() }?.average()
                ?: entity.respirationAvg,
            bodyBatteryStart = bodyBattery.firstOrNull() ?: entity.bodyBatteryStart,
            bodyBatteryMin = bodyBattery.minOrNull() ?: entity.bodyBatteryMin,
            bodyBatteryMax = bodyBattery.maxOrNull() ?: entity.bodyBatteryMax,
            bodyBatteryLatest = bodyBattery.lastOrNull() ?: entity.bodyBatteryLatest,
            hrvLastNight = entity.hrvLastNight
                ?: hrvSamples.takeIf { it.isNotEmpty() }?.average(),
        )
        if (merged != entity) dao.updateWellnessDay(merged)
        return merged
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    /**
     * Re-imports wellness files left in the app's directory by earlier
     * versions. Files are tiny (monitoring/sleep), so this is limited to
     * small decoded files and skips anything that is not a known wellness
     * FIT type. This is what backfills Body Battery for the current day after
     * upgrading without asking the user to re-sync.
     */
    suspend fun backfillWellnessFiles(filesDir: File) {
        val files = filesDir.listFiles()?.filter { file ->
            file.isFile && file.name.endsWith(".dec") && file.length() in 64..200_000
        } ?: return
        val known = dao.importedWellnessFileIds().toHashSet()
        for (file in files) {
            // v2 re-parses once after adding Firstbeat rMSSD/sleep fields, so
            // users upgrading at least once get historical HRV from files that
            // were already marked as imported by the previous parser.
            val key = "file-v2:${file.name}"
            if (key in known) continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            val parsed = runCatching { Fit.parse(bytes) }.getOrNull() ?: continue
            if (parsed.fileId?.type !in setOf(29, 32, 44, 49)) {
                markWellnessFileImported(key, "not-wellness")
                continue
            }
            val days = runCatching { importWellness(bytes) }.getOrDefault(emptyList())
            if (days.isNotEmpty()) markWellnessFileImported(key, "backfill-v2")
        }
    }

    suspend fun markWellnessFileImported(itemUid: String, type: String) {
        if (itemUid.isEmpty()) return
        dao.insertImportedWellnessFile(
            ImportedWellnessFileEntity(
                itemUid = itemUid,
                type = type,
                importedAt = System.currentTimeMillis() / 1000,
            ),
        )
    }

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
                bodyBattery = record.bodyBattery,
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
