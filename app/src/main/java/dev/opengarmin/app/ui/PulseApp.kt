package dev.opengarmin.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.coach.CoachController
import dev.opengarmin.app.coach.GpsSummary
import dev.opengarmin.app.coach.RunningDynamicsSummary
import dev.opengarmin.app.data.ActivityAnalysisEntity
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.ActivityExportFormat
import dev.opengarmin.app.data.ActivityInsights
import dev.opengarmin.app.data.FitMessageEntity
import dev.opengarmin.app.data.LapEntity
import dev.opengarmin.app.data.PaceCourse
import dev.opengarmin.app.data.PacePlanCalculator
import dev.opengarmin.app.data.PacePlanEntity
import dev.opengarmin.app.data.PacePlanSendStatus
import dev.opengarmin.app.data.PlannedWorkoutEntity
import dev.opengarmin.app.data.RecordEntity
import dev.opengarmin.app.data.SleepStageEntity
import dev.opengarmin.app.data.TrainingSummary
import dev.opengarmin.app.data.WellnessDayEntity
import dev.opengarmin.app.data.WellnessSampleEntity
import dev.opengarmin.app.data.WorkoutSendStatus
import dev.opengarmin.app.llm.ProviderConfig
import dev.opengarmin.app.llm.ProviderStore
import dev.opengarmin.app.settings.PulseSettings
import dev.opengarmin.app.sync.ActivitySyncProgress
import dev.opengarmin.app.sync.SyncPhase
import dev.opengarmin.app.ui.components.EmptyState
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.ui.components.PulseTab
import dev.opengarmin.app.ui.components.PulseTabBar
import dev.opengarmin.app.ui.screens.ActivitiesScreen
import dev.opengarmin.app.ui.screens.ActivityDetailScreen
import dev.opengarmin.app.ui.screens.CoachScreen
import dev.opengarmin.app.ui.screens.DataExplorer
import dev.opengarmin.app.ui.screens.HealthScreen
import dev.opengarmin.app.ui.screens.PaceRunScreen
import dev.opengarmin.app.ui.screens.PaceStrategyScreen
import dev.opengarmin.app.ui.screens.PlanScreen
import dev.opengarmin.app.ui.screens.ProviderSettingsScreen
import dev.opengarmin.app.ui.screens.SettingsScreen
import dev.opengarmin.app.ui.screens.TodayScreen
import dev.opengarmin.app.ui.screens.TrainingScreen
import dev.opengarmin.app.ui.screens.TrendsScreen
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack

private enum class AppRoute { Tabs, Trends, Detail, Plan, PacePro, PaceRun, Settings, Provider, Data, Log }

