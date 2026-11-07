package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Glucose stuck high with no meal declared: a bigger SMB, a little at a time.
 *
 * ## Why this exists
 *
 * Outside the active meal window the loop treats a high mostly with basal. Over two weeks of loop
 * runs at 220 mg/dL or more, going up or flat: inside the meal window the SMB averaged 0.29 U
 * (28 % of runs at 0.5 U or more); four to six hours after the meal and at night with no meal it
 * averaged 0.07 to 0.10 U (0 to 3 % at 0.5 U or more), with the basal at its safety limit. The
 * insulin-on-board surveillance and the PK/PD guard shrink every SMB once 4 to 5 U are on board.
 * In a field night glucose stayed at 250-290 for two hours that way.
 *
 * A replay of this rule on six weeks of data found 7 episodes, 1 to 2.5 U added each, and none of
 * them followed by glucose under 140 in the next five hours (apart from a new sensor night).
 *
 * ## What it does
 *
 * When no meal is declared, glucose has been [MIN_BG_MGDL] or more on every reading of the last
 * [STUCK_MIN] minutes and is not coming down (no more than [MAX_FALL_30_MGDL] lower than 30
 * minutes ago and less than [MAX_FALL_15_MGDL] lower than 15 minutes ago), and insulin on board is
 * under [MAX_IOB_U]:
 * - the SMB is raised so that [SMB_PER_WINDOW_U] goes in every [WINDOW_MIN] minutes, counting the
 *   SMB already given in that window;
 * - no more than [EPISODE_BUDGET_U] of SMB over [EPISODE_MIN] minutes, whoever gave it;
 * - never past the max IOB.
 * It never lowers a dose and never touches the basal.
 *
 * Pure: no clock, no preference. The caller passes the state and owns the dose.
 */
object StuckHighGate {

    const val MIN_BG_MGDL = 200.0
    const val STUCK_MIN = 60L
    const val MAX_FALL_30_MGDL = 15.0
    const val MAX_FALL_15_MGDL = 10.0
    const val MAX_IOB_U = 5.0
    const val SMB_PER_WINDOW_U = 0.5
    const val WINDOW_MIN = 15L
    const val EPISODE_BUDGET_U = 3.0
    const val EPISODE_MIN = 120L

    /** Readings may be this far apart before the curve counts as broken. */
    private const val MAX_GAP_MIN = 15L
    private const val MIN_MS = 60_000L

    data class Reading(val timestamp: Long, val mgdl: Double)

    data class Decision(
        /** Glucose is stuck high on this tick (the rule would act if the dose is short). */
        val stuck: Boolean,
        /** The SMB this tick should send at least, 0 when nothing is to add. */
        val smbTargetU: Double = 0.0,
        val bgMgdl: Double = 0.0,
        val fall30Mgdl: Double = 0.0,
        val smbLastWindowU: Double = 0.0,
        val smbLastEpisodeU: Double = 0.0,
        /** Why it does not act, for the reason line. Empty when it acts. */
        val why: String = "",
    ) {
        companion object {
            val INACTIVE = Decision(stuck = false)
        }
    }

    /**
     * @param mealDeclared a meal is declared on this tick (the meal treatment is on).
     * @param readings glucose readings, at least the last [STUCK_MIN] + 5 minutes, any order.
     * @param smbTimesAndUnits SMB given, as (timestamp, units), at least over [EPISODE_MIN] minutes.
     */
    fun evaluate(
        now: Long,
        mealDeclared: Boolean,
        activity: Boolean,
        readings: List<Reading>,
        iobU: Double,
        maxIobU: Double,
        proposedSmbU: Double,
        smbTimesAndUnits: List<Pair<Long, Double>>,
    ): Decision {
        if (mealDeclared || activity || !iobU.isFinite()) return Decision.INACTIVE
        val sorted = readings.filter { it.mgdl.isFinite() && it.timestamp <= now }.sortedBy { it.timestamp }
        val last = sorted.lastOrNull() ?: return Decision.INACTIVE
        if (now - last.timestamp > MAX_GAP_MIN * MIN_MS) return Decision.INACTIVE
        val bg = last.mgdl
        if (bg < MIN_BG_MGDL) return Decision.INACTIVE
        val from = now - STUCK_MIN * MIN_MS
        val window = sorted.filter { it.timestamp >= from - 5 * MIN_MS }
        // The curve must reach back to the start of the window, with no hole in it.
        if (window.isEmpty() || window.first().timestamp > from + 5 * MIN_MS) return Decision.INACTIVE
        if (window.zipWithNext().any { (a, b) -> b.timestamp - a.timestamp > MAX_GAP_MIN * MIN_MS }) return Decision.INACTIVE
        if (window.any { it.mgdl < MIN_BG_MGDL }) return Decision.INACTIVE
        val bg30 = at(sorted, now - 30 * MIN_MS) ?: return Decision.INACTIVE
        val bg15 = at(sorted, now - 15 * MIN_MS) ?: return Decision.INACTIVE
        val fall30 = bg - bg30
        val fall15 = bg - bg15
        if (fall30 < -MAX_FALL_30_MGDL || fall15 <= -MAX_FALL_15_MGDL) return Decision.INACTIVE

        val inWindow = smbTimesAndUnits.filter { it.first > now - WINDOW_MIN * MIN_MS && it.first <= now }.sumOf { max(it.second, 0.0) }
        val inEpisode = smbTimesAndUnits.filter { it.first > now - EPISODE_MIN * MIN_MS && it.first <= now }.sumOf { max(it.second, 0.0) }
        val base = Decision(stuck = true, bgMgdl = bg, fall30Mgdl = fall30, smbLastWindowU = inWindow, smbLastEpisodeU = inEpisode)
        if (iobU >= MAX_IOB_U) return base.copy(why = "IOB ${"%.1f".format(iobU)} >= ${MAX_IOB_U}")
        val proposed = if (proposedSmbU.isFinite()) max(proposedSmbU, 0.0) else 0.0
        val target = min(
            min(SMB_PER_WINDOW_U - inWindow, EPISODE_BUDGET_U - inEpisode),
            if (maxIobU.isFinite()) maxIobU - iobU else 0.0,
        )
        // The small margin keeps 0.3 from turning into 0.25 on float error.
        val quantized = floor(max(target, 0.0) * 20.0 + 1e-6) / 20.0
        if (quantized <= proposed || quantized <= 0.0) {
            val why = when {
                inEpisode >= EPISODE_BUDGET_U - 0.001 -> "episode budget used"
                inWindow >= SMB_PER_WINDOW_U - 0.001 -> "already given"
                else -> "dose already enough"
            }
            return base.copy(why = why)
        }
        return base.copy(smbTargetU = quantized)
    }

    /** Glucose at [t]: the reading closest to it, within 7 minutes. */
    private fun at(sorted: List<Reading>, t: Long): Double? =
        sorted.minByOrNull { abs(it.timestamp - t) }
            ?.takeIf { abs(it.timestamp - t) <= 7 * MIN_MS }?.mgdl
}
