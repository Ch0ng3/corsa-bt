package dev.watchstrava.model

import androidx.annotation.StringRes
import dev.watchstrava.R

enum class StepKind(@StringRes val label: Int) {
    WARMUP(R.string.step_warmup),
    WORK(R.string.step_work),
    EASY(R.string.step_easy),
    RECOVERY(R.string.step_recovery),
    COOLDOWN(R.string.step_cooldown),
    RUN(R.string.step_run),
}

sealed interface StepLength {
    data class Time(val seconds: Int) : StepLength
    data class Distance(val meters: Int) : StepLength
    /** Ends when the runner presses GIRO. */
    data object Open : StepLength
}

sealed interface Target {
    data object None : Target
    /** Pace window in seconds per km: [fastest] < [slowest]. */
    data class Pace(val fastest: Int, val slowest: Int) : Target {
        val middle get() = (fastest + slowest) / 2
    }
    data class Zone(val zone: Int) : Target
}

data class Step(
    val kind: StepKind,
    val length: StepLength,
    val target: Target = Target.None,
    val rep: Int = 0,
    val reps: Int = 0,
)

/** A race over a fixed distance with an optional finish-time goal ([goalSec] 0 = none). */
data class Race(val meters: Int, val goalSec: Int)

/** Activity type; stored by name in history, so entries may be added but never renamed. */
enum class Sport {
    RUN, SKI, CLIMB_INDOOR, CLIMB_OUTDOOR, HIKE;

    val isClimb get() = this == CLIMB_INDOOR || this == CLIMB_OUTDOOR
}

/**
 * A structured plan advances through [steps] (GIRO skips to the next step);
 * a free plan has a single open step and GIRO just marks a lap.
 */
data class Plan(
    val name: String,
    val steps: List<Step>,
    val structured: Boolean,
    val autoLapMeters: Int = 0,
    val race: Race? = null,
    val sport: Sport = Sport.RUN,
) {
    companion object {
        /** Warm-up/cool-down minutes: 0 = none, [UNTIL_LAP] = until GIRO is pressed. */
        const val UNTIL_LAP = -1

        fun free(target: Target = Target.None, autoLapMeters: Int = 1000, name: String = "") =
            Plan(name, listOf(Step(StepKind.RUN, StepLength.Open, target)), structured = false, autoLapMeters)

        /** Ski day: runs are detected from speed and altitude, no km laps. */
        fun ski(name: String = "") =
            Plan(name, listOf(Step(StepKind.RUN, StepLength.Open)), structured = false, sport = Sport.SKI)

        /** Climbing: GIRO toggles climbing ↔ resting, each climb is a lap. */
        fun climb(outdoor: Boolean, name: String = "") = Plan(
            name, listOf(Step(StepKind.RUN, StepLength.Open)), structured = false,
            sport = if (outdoor) Sport.CLIMB_OUTDOOR else Sport.CLIMB_INDOOR,
        )

        fun hike(autoLapMeters: Int = 1000, name: String = "") =
            Plan(name, listOf(Step(StepKind.RUN, StepLength.Open)), structured = false, autoLapMeters, sport = Sport.HIKE)

        /** A free run with km splits plus race progress toward [meters]. */
        fun race(name: String, meters: Int, goalSec: Int): Plan {
            require(meters > 0)
            return Plan(name, listOf(Step(StepKind.RUN, StepLength.Open)), structured = false, 1000, Race(meters, goalSec))
        }

        fun intervals(
            warmupMin: Int,
            reps: Int,
            work: StepLength,
            workTarget: Target,
            recovery: StepLength,
            cooldownMin: Int,
            name: String = "",
        ) = Plan(
            name,
            buildList {
                warmup(warmupMin)?.let(::add)
                for (i in 1..reps) {
                    add(Step(StepKind.WORK, work, workTarget, i, reps))
                    if (i < reps) add(Step(StepKind.RECOVERY, recovery, Target.None, i, reps))
                }
                cooldown(cooldownMin)?.let(::add)
            },
            structured = true,
        )

        fun fartlek(warmupMin: Int, cycles: Int, fastSec: Int, easySec: Int, cooldownMin: Int, name: String = "") = Plan(
            name,
            buildList {
                warmup(warmupMin)?.let(::add)
                for (i in 1..cycles) {
                    add(Step(StepKind.WORK, StepLength.Time(fastSec), Target.None, i, cycles))
                    add(Step(StepKind.EASY, StepLength.Time(easySec), Target.None, i, cycles))
                }
                cooldown(cooldownMin)?.let(::add)
            },
            structured = true,
        )

        private fun warmup(min: Int) = when {
            min == UNTIL_LAP -> Step(StepKind.WARMUP, StepLength.Open)
            min > 0 -> Step(StepKind.WARMUP, StepLength.Time(min * 60))
            else -> null
        }

        private fun cooldown(min: Int) = when {
            min == UNTIL_LAP -> Step(StepKind.COOLDOWN, StepLength.Open)
            min > 0 -> Step(StepKind.COOLDOWN, StepLength.Time(min * 60))
            else -> null
        }
    }
}
