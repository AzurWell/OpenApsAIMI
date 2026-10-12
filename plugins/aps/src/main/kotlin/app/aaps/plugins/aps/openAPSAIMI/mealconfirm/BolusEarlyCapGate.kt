package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import kotlin.math.max
import kotlin.math.min

/**
 * Limits what the loop adds on top of the user's own meal bolus, in the first minutes after it.
 *
 * ## Why this exists
 *
 * The user's meal bolus is already their own guess of the meal: a small one for a light meal, a big
 * one for a fat or large meal. Just after it, the loop sees the first part of the rise and doses it
 * like a new meal: SMB on every tick and a boosted basal, while the bolus has not started to act yet.
 * On a field log of 42 declared meals, the meals where the loop added more than 1 U in the first
 * 30 minutes after a manual bolus ended under 70 mg/dL three times out of 13. With 1 U or less it was
 * one time out of 16, and their peaks were not higher. Meals declared with the eating note only
 * (no bolus) never ended low, so they are not touched here.
 *
 * ## What it does
 *
 * For [WINDOW_MIN] minutes after the manual bolus that opened the meal window:
 * - SMB may add at most [SMB_BUDGET_U] in total, counted from the SMB already given since the bolus;
 * - the basal stays at or under the scheduled rate.
 * After that window, nothing changes. The user's bolus itself is never touched.
 *
 * Pure: no clock, no preference. The caller passes the state and owns the dose.
 */
object BolusEarlyCapGate {

    /** How long after the manual bolus the cap holds, in minutes. */
    const val WINDOW_MIN = 30L

    /** Most SMB the loop may add on top of the bolus during [WINDOW_MIN], in units. */
    const val SMB_BUDGET_U = 1.0

    private const val MIN_MS = 60_000L

    data class Decision(
        /** The cap is active on this tick. */
        val active: Boolean,
        /** Minutes since the bolus, when active. */
        val minutesSinceBolus: Long = 0L,
        /** SMB already given since the bolus, in units. */
        val smbSpentU: Double = 0.0,
        /** The SMB the tick may send. Equal to the proposed SMB when nothing is cut. */
        val smbAllowedU: Double = 0.0,
        /** The basal the tick may use, or null when the rate is not changed. */
        val rateAllowedUph: Double? = null,
    ) {
        /** True when this tick would send less than it proposed. */
        fun cuts(proposedSmbU: Double, proposedRateUph: Double?): Boolean =
            smbAllowedU < proposedSmbU || (rateAllowedUph != null && proposedRateUph != null && rateAllowedUph < proposedRateUph)

        companion object {
            val INACTIVE = Decision(active = false)
        }
    }

    /**
     * @param now wall clock in ms.
     * @param bolusAtMs time of the manual bolus that opened the meal window, 0 when there is none.
     * @param mealKnown the meal window is still open (a "correction" note closes it).
     * @param smbSinceBolusU SMB already given since the bolus, without the bolus itself.
     * @param proposedSmbU what the tick wants to send now.
     * @param proposedRateUph the basal the tick will run, or null when it sets none.
     * @param scheduledUph the profile basal now.
     */
    fun evaluate(
        now: Long,
        bolusAtMs: Long,
        mealKnown: Boolean,
        smbSinceBolusU: Double,
        proposedSmbU: Double,
        proposedRateUph: Double?,
        scheduledUph: Double,
    ): Decision {
        if (!mealKnown || bolusAtMs <= 0L || now < bolusAtMs) return Decision.INACTIVE
        val elapsedMs = now - bolusAtMs
        if (elapsedMs >= WINDOW_MIN * MIN_MS) return Decision.INACTIVE
        val spent = if (smbSinceBolusU.isFinite()) max(smbSinceBolusU, 0.0) else 0.0
        val proposed = if (proposedSmbU.isFinite()) max(proposedSmbU, 0.0) else 0.0
        val left = max(SMB_BUDGET_U - spent, 0.0)
        val rateAllowed = if (proposedRateUph != null && proposedRateUph.isFinite() && scheduledUph.isFinite()) {
            min(proposedRateUph, scheduledUph)
        } else {
            null
        }
        return Decision(
            active = true,
            minutesSinceBolus = elapsedMs / MIN_MS,
            smbSpentU = spent,
            smbAllowedU = min(proposed, left),
            rateAllowedUph = rateAllowed,
        )
    }
}
