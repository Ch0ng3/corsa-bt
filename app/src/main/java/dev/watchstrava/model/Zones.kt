package dev.watchstrava.model

/** Heart-rate zones as % of max HR (Garmin default: Z1 50–60 … Z5 90–100). */
object Zones {
    val names = listOf("Riposo", "Recupero", "Base", "Aerobico", "Soglia", "Massimo")

    /** 0 = below Z1, 1..5 = Z1..Z5. */
    fun zoneOf(bpm: Int, maxHr: Int): Int = (5 downTo 1).firstOrNull { bpm >= lower(it, maxHr) } ?: 0

    /** Lower bound (inclusive) of [zone] in bpm. */
    fun lower(zone: Int, maxHr: Int) = maxHr * (40 + 10 * zone) / 100

    /** Upper bound (exclusive) of [zone] in bpm; Z5 ends at max HR. */
    fun upper(zone: Int, maxHr: Int) = if (zone >= 5) maxHr else lower(zone + 1, maxHr)

    /** Position of [bpm] on a 50–100% scale, 0..1 — used by the zone gauge. */
    fun gaugePosition(bpm: Int, maxHr: Int): Float =
        ((bpm * 100f / maxHr - 50f) / 50f).coerceIn(0f, 1f)
}
