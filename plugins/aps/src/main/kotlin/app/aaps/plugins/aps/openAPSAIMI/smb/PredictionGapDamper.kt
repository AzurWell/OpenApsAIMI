package app.aaps.plugins.aps.openAPSAIMI.smb

/**
 * Scales the bolus down when the loop's own insulin-only projection already lands well below target.
 *
 * Every tick, the prediction pipeline publishes where glucose ends up on the insulin already on
 * board and nothing else — `IOBpredBG`. When that number sits far under the target, the insulin in
 * the body is already more than the situation needs, and another bolus is a bet that carbs will
 * arrive to absorb it. Sometimes that bet is right. It is never free.
 *
 * ## Why this exists
 *
 * Measured over 1431 ticks, 2026-09-11 to 2026-09-15, one user:
 *
 * | `IOBpredBG` vs target | ticks | insulin given | mean dose |
 * |---|---|---|---|
 * | **more than 60 below** | 497 | **35.41 U** | **0.43 U** |
 * | 20 to 60 below | 537 | 102.58 U | 0.45 U |
 * | around target | 336 | 38.86 U | 0.39 U |
 * | above target | 61 | 22.50 U | 0.45 U |
 *
 * The mean dose is the same in every row. The projection has **no influence at all** on how much is
 * delivered — 35 U over five days went out while the loop itself was computing a landing more than
 * 60 mg/dL under target. Those are the ticks that preceded the hypoglycaemias: 2026-09-14 at 17:08,
 * `IOBpredBG 39` against a target of 104, and 0.70 U went out anyway.
 *
 * ## What it is not
 *
 * It is not a hypo guard — those exist already and sit underneath. This never refuses a dose, it
 * only takes a share of it, so a genuine fast rise still gets treated, just less hard while the
 * loop's own arithmetic says the insulin is ahead of the glucose.
 *
 * Reduction only, bolus channel only. Upstream's `RiseCeilingGuard` covers the neighbouring case —
 * the same maximum dose repeated during a fast climb — and keys on the ceiling and the slope. This
 * one keys on the projection, and catches the slower rises that never reach the ceiling.
 */
object PredictionGapDamper {

    /** Under this gap the projection is close enough to target to change nothing. */
    const val GAP_FREE_MGDL = 20.0

    /** At this gap only [MIN_FACTOR] of the dose survives. */
    const val GAP_FULL_MGDL = 80.0

    /** Never below this: the gate reduces a dose, it does not refuse one. */
    const val MIN_FACTOR = 0.35

    data class Verdict(
        val active: Boolean,
        /** Multiplier to apply to the bolus, or null when this tick is not damped. */
        val factor: Double?,
        val reason: String,
    ) {

        companion object {

            val INACTIVE = Verdict(active = false, factor = null, reason = "idle")
        }
    }

    /**
     * How much of the dose survives for a given gap. Kept apart so it can be unit tested.
     *
     * @param gapMgdl target minus the insulin-only projection. Negative means the projection is
     *   above target, which never damps.
     */
    fun factorForGap(gapMgdl: Double): Double {
        if (gapMgdl <= GAP_FREE_MGDL) return 1.0
        if (gapMgdl >= GAP_FULL_MGDL) return MIN_FACTOR
        val progress = (gapMgdl - GAP_FREE_MGDL) / (GAP_FULL_MGDL - GAP_FREE_MGDL)
        return 1.0 - progress * (1.0 - MIN_FACTOR)
    }

    /**
     * Looks at one tick. Writes nothing, so the verdict can be exported on every tick even when the
     * feature is off, and the effect measured before it is turned on.
     *
     * @param iobPredBgMgdl terminal of the insulin-only prediction curve, null when unavailable.
     * @param targetBgMgdl the target this tick is aiming at.
     */
    fun evaluate(iobPredBgMgdl: Double?, targetBgMgdl: Double): Verdict {
        if (iobPredBgMgdl == null || !iobPredBgMgdl.isFinite()) return Verdict.INACTIVE
        if (!targetBgMgdl.isFinite() || targetBgMgdl <= 0.0) return Verdict.INACTIVE
        val gap = targetBgMgdl - iobPredBgMgdl
        val factor = factorForGap(gap)
        if (factor >= 1.0) return Verdict.INACTIVE
        return Verdict(
            active = true,
            factor = factor,
            reason = "iobPred=${iobPredBgMgdl.toInt()} vs target=${targetBgMgdl.toInt()} " +
                "gap=${gap.toInt()} ×${"%.2f".format(factor)}",
        )
    }
}
