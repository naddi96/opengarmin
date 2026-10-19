package dev.opengarmin.app.coach

import dev.opengarmin.app.data.ActivityRepository
import dev.opengarmin.app.data.PlannedWorkoutEntity

/**
 * The only write path exposed to the coach: creating and listing locally
 * planned workouts. It never sends anything to the watch; the user reviews and
 * sends from Training → Workout plan.
 */
interface WorkoutWriter {
    suspend fun createWorkout(scope: ToolScope, workout: PlannedWorkoutEntity): PlannedWorkoutEntity
    suspend fun listWorkouts(scope: ToolScope): List<PlannedWorkoutEntity>
}

class RepositoryWorkoutWriter(
    private val repository: ActivityRepository,
) : WorkoutWriter {
    override suspend fun createWorkout(
        scope: ToolScope,
        workout: PlannedWorkoutEntity,
    ): PlannedWorkoutEntity {
        requireScope(scope)
        return repository.saveWorkout(workout.copy(id = 0L, lastSentAt = null))
    }

    override suspend fun listWorkouts(scope: ToolScope): List<PlannedWorkoutEntity> {
        requireScope(scope)
        return repository.plannedWorkoutsOnce()
    }

    private fun requireScope(scope: ToolScope) {
        if (scope.profileId != LOCAL_PROFILE_ID) {
            throw IllegalArgumentException("Unknown profile scope")
        }
    }
}
