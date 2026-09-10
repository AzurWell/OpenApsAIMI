package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import app.aaps.core.keys.interfaces.Preferences

/**
 * The second rise of a meal that is already running.
 *
 * Measured on the user this was written for, over 90 days: 43 lunches, and **12 of them followed by
 * glucose under 70 mg/dL (28 %)**, with the low points grouped between 16:40 and 17:20. The shape
 * is always the same. The peak after lunch comes down, glucose rises again two to four hours after
 * the meal, and AIMI reads that second rise as a brand new meal. It has no memory of the first one:
 * [app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase] holds one phase per tick. On
 * 2026-08-13 the loop gave **11.5 U in 22 SMBs** over two hours and twenty minutes, with no manual
 * bolus at all, and glucose went from 289 to 56.
 *
 * A simple cap would not have helped. Splitting those days by peak IOB (6 U or more: 24 % of hypos,
 * under 6 U: 22 %) or by SMB volume (4 U or more: 21 %, under 4 U: 23 %) separates nothing. The
 * problem is not "too much insulin", it is "as much as for a new meal while the first meal is still
 * working". So the gate needs the meal context from [MealKnownGate], not a threshold.
 *
 * Upstream already has `DescentRedoseGuard` for the case next door, and it leaves this one out on
 * purpose: its last condition asks glucose to stay within 25 mg/dL of the low reached since the
 * peak, "which is what keeps a new rise out". A second rise of 36 to 178 mg/dL is that new rise.
 *
 * This gate only reduces, and only on the **bolus** channel. The temporary basal is left alone, so
 * the loop can still hold a real rise without stacking boluses on top of it.
 */
object SecondWaveGate {

    /**
     * Before this much time has passed, the rise is still the first wave of the meal and must be
     * dosed normally. Two hours also puts the gate after the fast carb peak.
     */
    const val MIN_ELAPSED_MIN = 120L

    /** Under this, nothing is stacked yet, so there is nothing to protect against. */
    const val MIN_IOB_U = 4.0

    /** Largest SMB allowed while a second rise is running. */
    const val SMB_CEILING_U = 0.4

    /**
     * Total SMB allowed for the whole second rise. On 2026-08-13 this would have given 3 U instead
     * of 11.5 U. Once the budget is used up the bolus channel closes, and the basal keeps working.
     *
     * This number is a starting point taken from the days above, not a measured optimum. Run the
     * gate in shadow mode first and read the exported verdict before turning it on.
     */
    const val BUDGET_U = 3.0

    /** A real rise, not noise. */
    const val MIN_DELTA_MGDL = 2.0

    /** Under this there is no rise worth fighting, whatever the delta says. */
    const val MIN_BG_MGDL = 140.0

    data class Verdict(
        /** True when this tick is inside a second rise. */
        val active: Boolean,
        /** Ceiling to put on the SMB, or null when the gate does not limit this tick. */
        val ceilingU: Double?,
        /** Short reason for `rT.reason` and the logs. */
        val reason: String,
    ) {

        companion object {

            val INACTIVE = Verdict(active = false, ceilingU = null, reason = "idle")
        }
    }

    /**
     * Looks at one tick. This function writes nothing, so the verdict can be worked out and
     * exported on every tick even when the feature is off. That way the effect can be measured
     * before it is turned on, the same way upstream ships `DescentRedoseGuard`.
     *
     * @param smbDeliveredSinceArmU SMB units given since the meal window was opened.
     */
    fun evaluate(
        preferences: Preferences,
        now: Long,
        iobU: Double,
        declaredCobG: Double,
        bgMgdl: Double,
        deltaMgdl: Double,
        smbDeliveredSinceArmU: Double,
    ): Verdict {
        if (declaredCobG > 0.0) return Verdict.INACTIVE
        val elapsedMs = MealKnownGate.elapsedSinceArmMs(preferences, now) ?: return Verdict.INACTIVE
        val elapsedMin = elapsedMs / 60_000L
        if (elapsedMin < MIN_ELAPSED_MIN) return Verdict.INACTIVE
        if (iobU < MIN_IOB_U) return Verdict.INACTIVE
        if (bgMgdl < MIN_BG_MGDL) return Verdict.INACTIVE
        if (deltaMgdl < MIN_DELTA_MGDL) return Verdict.INACTIVE

        val budgetLeft = (BUDGET_U - smbDeliveredSinceArmU).coerceAtLeast(0.0)
        val ceiling = minOf(SMB_CEILING_U, budgetLeft)
        return Verdict(
            active = true,
            ceilingU = ceiling,
            reason = "2nd wave t+${elapsedMin}min iob=${"%.1f".format(iobU)}U " +
                "left=${"%.1f".format(budgetLeft)}U cap=${"%.2f".format(ceiling)}U",
        )
    }
}
