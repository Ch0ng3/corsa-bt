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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.watchstrava.R
import dev.watchstrava.data.History
import dev.watchstrava.data.Settings
import dev.watchstrava.engine.TargetStatus
import dev.watchstrava.engine.WorkoutEngine
import dev.watchstrava.engine.WorkoutEngine.Event
import dev.watchstrava.model.Format
import dev.watchstrava.model.StepKind
import dev.watchstrava.model.Track
import dev.watchstrava.service.WorkoutSession.Banner
import dev.watchstrava.service.WorkoutSession.Tone
import dev.watchstrava.ui.MainActivity

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
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private var engine: WorkoutEngine? = null
    private var ble: HrBroadcaster? = null
    private var useGps = true
    private var sensorOnly = false
    private var lastGood: Location? = null
    private var track = Track()
    private var wakeLock: PowerManager.WakeLock? = null

    private val tick = object : Runnable {
        override fun run() {
            val e = engine ?: return
            val now = SystemClock.elapsedRealtime()
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
        sensorOnly = !WorkoutSession.pendingUseGps
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
        val e = WorkoutEngine(plan, settings.maxHr, settings.autoPause && useGps)
        engine = e
        track = Track()
        lastGood = null

        val sm = getSystemService(SensorManager::class.java)
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
                val title = if (event.auto) "KM ${lap.number}" else "GIRO ${lap.number}"
                show(title, "${Format.pace(lap.pace)} /km · ${Format.duration(lap.movingMs)}$hr", Tone.LAP)
                vibrate(longArrayOf(0, 150, 100, 150))
            }
            is Event.StepStarted -> {
                val s = event.step
                val reps = if (s.reps > 0) " ${s.rep}/${s.reps}" else ""
                val target = Format.target(s.target, maxHr).let { if (it.isEmpty()) "" else "\n$it" }
                val hard = s.kind == StepKind.WORK
                show("${s.kind.label.uppercase()}$reps", Format.length(s.length) + target, if (hard) Tone.STEP_WORK else Tone.STEP_EASY)
                vibrate(if (hard) longArrayOf(0, 700) else longArrayOf(0, 300, 120, 300))
            }
            is Event.Countdown -> vibrate(longArrayOf(0, 60))
            is Event.OffTarget -> {
                val (title, detail) = when (event.status) {
                    TargetStatus.TOO_FAST -> "RALLENTA" to "Sei più veloce del passo obiettivo"
                    TargetStatus.TOO_SLOW -> "ACCELERA" to "Sei più lento del passo obiettivo"
                    TargetStatus.HR_HIGH -> "BATTITI ALTI" to "Sopra la zona obiettivo"
                    TargetStatus.HR_LOW -> "BATTITI BASSI" to "Sotto la zona obiettivo"
                    else -> continue
                }
                show(title, detail, Tone.ALERT)
                vibrate(longArrayOf(0, 100, 80, 100, 80, 100))
            }
            Event.PlanDone -> {
                show("COMPLETATO!", "Allenamento finito.\nCorsa libera finché premi Fine", Tone.DONE)
                vibrate(longArrayOf(0, 400, 150, 400, 150, 400))
            }
            is Event.AutoPaused -> {
                show(if (event.paused) "PAUSA AUTO" else "RIPRESO", "", Tone.INFO)
                vibrate(longArrayOf(0, 200))
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
        track.add(location.latitude, location.longitude)
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
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // --- Notification / watch-face ongoing activity -------------------------

    private fun startForegroundWithOngoing(title: String, types: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Allenamento", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(title)
            .setContentText("Allenamento in corso")
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
