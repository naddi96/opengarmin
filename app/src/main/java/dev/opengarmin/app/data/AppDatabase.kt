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
import dev.opengarmin.protocol.Fit
import dev.opengarmin.protocol.Gfdi
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

@Entity(
    tableName = "activities",
    indices = [Index(value = ["sourceKey"], unique = true)],
)
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceKey: String,
    val fileName: String,
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
)

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities ORDER BY startTime DESC")
    fun observeAll(): Flow<List<ActivityEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActivity(activity: ActivityEntity): Long

    @Insert
    suspend fun insertRecords(records: List<RecordEntity>)

    @Query("SELECT * FROM records WHERE activityId = :activityId ORDER BY timestamp")
    suspend fun records(activityId: Long): List<RecordEntity>

    @Query("DELETE FROM activities")
    suspend fun clear()
}

@Database(entities = [ActivityEntity::class, RecordEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "opengarmin.db",
                ).build().also { instance = it }
            }
    }
}

class ActivityRepository(private val dao: ActivityDao) {
    val activities: Flow<List<ActivityEntity>> = dao.observeAll()

    suspend fun records(activityId: Long): List<RecordEntity> = dao.records(activityId)

    suspend fun clear() = dao.clear()

    /**
     * Parses a decoded FIT file and stores the activity plus its record
     * samples. Returns null when the file has no session or was imported
     * before (same start time, distance and sport).
     */
    suspend fun importFit(fileName: String, data: ByteArray): ActivityEntity? {
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
        if (id == -1L) return null
        val records = parsed.records.map { record ->
            RecordEntity(
                activityId = id,
                timestamp = record.timestamp + Gfdi.GARMIN_EPOCH_UNIX,
                distanceMeters = record.distanceMeters,
                heartRate = record.heartRate,
                speedMps = record.speedMps,
                altitudeMeters = record.altitudeMeters,
                cadence = record.cadence,
            )
        }
        if (records.isNotEmpty()) dao.insertRecords(records)
        return entity.copy(id = id)
    }
}
