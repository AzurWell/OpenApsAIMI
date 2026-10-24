package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import kotlin.math.max
import kotlin.math.min

/**
 * A second rise of a meal that has proven itself: add little at a time, as at the start of the meal.
 *
 * ## Why this exists
 *
 * [SecondWaveGate] holds a second rise back until it climbed 35 mg/dL above its trough. Once it has,
 * nothing limits the loop any more: rise exemption, the max basal and the largest SMB on every tick,
 * so the second rise gets more in half an hour than the start of the meal, where [BolusEarlyCapGate]
 * allows 1 U. In a field log a lunch came down to 91, rose slowly to 126 more than three hours after
 * the meal, and got about 4 U in 35 minutes (insulin on board 1.3 → 4.8 U) for a rise that topped
 * at 164, then fell on the way to a low.
 *
 * Over a month of proven second rises, the loop added 2 to 5 U in the first 35 minutes on most of
 * them. Lows behind them cannot be counted reliably: the user eats sugar on most afternoons to stop
 * the fall, and that sugar is not recorded.
 *
 * ## What it does
 *
 * While the second rise is proven and glucose is under [SecondWaveGate.HOLD_CEILING_MGDL]:
 * - SMB may add at most [SMB_BUDGET_U] over any [WINDOW_MIN] minutes, counted from the SMB already
 *   given in that window;
 * - the basal stays at or under the scheduled rate.
 * At 200 mg/dL or more nothing is limited: a real big wave is dosed as usual, only from there. The
 * user's own bolus is never touched, and pressing "eating" again starts the meal over.
 *
 * Pure: no clock, no preference. The caller passes the state and owns the dose.
 */
object SecondWaveRateGate {

    /** Length of the sliding window the SMB budget is counted on, in minutes. */
    const val WINDOW_MIN = 30L

    /** Most SMB the loop may add during [WINDOW_MIN], in units. Same as [BolusEarlyCapGate.SMB_BUDGET_U]. */
    const val SMB_BUDGET_U = 1.0

    data class Decision(
        /** The cap is active on this tick. */
        val active: Boolean,
        /** SMB already given in the last [WINDOW_MIN] minutes, in units. */
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
     * @param proven the second rise of the meal has proven itself on this tick, see [SecondWaveGate.Verdict.proven].
     * @param bgMgdl glucose now.
     * @param smbLastWindowU SMB given in the last [WINDOW_MIN] minutes.
     * @param proposedSmbU what the tick wants to send now.
     * @param proposedRateUph the basal the tick will run, or null when it sets none.
     * @param scheduledUph the profile basal now.
     */
    fun evaluate(
        proven: Boolean,
        bgMgdl: Double,
        smbLastWindowU: Double,
        proposedSmbU: Double,
        proposedRateUph: Double?,
        scheduledUph: Double,
    ): Decision {
        if (!proven || !bgMgdl.isFinite() || bgMgdl >= SecondWaveGate.HOLD_CEILING_MGDL) return Decision.INACTIVE
        val spent = if (smbLastWindowU.isFinite()) max(smbLastWindowU, 0.0) else 0.0
        val proposed = if (proposedSmbU.isFinite()) max(proposedSmbU, 0.0) else 0.0
        val left = max(SMB_BUDGET_U - spent, 0.0)
        val rateAllowed = if (proposedRateUph != null && proposedRateUph.isFinite() && scheduledUph.isFinite()) {
            min(proposedRateUph, scheduledUph)
        } else {
            null
        }
        return Decision(
            active = true,
            smbSpentU = spent,
            smbAllowedU = min(proposed, left),
            rateAllowedUph = rateAllowed,
        )
    }
}
