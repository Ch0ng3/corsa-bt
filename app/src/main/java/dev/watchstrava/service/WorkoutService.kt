package dev.watchstrava.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.watchstrava.R
import dev.watchstrava.data.History
import dev.watchstrava.data.Settings
import dev.watchstrava.engine.LapLabels
import dev.watchstrava.engine.TargetStatus
import dev.watchstrava.engine.WorkoutEngine
import dev.watchstrava.engine.WorkoutEngine.Event
import dev.watchstrava.model.Format
import dev.watchstrava.model.Route
import dev.watchstrava.model.Sport
import dev.watchstrava.model.StepKind
import dev.watchstrava.model.Track
import dev.watchstrava.service.WorkoutSession.Banner
import dev.watchstrava.service.WorkoutSession.Tone
import dev.watchstrava.ui.MainActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.exp

/** Runs a workout: GPS, heart rate, step counter, BLE broadcast, vibration cues. */
@SuppressLint("MissingPermission") // MainActivity grants permissions before starting
class WorkoutService : Service(), SensorEventListener, LocationListener {

    companion object {
        const val ACTION_START = "dev.watchstrava.START"
        const val ACTION_PAUSE = "dev.watchstrava.PAUSE"
        const val ACTION_RESUME = "dev.watchstrava.RESUME"
        const val ACTION_LAP = "dev.watchstrava.LAP"
        const val ACTION_STOP = "dev.watchstrava.STOP"

        private const val CHANNEL = "workout"
        private const val NOTIF_ID = 1
        private const val MAX_ACCURACY_M = 25f
        private const val BANNER_MS = 6_000L
        private const val OFF_ROUTE_M = 40.0
        private const val OFF_ROUTE_FIXES = 3
        private const val BACK_ON_ROUTE_M = 25.0
        private const val FINISH_ZONE_M = 30.0
        private const val BARO_TAU_S = 5.0 // altitude smoothing time constant
        private const val BARO_PERIOD_US = 500_000
        private const val CALIB_FIXES = 5
        private const val CALIB_MAX_VERTICAL_ACCURACY_M = 15f
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private var engine: WorkoutEngine? = null
    private var ble: HrBroadcaster? = null
    private var useGps = true
    private var sensorOnly = false
    private var lastGood: Location? = null
    private var track = Track()
    private var route: Route? = null
    private var routeProgress = 0.0
    private var offFixes = 0
    private var armed = false
    private var offRoute = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var baroAlt: Double? = null
    private var baroNanos = 0L
    private var baroSeenMs = 0L
    private val calibOffsets = mutableListOf<Double>()
    private var calibPending = false
    private var altExecutor: ExecutorService? = null

    private val tick = object : Runnable {
        override fun run() {
            val e = engine ?: return
            val now = SystemClock.elapsedRealtime()
            baroAlt?.let { if (now - baroSeenMs <= 5_000) e.onAltitude(now, it) }
            handle(e.tick(now))
            ble?.send(e.snapshot(now).heartRate)
            publish()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val e = engine
        when (intent?.action) {
            ACTION_START -> if (e == null) start()
            ACTION_PAUSE -> e?.pause()
            ACTION_RESUME -> e?.resume()
            ACTION_LAP -> e?.let { handle(it.lap()) }
            ACTION_STOP -> {
                finish()
                return START_NOT_STICKY
            }
        }
        if (engine == null) stopSelf() else publish()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (engine != null) finish()
        super.onDestroy()
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun start() {
        val plan = WorkoutSession.pendingPlan ?: return
        WorkoutSession.pendingPlan = null
        val pendingRoute = WorkoutSession.pendingRoute
        WorkoutSession.pendingRoute = null
        // Indoor climbing has no GPS either, but keeps its own pages.
        sensorOnly = !WorkoutSession.pendingUseGps && plan.sport == Sport.RUN
        settings = Settings(this)
        useGps = WorkoutSession.pendingUseGps && granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val broadcast = settings.broadcastHr && (Build.VERSION.SDK_INT < 31 ||
            granted(Manifest.permission.BLUETOOTH_ADVERTISE) && granted(Manifest.permission.BLUETOOTH_CONNECT))
        val sensors = granted(Manifest.permission.BODY_SENSORS)

        var types = 0
        if (useGps) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (broadcast) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (sensors && Build.VERSION.SDK_INT >= 34) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        try {
            startForegroundWithOngoing(plan.name, types)
        } catch (e: RuntimeException) {
            // Missing permission for a declared type: nothing sensible to record.
            stopSelf()
            return
        }

        val now = SystemClock.elapsedRealtime()
        // A race clock never stops on its own: aid stations count. Ski lifts and climbing rests neither.
        val labels = LapLabels(
            { getString(R.string.lap_label, it) }, { getString(it.label) },
            run = { getString(R.string.run_label, it) }, climb = { getString(R.string.climb_label, it) },
        )
        val autoPause = settings.autoPause && useGps && plan.race == null && (plan.sport == Sport.RUN || plan.sport == Sport.HIKE)
        val e = WorkoutEngine(plan, settings.maxHr, autoPause, labels)
        engine = e
        route = pendingRoute?.takeIf { useGps }
        track = Track().apply { route?.let { anchor(it.lat0, it.lon0) } }
        routeProgress = 0.0
        offFixes = 0
        armed = false
        offRoute = false
        lastGood = null
        baroAlt = null
        calibOffsets.clear()
        calibPending = false
        if (useGps && Build.VERSION.SDK_INT >= 34) altExecutor = Executors.newSingleThreadExecutor()

        val sm = getSystemService(SensorManager::class.java)
        // Barometric altitude in every mode (no permission needed); runs just show ascent in the summary.
        if (!sensorOnly) sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(this, it, BARO_PERIOD_US) }
        if (sensors) sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (granted(Manifest.permission.ACTIVITY_RECOGNITION)) sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (useGps) {
            getSystemService(LocationManager::class.java)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        }
        if (broadcast) ble = HrBroadcaster(this) { publish() }.also { it.start() }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CorsaBT:workout")
            .apply { acquire(8 * 60 * 60 * 1000L) }

        WorkoutSession.finished.value = null
        handle(e.start(now, wallClock = System.currentTimeMillis()))
        publish()
        handler.postDelayed(tick, 1000)
    }

    private fun finish() {
        val e = engine ?: run { stopSelf(); return }
        engine = null
        handler.removeCallbacksAndMessages(null)
        getSystemService(SensorManager::class.java).unregisterListener(this)
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }
        ble?.stop()
        ble = null
        altExecutor?.shutdownNow()
        altExecutor = null
        wakeLock?.takeIf { it.isHeld }?.release()

        val summary = e.finish()
        if (summary.movingMs >= 10_000) History(this).save(summary)
        WorkoutSession.live.value = null
        WorkoutSession.banner.value = null
        WorkoutSession.finished.value = summary
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publish() {
        val e = engine ?: return
        WorkoutSession.live.value = WorkoutSession.Live(
            plan = e.plan,
            snapshot = e.snapshot(SystemClock.elapsedRealtime()),
            useGps = useGps,
            sensorOnly = sensorOnly,
            maxHr = settings.maxHr,
            bleConnected = ble?.connected ?: 0,
            bleError = ble?.error,
            track = track.snapshot(),
            route = route?.points.orEmpty(),
            routeProgressM = routeProgress,
            routeRemainingM = route?.let { (it.lengthM - routeProgress).coerceAtLeast(0.0) },
            offRoute = offRoute,
        )
    }

    // --- Cues ---------------------------------------------------------------

    private fun handle(events: List<Event>) {
        val maxHr = settings.maxHr
        for (event in events) when (event) {
            is Event.LapDone -> {
                // Step laps of a structured plan are announced by the following StepStarted.
                if (event.fromStep) continue
                val lap = event.lap
                if (lap.movingMs < 3_000) continue
                val hr = lap.avgHr?.let { " · ♥ $it" } ?: ""
                val title = getString(if (event.auto) R.string.fmt_km_n else R.string.lap_n, lap.number)
                val ahead = engine?.snapshot(SystemClock.elapsedRealtime())?.race?.takeIf { !it.finished }?.aheadSec
                    ?.let { "\n" + getString(R.string.vs_goal, Format.ahead(it)) } ?: ""
                val up = if (engine?.plan?.sport == Sport.HIKE) "\n↑ ${Format.height(lap.ascentM)} ↓ ${Format.height(lap.descentM)}" else ""
                show(title, "${Format.pace(lap.pace)} /km · ${Format.duration(lap.movingMs)}$hr$ahead$up", Tone.LAP)
                vibrate(longArrayOf(0, 150, 100, 150))
            }
            is Event.StepStarted -> {
                val s = event.step
                val reps = if (s.reps > 0) " ${s.rep}/${s.reps}" else ""
                val target = Format.target(s.target, maxHr).let { if (it.isEmpty()) "" else "\n$it" }
                val hard = s.kind == StepKind.WORK
                show("${getString(s.kind.label).uppercase()}$reps", Format.length(s.length, getString(R.string.step_until_lap)) + target, if (hard) Tone.STEP_WORK else Tone.STEP_EASY)
                vibrate(if (hard) longArrayOf(0, 700) else longArrayOf(0, 300, 120, 300))
            }
            is Event.Countdown -> vibrate(longArrayOf(0, 60))
            is Event.OffTarget -> {
                val (title, detail) = when (event.status) {
                    TargetStatus.TOO_FAST -> R.string.alert_slow_down to R.string.alert_too_fast
                    TargetStatus.TOO_SLOW -> R.string.alert_speed_up to R.string.alert_too_slow
                    TargetStatus.HR_HIGH -> R.string.alert_hr_high to R.string.alert_above_zone
                    TargetStatus.HR_LOW -> R.string.alert_hr_low to R.string.alert_below_zone
                    else -> continue
                }
                show(getString(title), getString(detail), Tone.ALERT)
                vibrate(longArrayOf(0, 100, 80, 100, 80, 100))
            }
            Event.PlanDone -> {
                show(getString(R.string.plan_done), getString(R.string.plan_done_detail), Tone.DONE)
                vibrate(longArrayOf(0, 400, 150, 400, 150, 400))
            }
            is Event.RaceFinished -> {
                val ahead = event.aheadSec?.let { "\n" + getString(R.string.vs_goal, Format.ahead(it)) } ?: ""
                show(getString(R.string.finish_line), Format.duration(event.movingMs) + ahead, Tone.DONE)
                vibrate(longArrayOf(0, 400, 150, 400, 150, 400))
            }
            is Event.AutoPaused -> {
                show(getString(if (event.paused) R.string.auto_paused else R.string.resumed), "", Tone.INFO)
                vibrate(longArrayOf(0, 200))
            }
            is Event.RunDone -> {
                val lap = event.lap
                show(
                    getString(R.string.run_label, lap.number).uppercase(),
                    "↓ ${Format.height(lap.descentM)} · ${getString(R.string.fmt_max_kmh, Format.kmh(lap.maxSpeedMps))}\n${Format.duration(lap.movingMs)}",
                    Tone.LAP,
                )
                vibrate(longArrayOf(0, 150, 100, 150))
            }
            is Event.ClimbToggled -> {
                vibrate(longArrayOf(0, 120))
                val lap = event.lap ?: continue
                if (lap.movingMs < 3_000) continue
                val hr = lap.avgHr?.let { " · ♥ $it" } ?: ""
                val up = if (lap.ascentM >= 1) "\n↑ ${Format.height(lap.ascentM)}" else ""
                show(getString(R.string.climb_label, lap.number).uppercase(), Format.duration(lap.movingMs) + hr + up, Tone.LAP)
            }
        }
    }

    private fun show(title: String, detail: String, tone: Tone) {
        WorkoutSession.banner.value = Banner(title, detail, tone, System.currentTimeMillis() + BANNER_MS)
    }

    private fun vibrate(pattern: LongArray) {
        if (!settings.vibrate) return
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    // --- Sensors ------------------------------------------------------------

    override fun onLocationChanged(location: Location) {
        val e = engine ?: return
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return
        if (Build.VERSION.SDK_INT >= 34) calibrateAltitude(location)
        track.add(location.latitude, location.longitude)
        route?.let { followRoute(it, location) }
        val prev = lastGood
        var delta = 0.0
        if (prev == null) {
            lastGood = location
        } else {
            val d = prev.distanceTo(location)
            // Ignore sub-2 m jitter but keep the anchor so slow movement still adds up.
            if (d >= 2f) {
                delta = d.toDouble()
                lastGood = location
            }
        }
        val dtSec = prev?.let { (location.elapsedRealtimeNanos - it.elapsedRealtimeNanos) / 1e9 } ?: 0.0
        val speed = when {
            location.hasSpeed() -> location.speed.toDouble()
            dtSec > 0 -> delta / dtSec
            else -> 0.0
        }
        e.onLocation(SystemClock.elapsedRealtime(), delta, speed)
    }

    /** Off route after [OFF_ROUTE_FIXES] fixes beyond [OFF_ROUTE_M]; back on below [BACK_ON_ROUTE_M]. */
    private fun followRoute(r: Route, location: Location) {
        val fix = r.locate(location.latitude, location.longitude, routeProgress)
        routeProgress = fix.progressM
        if (offRoute) {
            if (fix.offM < BACK_ON_ROUTE_M) {
                offRoute = false
                offFixes = 0
                show(getString(R.string.back_on_route), "", Tone.DONE)
            }
            return
        }
        if (!armed) {
            armed = fix.offM < BACK_ON_ROUTE_M
            return
        }
        if (fix.progressM >= r.lengthM - FINISH_ZONE_M) return
        offFixes = if (fix.offM > OFF_ROUTE_M) offFixes + 1 else 0
        if (offFixes >= OFF_ROUTE_FIXES) {
            offRoute = true
            show(getString(R.string.off_route), Format.meters(fix.offM), Tone.ALERT)
            vibrate(longArrayOf(0, 300))
        }
    }

    /**
     * The barometer gives good relative heights but its absolute value drifts with the weather:
     * offset it by the average gap to the first [CALIB_FIXES] good GPS altitudes.
     * GPS reports height above the ellipsoid, so this needs Android 14's sea-level converter
     * (disk I/O, hence the worker thread); on older systems the standard-atmosphere value stays.
     */
    @RequiresApi(34)
    private fun calibrateAltitude(location: Location) {
        val baro = baroAlt ?: return
        val executor = altExecutor ?: return
        if (calibPending || calibOffsets.size >= CALIB_FIXES) return
        if (!location.hasAltitude() || !location.hasVerticalAccuracy() ||
            location.verticalAccuracyMeters > CALIB_MAX_VERTICAL_ACCURACY_M
        ) return
        calibPending = true
        val fix = Location(location)
        executor.execute {
            val msl = runCatching {
                if (!fix.hasMslAltitude()) AltitudeConverter().addMslAltitudeToLocation(this, fix)
                fix.mslAltitudeMeters
            }.getOrNull()
            handler.post {
                calibPending = false
                val e = engine ?: return@post
                if (msl == null) return@post
                calibOffsets += msl - baro
                if (calibOffsets.size == CALIB_FIXES) e.setAltitudeOffset(calibOffsets.average())
            }
        }
    }

    @Deprecated("Required for API 30 compatibility")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val e = engine ?: return
        val now = SystemClock.elapsedRealtime()
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                if (event.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT) return
                val bpm = event.values[0].toInt()
                if (bpm in 30..230) e.onHeartRate(now, bpm)
            }
            Sensor.TYPE_STEP_COUNTER -> e.onSteps(now, event.values[0].toLong())
            Sensor.TYPE_PRESSURE -> {
                // Exponential smoothing with a time constant, whatever the sensor rate.
                val hpa = event.values[0]
                if (!hpa.isFinite() || hpa !in 300f..1100f) return
                baroSeenMs = now
                val alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hpa).toDouble()
                val prev = baroAlt
                baroAlt = if (prev == null) alt else {
                    val dt = ((event.timestamp - baroNanos) / 1e9).coerceIn(0.0, 10.0)
                    prev + (alt - prev) * (1 - exp(-dt / BARO_TAU_S))
                }
                baroNanos = event.timestamp
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // --- Notification / watch-face ongoing activity -------------------------

    private fun startForegroundWithOngoing(title: String, types: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_workout), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(title)
            .setContentText(getString(R.string.workout_in_progress))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
        OngoingActivity.Builder(this, NOTIF_ID, builder)
            .setStaticIcon(R.drawable.ic_heart)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(title).build())
            .build()
            .apply(this)
        ServiceCompat.startForeground(this, NOTIF_ID, builder.build(), types)
    }
}
