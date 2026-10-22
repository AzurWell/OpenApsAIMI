package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import kotlin.math.max

/**
 * A big meal announced by the size of the user's own bolus: when its later rise takes off, let the
 * loop dose it above the usual max IOB instead of holding it back. Shadow only for now.
 *
 * ## Why this exists
 *
 * The loop cannot know a meal is large before the glucose rises: a 2 U bolus may cover a salad or a
 * plate of pasta. On large starchy meals the rise comes fast, the loop reaches the max IOB within
 * half an hour of it and then sends almost nothing while glucose climbs past 300 and stays there.
 * The user knows the size of the meal when they bolus. A bolus of [BIG_BOLUS_U] or more is taken as
 * "this is a big meal, expect a big second wave".
 *
 * Replayed on a month of declared meals, no meal bolus ever reached [BIG_BOLUS_U]: this is a new
 * habit, not a pattern found in the log, so nothing here is tuned yet and the rule only reports.
 *
 * ## The rule
 *
 * The meal is declared ([MealKnownGate]) and the user's own boluses for it add up to [BIG_BOLUS_U]
 * or more, in one bolus or in two parts ([mealBolusU]). Between [MIN_DELAY_MIN] and [MAX_DELAY_MIN] after the declaration, on a tick where
 * glucose is at [MIN_BG_MGDL] or above and rose [FAST_RISE_MGDL] or more over the last
 * [RISE_WINDOW_MIN] minutes, the max IOB would be [RAISED_MAX_IOB_U] instead of the user's own.
 * The second-wave hold and the late-wave cap would stand aside on those ticks. As soon as the rise
 * slows down, everything is back to normal. A bolus of 1 to 4 U changes nothing.
 *
 * Pure: no clock, no preference. The caller passes the glucose and writes the verdict.
 */
object BigMealGate {

    const val BIG_BOLUS_U = 5.0
    const val BOLUS_BEFORE_ARM_MIN = 120L
    const val MIN_DELAY_MIN = 45L
    const val MAX_DELAY_MIN = 300L
    const val MIN_BG_MGDL = 180.0
    const val FAST_RISE_MGDL = 30.0
    const val RISE_WINDOW_MIN = 15L
    const val RAISED_MAX_IOB_U = 10.0

    private const val MIN_MS = 60_000L

    /** One glucose reading, in ms and mg/dL. */
    data class Reading(val timestamp: Long, val mgdl: Double)

    data class Decision(
        /** A big meal is declared and still inside its window. */
        val bigMeal: Boolean,
        /** This tick is a fast rise of that meal: the raised max IOB would apply. */
        val release: Boolean = false,
        val minutesSinceArm: Long = 0L,
        val bolusU: Double = 0.0,
        val bgMgdl: Double = 0.0,
        val riseMgdl: Double = 0.0,
        val iobU: Double = 0.0,
        val maxIobU: Double = 0.0,
        /** Insulin the raised max IOB would leave room for, above what the user's max IOB allows. */
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
     * @param readings glucose since a little before [now], any order.
     * @param maxIobU the user's max IOB.
     */
    fun evaluate(
        now: Long,
        armedAtMs: Long,
        bolusU: Double,
        readings: List<Reading>,
        iobU: Double,
        maxIobU: Double,
    ): Decision {
        if (armedAtMs <= 0L || now < armedAtMs || !bolusU.isFinite() || bolusU < BIG_BOLUS_U) return Decision.INACTIVE
        val sinceArm = (now - armedAtMs) / MIN_MS
        if (sinceArm > MAX_DELAY_MIN) return Decision.INACTIVE
        val waiting = Decision(bigMeal = true, minutesSinceArm = sinceArm, bolusU = bolusU, iobU = iobU, maxIobU = maxIobU)
        val valid = readings.filter { it.timestamp <= now && it.mgdl.isFinite() && it.mgdl > 39.0 }.sortedBy { it.timestamp }
        val last = valid.lastOrNull() ?: return waiting
        val before = valid.lastOrNull { it.timestamp <= last.timestamp - RISE_WINDOW_MIN * MIN_MS } ?: return waiting
        // A gap in the data is not a rise: the earlier reading must be about one window back.
        if (last.timestamp - before.timestamp > (RISE_WINDOW_MIN + 5L) * MIN_MS) return waiting
        val rise = last.mgdl - before.mgdl
        val seen = waiting.copy(bgMgdl = last.mgdl, riseMgdl = rise)
        if (sinceArm < MIN_DELAY_MIN || last.mgdl < MIN_BG_MGDL || rise < FAST_RISE_MGDL) return seen
        if (!iobU.isFinite() || !maxIobU.isFinite()) return seen
        val raised = max(RAISED_MAX_IOB_U, maxIobU)
        val extra = max(raised - iobU, 0.0) - max(maxIobU - iobU, 0.0)
        return seen.copy(release = true, extraRoomU = extra)
    }
}
