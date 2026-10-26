package dev.watchstrava.service

import dev.watchstrava.engine.Snapshot
import dev.watchstrava.engine.Summary
import dev.watchstrava.model.Plan
import dev.watchstrava.model.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow

/** In-process bridge between [WorkoutService] and the UI. */
object WorkoutSession {
    /** Set by the UI right before starting the service. */
    @Volatile var pendingPlan: Plan? = null
    @Volatile var pendingUseGps = true

    /** Non-null while a workout is running. */
    val live = MutableStateFlow<Live?>(null)
    val banner = MutableStateFlow<Banner?>(null)
    /** The workout just finished, shown once by the UI. */
    val finished = MutableStateFlow<Summary?>(null)

    data class Live(
        val plan: Plan,
        val snapshot: Snapshot,
        val useGps: Boolean,
        /** Chosen "Solo sensore cardio": no GPS pages at all. */
        val sensorOnly: Boolean,
        val maxHr: Int,
        val bleConnected: Int,
        val bleError: String?,
        /** GPS breadcrumb for the map page. */
        val track: List<TrackPoint> = emptyList(),
    )

    enum class Tone { LAP, STEP_WORK, STEP_EASY, ALERT, DONE, INFO }

    data class Banner(val title: String, val detail: String, val tone: Tone, val untilMs: Long)
}
