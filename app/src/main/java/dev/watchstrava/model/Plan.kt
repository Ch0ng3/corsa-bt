package dev.watchstrava.model

enum class StepKind(val label: String) {
    WARMUP("Riscaldamento"),
    WORK("Veloce"),
    EASY("Lento"),
    RECOVERY("Recupero"),
    COOLDOWN("Defaticamento"),
    RUN("Corsa"),
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

/**
 * A structured plan advances through [steps] (GIRO skips to the next step);
 * a free plan has a single open step and GIRO just marks a lap.
 */
data class Plan(
    val name: String,
    val steps: List<Step>,
    val structured: Boolean,
    val autoLapMeters: Int = 0,
) {
    companion object {
        /** Warm-up/cool-down minutes: 0 = none, [UNTIL_LAP] = until GIRO is pressed. */
        const val UNTIL_LAP = -1

        fun free(target: Target = Target.None, autoLapMeters: Int = 1000, name: String = "Corsa libera") =
            Plan(name, listOf(Step(StepKind.RUN, StepLength.Open, target)), structured = false, autoLapMeters)

        fun intervals(
            warmupMin: Int,
            reps: Int,
            work: StepLength,
            workTarget: Target,
            recovery: StepLength,
            cooldownMin: Int,
        ) = Plan(
            "Ripetute",
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

        fun fartlek(warmupMin: Int, cycles: Int, fastSec: Int, easySec: Int, cooldownMin: Int) = Plan(
            "Fartlek",
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
