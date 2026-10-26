package dev.watchstrava.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.watchstrava.data.History
import dev.watchstrava.data.Settings
import dev.watchstrava.model.Plan
import dev.watchstrava.service.WorkoutService
import dev.watchstrava.service.WorkoutSession

class MainActivity : ComponentActivity() {

    private lateinit var settings: Settings
    private var afterPermissions: (() -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // The service degrades gracefully (no GPS / no HR / no BLE) as long as something works.
        val go = afterPermissions
        afterPermissions = null
        go?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        accentIndex.intValue = settings.accent
        setContent {
            AppTheme {
                App(settings, History(this), ::startWorkout, ::command)
            }
        }
    }

    private fun startWorkout(plan: Plan, useGps: Boolean) {
        if (afterPermissions != null) return // permission dialog already pending (double tap)
        val needed = buildList {
            if (useGps) add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.BODY_SENSORS)
            if (useGps) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (settings.broadcastHr && Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        val go = go@{
            // A foreground service needs at least one usable type (location / sensors / BLE).
            val anyType = buildList {
                if (useGps) add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.BODY_SENSORS)
                if (settings.broadcastHr && Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            }.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            if (!anyType) {
                Toast.makeText(this, "Servono i permessi di posizione o sensori", Toast.LENGTH_LONG).show()
                return@go
            }
            WorkoutSession.pendingPlan = plan
            WorkoutSession.pendingUseGps = useGps
            startForegroundService(Intent(this, WorkoutService::class.java).setAction(WorkoutService.ACTION_START))
        }
        if (needed.isEmpty()) {
            go()
        } else {
            afterPermissions = { go() }
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun command(c: RunCommand) {
        val action = when (c) {
            RunCommand.PAUSE -> WorkoutService.ACTION_PAUSE
            RunCommand.RESUME -> WorkoutService.ACTION_RESUME
            RunCommand.LAP -> WorkoutService.ACTION_LAP
            RunCommand.STOP -> WorkoutService.ACTION_STOP
        }
        startService(Intent(this, WorkoutService::class.java).setAction(action))
    }

    /** The lower physical button acts as GIRO during a workout, like on a Garmin. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STEM_1 && WorkoutSession.live.value != null) {
            if (event.repeatCount == 0) command(RunCommand.LAP)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun App(
    settings: Settings,
    history: History,
    startWorkout: (Plan, Boolean) -> Unit,
    command: (RunCommand) -> Unit,
) {
    val live by WorkoutSession.live.collectAsState()
    val banner by WorkoutSession.banner.collectAsState()
    val finished by WorkoutSession.finished.collectAsState()

    val view = LocalView.current
    val screenOn = live != null && settings.keepScreenOn
    DisposableEffect(screenOn) {
        view.keepScreenOn = screenOn
        onDispose { view.keepScreenOn = false }
    }

    val current = live
    val summary = finished
    // Re-read when a workout starts, so changes made in Personalizza apply to the next run.
    val runPrefs = remember(current == null) { RunPrefs(settings) }
    when {
        current != null -> RunScreen(current, banner, runPrefs, command)
        summary != null -> SummaryScreen(summary) { WorkoutSession.finished.value = null }
        else -> NavGraph(settings, history, startWorkout)
    }
}

@Composable
private fun NavGraph(settings: Settings, history: History, startWorkout: (Plan, Boolean) -> Unit) {
    val nav = rememberSwipeDismissableNavController()
    val run = remember { { plan: Plan -> startWorkout(plan, true) } }
    SwipeDismissableNavHost(navController = nav, startDestination = "home") {
        composable("home") { HomeScreen(settings) { nav.navigate(it) } }
        composable("customize") { CustomizeScreen(settings) }
        composable("free") { FreeRunSetup(settings, run) }
        composable("intervals") { IntervalsSetup(settings, run) }
        composable("fartlek") { FartlekSetup(settings, run) }
        composable("sensor") { SensorSetup { startWorkout(it, false) } }
        composable("settings") { SettingsScreen(settings) }
        composable("history") { HistoryScreen(history) { nav.navigate("history/$it") } }
        composable("history/{id}") { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull()
            val summary = id?.let { history.get(it) }
            if (summary != null) {
                HistoryDetail(summary) {
                    history.delete(summary.startedAt)
                    nav.popBackStack()
                }
            }
        }
    }
}
