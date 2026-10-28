package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import kotlin.math.max

/**
 * A big meal announced at its start, by the big meal button or by the size of the user's own bolus:
 * the loop may hold more insulin for it, and pushes hard while its glucose goes up.
 *
 * ## Why this exists
 *
 * The loop cannot know a meal is large before the glucose rises: a 2 U bolus may cover a salad or a
 * plate of pasta. On large starchy meals the loop reaches the max IOB within an hour of the rise
 * and then sends almost nothing while glucose stays above 200 for hours. The user knows the size of
 * the meal when it starts. Two ways to say "this is a big meal", both with the one action the user
 * already does at the start of a meal:
 * - the big meal button, an AAPS Automation that writes a note holding "gros". It opens the meal
 *   window like "eating" and gives no insulin: for a big meal started low or with insulin on board;
 * - a meal bolus of [BIG_BOLUS_U] or more.
 *
 * A first version raised the max IOB only on a rise of 15 mg/dL per 15 minutes above 180, after the
 * first 45 minutes. On a pasta dinner that plateaued at 200-250 for four hours with 7 U on board it
 * would have opened for about 15 minutes in all: the slow rise of a big meal never met it. The user
 * asked for the plain rule below instead, knowing that the extra insulin is still on board when the
 * glucose turns.
 *
 * ## The rule
 *
 * The meal is declared ([MealKnownGate]), and the big meal note was written for it or the user's own
 * boluses for it add up to [BIG_BOLUS_U] or more, in one bolus or in two parts ([mealBolusU]).
 * Until the end of the big meal (its horizon, set by the user, `MealKnownGate.bigMealHorizonMin`):
 * - the max IOB is the big meal max IOB set by the user, on every tick ([Decision.release]);
 * - on a tick where glucose is at [PUSH_MIN_BG_MGDL] or above, rose [PUSH_MIN_RISE_MGDL] or more over
 *   the last [RISE_WINDOW_MIN] minutes and is still going up on the last reading, the loop pushes
 *   ([Decision.push]): the caller gives the big meal SMB and the max basal, past the second-wave
 *   hold, the second-wave rate cap and the cap after the meal bolus.
 * As soon as glucose is flat or falls, the push stops and the loop doses as usual. Under
 * [PUSH_MIN_BG_MGDL] there is no push: a big bolus given low must not be stacked on.
 *
 * Pure: no clock, no preference. The caller passes the glucose and writes the verdict.
 */
object BigMealGate {

    const val BIG_BOLUS_U = 4.0
    const val BOLUS_BEFORE_ARM_MIN = 120L
    /** Above the line where the no-meal basal limit starts lifting: a big bolus given low is left alone. */
    const val PUSH_MIN_BG_MGDL = 140.0
    /** 3 mg/dL per 5 minutes: a slow pasta rise climbs 10 to 20 mg/dL per 15 minutes. */
    const val PUSH_MIN_RISE_MGDL = 9.0
    const val RISE_WINDOW_MIN = 15L
    /** The last step: the previous reading must be at most this far back. */
    private const val STEP_MAX_MIN = 10L

    private const val MIN_MS = 60_000L

    /** One glucose reading, in ms and mg/dL. */
    data class Reading(val timestamp: Long, val mgdl: Double)

    data class Decision(
        /** A big meal is declared and still inside its window. */
        val bigMeal: Boolean,
        /** The big meal note said so (the bolus may be smaller). */
        val byNote: Boolean = false,
        /** The big meal max IOB applies on this tick. */
        val release: Boolean = false,
        /** Glucose is going up: give the big meal SMB and the max basal. */
        val push: Boolean = false,
        val minutesSinceArm: Long = 0L,
        val bolusU: Double = 0.0,
        val bgMgdl: Double = 0.0,
        val riseMgdl: Double = 0.0,
        /** Change since the previous reading. */
        val stepMgdl: Double = 0.0,
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
        if (!iobU.isFinite() || !maxIobU.isFinite() || !bigMealMaxIobU.isFinite()) return Decision.INACTIVE
        // A big meal max IOB under the user's own changes nothing.
        val raised = bigMealMaxIobU > maxIobU
        val extra = if (raised) max(bigMealMaxIobU - iobU, 0.0) - max(maxIobU - iobU, 0.0) else 0.0
        val base = Decision(
            bigMeal = true,
            byNote = byNote,
            release = raised,
            minutesSinceArm = sinceArm,
            bolusU = if (bolusU.isFinite()) bolusU else 0.0,
            iobU = iobU,
            maxIobU = maxIobU,
            raisedMaxIobU = if (raised) bigMealMaxIobU else maxIobU,
            extraRoomU = extra,
        )
        val valid = readings.filter { it.timestamp <= now && it.mgdl.isFinite() && it.mgdl > 39.0 }.sortedBy { it.timestamp }
        val last = valid.lastOrNull() ?: return base
        val previous = valid.lastOrNull { it.timestamp < last.timestamp } ?: return base.copy(bgMgdl = last.mgdl)
        val before = valid.lastOrNull { it.timestamp <= last.timestamp - RISE_WINDOW_MIN * MIN_MS } ?: return base.copy(bgMgdl = last.mgdl)
        // A gap in the data is not a rise: the earlier readings must be about where they belong.
        if (last.timestamp - before.timestamp > (RISE_WINDOW_MIN + 5L) * MIN_MS) return base.copy(bgMgdl = last.mgdl)
        if (last.timestamp - previous.timestamp > STEP_MAX_MIN * MIN_MS) return base.copy(bgMgdl = last.mgdl)
        val rise = last.mgdl - before.mgdl
        val step = last.mgdl - previous.mgdl
        val push = last.mgdl >= PUSH_MIN_BG_MGDL && rise >= PUSH_MIN_RISE_MGDL && step > 0.0
        return base.copy(bgMgdl = last.mgdl, riseMgdl = rise, stepMgdl = step, push = push)
    }
}
