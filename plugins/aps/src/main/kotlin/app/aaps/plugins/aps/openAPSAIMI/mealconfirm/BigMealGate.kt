package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import kotlin.math.max

/**
 * A big meal announced at its start, by the big meal button or by the size of the user's own bolus:
 * while its rise goes on, let the loop dose it above the usual max IOB instead of holding it back.
 *
 * ## Why this exists
 *
 * The loop cannot know a meal is large before the glucose rises: a 2 U bolus may cover a salad or a
 * plate of pasta. On large starchy meals the rise comes fast, the loop reaches the max IOB within
 * half an hour of it and then sends almost nothing while glucose climbs past 300 and stays there.
 * The user knows the size of the meal when it starts. Two ways to say "this is a big meal, expect a
 * big second wave", both with the one action the user already does at the start of a meal:
 * - the big meal button, an AAPS Automation that writes a note holding "gros". It opens the meal
 *   window like "eating" and gives no insulin: for a big meal started low or with insulin on board;
 * - a meal bolus of [BIG_BOLUS_U] or more.
 *
 * Replayed on a month of declared meals, no meal bolus ever reached [BIG_BOLUS_U]: this is a new
 * habit, not a pattern found in the log. In a field log a big bread dinner, declared with the note,
 * reached the max IOB of 7 U at about 190 mg/dL: from then on each SMB was cut to 0.05 U while the
 * loop asked for 0.6 U, and glucose climbed to 320 over the next two hours.
 *
 * ## The rule
 *
 * The meal is declared ([MealKnownGate]), and the big meal note was written for it or the user's own
 * boluses for it add up to [BIG_BOLUS_U] or more, in one bolus or in two parts ([mealBolusU]).
 * From [MIN_DELAY_MIN] after the declaration to the end of the big meal (its horizon, set by the
 * user, `MealKnownGate.bigMealHorizonMin`), on a tick where glucose is at [MIN_BG_MGDL] or above and
 * rose [MIN_RISE_MGDL] or more over the last [RISE_WINDOW_MIN] minutes, the max IOB is the big meal
 * max IOB set by the user instead of the usual one. As soon as the rise stops, the usual max IOB is
 * back. A bolus of 1 to 4 U changes nothing.
 *
 * Only the max IOB changes. The second-wave hold and the second-wave rate cap still apply: a second
 * rise of a big meal is dosed little by little like any other. When they stood aside, a second rise
 * got about 4.5 U in 35 minutes, the fast dose those rules were made to stop.
 *
 * Pure: no clock, no preference. The caller passes the glucose and writes the verdict.
 */
object BigMealGate {

    const val BIG_BOLUS_U = 4.0
    const val BOLUS_BEFORE_ARM_MIN = 120L
    const val MIN_DELAY_MIN = 45L
    const val MIN_BG_MGDL = 180.0
    /** 1 mg/dL per minute: the bread dinner climbed 10 to 15 mg/dL every 5 minutes. */
    const val MIN_RISE_MGDL = 15.0
    const val RISE_WINDOW_MIN = 15L

    private const val MIN_MS = 60_000L

    /** One glucose reading, in ms and mg/dL. */
    data class Reading(val timestamp: Long, val mgdl: Double)

    data class Decision(
        /** A big meal is declared and still inside its window. */
        val bigMeal: Boolean,
        /** The big meal note said so (the bolus may be smaller). */
        val byNote: Boolean = false,
        /** This tick is a rise of that meal: the raised max IOB applies. */
        val release: Boolean = false,
        val minutesSinceArm: Long = 0L,
        val bolusU: Double = 0.0,
        val bgMgdl: Double = 0.0,
        val riseMgdl: Double = 0.0,
        val iobU: Double = 0.0,
        val maxIobU: Double = 0.0,
        /** The big meal max IOB, never under the user's own. */
        val raisedMaxIobU: Double = 0.0,
        /** Insulin the raised max IOB leaves room for, above what the user's max IOB allows. */
        val extraRoomU: Double = 0.0,
    ) {
        companion object {
            val INACTIVE = Decision(bigMeal = false)
        }
    }

    /**
     * Total of the user's own boluses for the meal declared at [armedAtMs]: those given at most
     * [BOLUS_BEFORE_ARM_MIN] before it and after it. A meal is often bolused in two parts, half at
     * the start and half when glucose starts to rise, and each bolus restarts the declaration: the
     * first part must still count.
     */
    fun mealBolusU(armedAtMs: Long, now: Long, boluses: List<Pair<Long, Double>>): Double =
        boluses
            .filter { (t, u) -> t >= armedAtMs - BOLUS_BEFORE_ARM_MIN * MIN_MS && t <= now && u.isFinite() && u > 0.0 }
            .sumOf { it.second }

    /**
     * @param armedAtMs when the meal was declared ([MealKnownGate]), 0 when there is none.
     * @param bolusU the user's own boluses for this meal, see [mealBolusU].
     * @param bigMealNoteMs when the newest big meal note was written, 0 when there is none.
     * @param readings glucose since a little before [now], any order.
     * @param maxIobU the user's max IOB.
     * @param bigMealMaxIobU the big meal max IOB set by the user.
     * @param horizonMin how long a big meal lasts, from its declaration.
     */
    fun evaluate(
        now: Long,
        armedAtMs: Long,
        bolusU: Double,
        readings: List<Reading>,
        iobU: Double,
        maxIobU: Double,
        bigMealMaxIobU: Double,
        horizonMin: Long,
        bigMealNoteMs: Long = 0L,
    ): Decision {
        if (armedAtMs <= 0L || now < armedAtMs) return Decision.INACTIVE
        val byNote = bigMealNoteMs > 0L && bigMealNoteMs >= armedAtMs - BOLUS_BEFORE_ARM_MIN * MIN_MS && bigMealNoteMs <= now
        val byBolus = bolusU.isFinite() && bolusU >= BIG_BOLUS_U
        if (!byNote && !byBolus) return Decision.INACTIVE
        val sinceArm = (now - armedAtMs) / MIN_MS
        if (sinceArm > horizonMin) return Decision.INACTIVE
        val waiting = Decision(
            bigMeal = true,
            byNote = byNote,
            minutesSinceArm = sinceArm,
            bolusU = if (bolusU.isFinite()) bolusU else 0.0,
            iobU = iobU,
            maxIobU = maxIobU,
        )
        val valid = readings.filter { it.timestamp <= now && it.mgdl.isFinite() && it.mgdl > 39.0 }.sortedBy { it.timestamp }
        val last = valid.lastOrNull() ?: return waiting
        val before = valid.lastOrNull { it.timestamp <= last.timestamp - RISE_WINDOW_MIN * MIN_MS } ?: return waiting
        // A gap in the data is not a rise: the earlier reading must be about one window back.
        if (last.timestamp - before.timestamp > (RISE_WINDOW_MIN + 5L) * MIN_MS) return waiting
        val rise = last.mgdl - before.mgdl
        val seen = waiting.copy(bgMgdl = last.mgdl, riseMgdl = rise)
        if (sinceArm < MIN_DELAY_MIN || last.mgdl < MIN_BG_MGDL || rise < MIN_RISE_MGDL) return seen
        if (!iobU.isFinite() || !maxIobU.isFinite() || !bigMealMaxIobU.isFinite()) return seen
        // A big meal max IOB under the user's own changes nothing.
        if (bigMealMaxIobU <= maxIobU) return seen
        val extra = max(bigMealMaxIobU - iobU, 0.0) - max(maxIobU - iobU, 0.0)
        return seen.copy(release = true, raisedMaxIobU = bigMealMaxIobU, extraRoomU = extra)
    }
}