@Composable
fun PulseApp(
    activities: List<ActivityEntity>,
    wellnessDays: List<WellnessDayEntity>,
    hrSamples: List<WellnessSampleEntity>,
    sleepStages: List<SleepStageEntity>,
    trainingSummary: TrainingSummary,
    plannedWorkouts: List<PlannedWorkoutEntity>,
    workoutSendStatus: WorkoutSendStatus?,
    pacePlans: List<PacePlanEntity>,
    pacePlanSendStatus: PacePlanSendStatus?,
    selectedActivity: ActivityEntity?,
    selectedRecords: List<RecordEntity>,
    selectedLaps: List<LapEntity>,
    selectedInsights: ActivityInsights?,
    selectedDynamics: RunningDynamicsSummary?,
    selectedGps: GpsSummary?,
    selectedAnalysis: ActivityAnalysisEntity?,
    openActivityId: Long?,
    onOpenActivityHandled: () -> Unit,
    selectedFitExportAvailable: Boolean,
    exportStatus: String?,
    exportBusy: Boolean,
    dataActivity: ActivityEntity?,
    dataMessages: List<FitMessageEntity>,
    dataLoading: Boolean,
    settings: PulseSettings,
    providerStore: ProviderStore,
    coachController: CoachController,
    syncLabel: String,
    syncColor: Color,
    activitySyncProgress: ActivitySyncProgress?,
    lastImportedLabel: String?,
    logs: List<String>,
    deviceInfo: () -> List<Pair<String, String>>,
    onSync: () -> Unit,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectActivity: (ActivityEntity) -> Unit,
    onClearSelection: () -> Unit,
    onExploreData: (ActivityEntity) -> Unit,
    onCloseData: () -> Unit,
    onExportActivity: (ActivityEntity, ActivityExportFormat, android.net.Uri) -> Unit,
    onGenerateAnalysis: (ActivityEntity) -> Unit,
    onImportFit: (android.net.Uri) -> Unit,
    onSaveWorkout: (PlannedWorkoutEntity) -> Unit,
    onDeleteWorkout: (Long) -> Unit,
    onSendWorkout: (PlannedWorkoutEntity) -> Unit,
    onSendPacePlan: (PacePlanEntity) -> Unit,
    onDeletePacePlan: (Long) -> Unit,
    loadPaceCourse: suspend (Long) -> PaceCourse?,
    backupStatus: String?,
    backupBusy: Boolean,
    onExportBackup: (android.net.Uri) -> Unit,
    onImportBackup: (android.net.Uri) -> Unit,
    onBackgroundSyncChanged: () -> Unit,
    onRunBackgroundSyncNow: () -> Unit,
) {
    var tabName by rememberSaveable { mutableStateOf(PulseTab.Today.name) }
    var routeName by rememberSaveable { mutableStateOf(AppRoute.Tabs.name) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var dataId by rememberSaveable { mutableStateOf<Long?>(null) }
    var paceRunPlan by remember { mutableStateOf<PacePlanEntity?>(null) }
    var providerReturnName by rememberSaveable { mutableStateOf(AppRoute.Settings.name) }
    var providerVersion by remember { mutableIntStateOf(0) }
    val tab = PulseTab.entries.firstOrNull { it.name == tabName } ?: PulseTab.Today
    val route = runCatching { AppRoute.valueOf(routeName) }.getOrDefault(AppRoute.Tabs)
    val detailActivity = activities.firstOrNull { it.id == detailId }
    val dataTarget = activities.firstOrNull { it.id == dataId } ?: dataActivity ?: detailActivity
    val provider: ProviderConfig = remember(providerVersion, route) { providerStore.load() }

    LaunchedEffect(openActivityId, activities) {
        val id = openActivityId ?: return@LaunchedEffect
        val activity = activities.firstOrNull { it.id == id }
        when {
            activity != null -> {
                onSelectActivity(activity)
                detailId = activity.id
                routeName = AppRoute.Detail.name
                onOpenActivityHandled()
            }
            // Wait for the database flow on a cold start. Once the list is
            // present and the activity is gone, drop the stale notification.
            activities.isNotEmpty() -> onOpenActivityHandled()
        }
    }

    fun openSettings() {
        routeName = AppRoute.Settings.name
    }

    fun openProvider(returnTo: AppRoute) {
        providerReturnName = returnTo.name
        routeName = AppRoute.Provider.name
    }

    val planSummary = if (plannedWorkouts.isEmpty()) {
        "Build a session and send it to the watch"
    } else {
        val scheduled = plannedWorkouts.count { it.scheduledDate != null }
        "${plannedWorkouts.size} saved · $scheduled scheduled"
    }
    val pacePlan = pacePlans.firstOrNull()
    val paceSummary = pacePlan?.let { "${it.name} · ${it.summary()}" }
        ?: "Build a split-by-split racing plan"
    val racePredictions = PacePlanCalculator.predictions(activities)

    BackHandler(enabled = route != AppRoute.Tabs) {
        when (route) {
            AppRoute.Data -> {
                onCloseData()
                dataId = null
                routeName = if (detailActivity != null) AppRoute.Detail.name else AppRoute.Tabs.name
            }
            AppRoute.Trends -> routeName = AppRoute.Tabs.name
            AppRoute.Plan -> routeName = AppRoute.Tabs.name
            AppRoute.PacePro -> routeName = AppRoute.Tabs.name
            AppRoute.PaceRun -> {
                paceRunPlan = null
                tabName = PulseTab.Training.name
                routeName = AppRoute.Tabs.name
            }
            AppRoute.Detail -> {
                onClearSelection()
                detailId = null
                routeName = AppRoute.Tabs.name
            }
            AppRoute.Provider -> {
                providerVersion++
                coachController.refreshProvider()
                routeName = providerReturnName
            }
            AppRoute.Settings, AppRoute.Log -> routeName = if (route == AppRoute.Log) AppRoute.Settings.name else AppRoute.Tabs.name
            AppRoute.Tabs -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(PulseColors.Background)) {
        when (route) {
            AppRoute.Tabs -> {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            PulseTab.Today -> TodayScreen(
                                days = wellnessDays,
                                hrSamples = hrSamples,
                                settings = settings,
                                syncLabel = syncLabel,
                                syncColor = syncColor,
                                activitySyncProgress = activitySyncProgress,
                                lastImportedLabel = lastImportedLabel,
                                syncError = settings.lastSyncError
                                    .takeIf {
                                        SyncPhase.fromStored(settings.lastSyncStatus) == SyncPhase.Failed &&
                                            it.isNotBlank()
                                    },
                                onSync = onSync,
                                onOpenSettings = { openSettings() },
                                onAskCoach = { tabName = PulseTab.Coach.name },
                            )
                            PulseTab.Health -> HealthScreen(
                                days = wellnessDays,
                                samples = hrSamples,
                                sleepStages = sleepStages,
                                training = trainingSummary,
                                onOpenTrends = { routeName = AppRoute.Trends.name },
                                onOpenTraining = { tabName = PulseTab.Training.name },
                                onAskCoach = { tabName = PulseTab.Coach.name },
                                onSync = onSync,
                            )
                            PulseTab.Training -> TrainingScreen(
                                summary = trainingSummary,
                                onOpenActivity = { activity ->
                                    onSelectActivity(activity)
                                    detailId = activity.id
                                    routeName = AppRoute.Detail.name
                                },
                                onAskCoach = { tabName = PulseTab.Coach.name },
                                onOpenPlan = { routeName = AppRoute.Plan.name },
                                plannedWorkoutsSummary = planSummary,
                                onOpenPace = { routeName = AppRoute.PacePro.name },
                                pacePlanSummary = paceSummary,
                                racePredictions = racePredictions,
                            )
                            PulseTab.Activities -> ActivitiesScreen(
                                activities = activities,
                                selectedActivity = selectedActivity,
                                selectedRecords = selectedRecords,
                                maxHeartRate = settings.maxHeartRate,
                                onSelect = onSelectActivity,
                                onOpenDetail = { activity ->
                                    onSelectActivity(activity)
                                    detailId = activity.id
                                    routeName = AppRoute.Detail.name
                                },
                                onOpenSettings = { openSettings() },
                            )
                            PulseTab.Coach -> CoachScreen(
                                controller = coachController,
                                dataSummary = "Reading steps, heart rate and workouts",
                                onManage = { openProvider(AppRoute.Tabs) },
                            )
                        }
                    }
                    PulseTabBar(
                        selected = tab,
                        onSelect = { tabName = it.name },
                    )
                }
            }
            AppRoute.Trends -> TrendsScreen(
                days = wellnessDays,
                hrSamples = hrSamples,
                settings = settings,
                onAskCoach = { tabName = PulseTab.Coach.name },
                onBack = { routeName = AppRoute.Tabs.name },
            )
            AppRoute.Plan -> PlanScreen(
                workouts = plannedWorkouts,
                sendStatus = workoutSendStatus,
                onBack = { routeName = AppRoute.Tabs.name },
                onSave = onSaveWorkout,
                onDelete = onDeleteWorkout,
                onSend = onSendWorkout,
            )
            AppRoute.PacePro -> PaceStrategyScreen(
                plans = pacePlans,
                activities = activities,
                sendStatus = pacePlanSendStatus,
                onBack = { routeName = AppRoute.Tabs.name },
                onSend = onSendPacePlan,
                onDelete = onDeletePacePlan,
                loadCourse = loadPaceCourse,
                onOpenRun = { plan ->
                    paceRunPlan = plan
                    routeName = AppRoute.PaceRun.name
                },
            )
            AppRoute.PaceRun -> {
                val plan = paceRunPlan
                if (plan != null) {
                    PaceRunScreen(
                        plan = plan,
                        onStop = {
                            paceRunPlan = null
                            tabName = PulseTab.Training.name
                            routeName = AppRoute.Tabs.name
                        },
                    )
                } else {
                    EmptyState(
                        title = "No pace plan selected",
                        message = "Build a PacePro strategy before starting a run.",
                        actionLabel = "Back",
                        onAction = { routeName = AppRoute.Tabs.name },
                        modifier = Modifier.padding(PulseSpacing.xxl),
                    )
                }
            }
            AppRoute.Detail -> {
                if (detailActivity != null) {
                    // After a configuration change (rotation) the activity's
                    // record/insight state is rebuilt asynchronously. Reload it
                    // if the remembered detail route is restored before the
                    // selection state is.
                    LaunchedEffect(detailActivity.id) {
                        if (selectedActivity?.id != detailActivity.id) {
                            onSelectActivity(detailActivity)
                        }
                    }
                    ActivityDetailScreen(
                        activity = detailActivity,
                        records = selectedRecords,
                        laps = selectedLaps,
                        insights = selectedInsights,
                        dynamics = selectedDynamics,
                        gps = selectedGps,
                        analysis = selectedAnalysis,
                        providerConfigured = provider.isConfigured,
                        maxHeartRate = settings.maxHeartRate,
                        fitExportAvailable = selectedFitExportAvailable,
                        exportStatus = exportStatus,
                        exportBusy = exportBusy,
                        onGenerateAnalysis = { onGenerateAnalysis(detailActivity) },
                        onExport = { format, uri -> onExportActivity(detailActivity, format, uri) },
                        onBack = {
                            onClearSelection()
                            detailId = null
                            routeName = AppRoute.Tabs.name
                        },
                        onExploreData = {
                            dataId = detailActivity.id
                            onExploreData(detailActivity)
                            routeName = AppRoute.Data.name
                        },
                        onOpenSettings = { openSettings() },
                    )
                } else {
                    EmptyState(
                        title = "Activity unavailable",
                        message = "This activity is no longer in the local database.",
                        actionLabel = "Back",
                        onAction = { routeName = AppRoute.Tabs.name },
                        modifier = Modifier.padding(PulseSpacing.xxl),
                    )
                }
            }
            AppRoute.Settings -> SettingsScreen(
                settings = settings,
                provider = provider,
                syncLabel = syncLabel,
                activitySyncProgress = activitySyncProgress,
                lastImportedLabel = lastImportedLabel,
                onBack = { routeName = AppRoute.Tabs.name },
                onManageProvider = { openProvider(AppRoute.Settings) },
                onSync = onSync,
                onScan = onScan,
                onDisconnect = onDisconnect,
                onShowLog = { routeName = AppRoute.Log.name },
                onImportFit = onImportFit,
                backupStatus = backupStatus,
                backupBusy = backupBusy,
                onExportBackup = onExportBackup,
                onImportBackup = onImportBackup,
                onBackgroundSyncChanged = onBackgroundSyncChanged,
                onRunBackgroundSyncNow = onRunBackgroundSyncNow,
            )
            AppRoute.Provider -> ProviderSettingsScreen(
                store = providerStore,
                initialConfig = provider,
                onBack = {
                    providerVersion++
                    coachController.refreshProvider()
                    routeName = providerReturnName
                },
                onChanged = {
                    providerVersion++
                    coachController.refreshProvider()
                },
            )
            AppRoute.Data -> {
                val activity = dataTarget
                if (activity != null) {
                    LaunchedEffect(activity.id) {
                        if (!dataLoading && dataMessages.isEmpty()) {
                            onExploreData(activity)
                        }
                    }
                    CompositionLocalProvider(LocalContentColor provides PulseColors.Text) {
                        DataExplorer(
                            activity = activity,
                            messages = dataMessages,
                            loading = dataLoading,
                            onBack = {
                                onCloseData()
                                dataId = null
                                routeName = if (detailActivity != null) AppRoute.Detail.name else AppRoute.Tabs.name
                            },
                            modifier = Modifier.fillMaxSize().padding(PulseSpacing.md),
                        )
                    }
                } else {
                    EmptyState(
                        title = "No activity selected",
                        message = "Open an activity before exploring its FIT fields.",
                        actionLabel = "Back",
                        onAction = { routeName = AppRoute.Tabs.name },
                        modifier = Modifier.padding(PulseSpacing.xxl),
                    )
                }
            }
            AppRoute.Log -> LogScreen(
                logs = logs,
                deviceInfo = deviceInfo,
                onBack = { routeName = AppRoute.Settings.name },
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun LogScreen(
    logs: List<String>,
    deviceInfo: () -> List<Pair<String, String>>,
    onBack: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButtonRound(Icons.Outlined.ArrowBack, "Back to settings", onBack)
            Text(
                "Device log",
                style = MaterialTheme.typography.headlineLarge,
                color = PulseColors.Text,
                modifier = Modifier.padding(start = PulseSpacing.md),
            )
        }
        Text(
            deviceInfo().joinToString("  ·  ") { "${it.first}: ${it.second}" },
            style = MaterialTheme.typography.labelSmall,
            color = PulseColors.TextMuted,
        )
        Text(
            "Sensitive values and provider keys are not logged. Protocol output only.",
            style = MaterialTheme.typography.labelSmall,
            color = PulseColors.TextMuted,
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(logs) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = PulseColors.Text,
                )
            }
        }
        dev.opengarmin.app.ui.components.PrimaryButton("Stop scan", onStop)
    }
}
