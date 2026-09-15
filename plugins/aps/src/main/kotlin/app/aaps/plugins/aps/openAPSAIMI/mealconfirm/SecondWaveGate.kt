package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import app.aaps.core.keys.interfaces.Preferences

/**
 * What to do with a rise that happens hours after a declared meal.
 *
 * There are two of them, they look alike on a single tick, and they need opposite treatment.
 *
 * **The tail.** The meal peaked, came down, and hours later the last of it arrives. Measured on
 * 2026-09-14: half a baguette at 11:56, peak 142, trough 72 at 13:22, then back up — and AIMI put
 * **6.65 U into the last three hours** because `COB` reads 0 and every tick rediscovers the rise as
 * if it were a new meal. Total 12.10 U for ~40 g of bread, and the hypo that followed was avoided
 * only because the user ate 10 g of sugar at 18:30. This is the case to damp.
 *
 * **The fat plateau.** Bread with cheese, or pizza: glucose climbs and *stays* up for hours, and
 * the fat installs real insulin resistance on top. In the user's words: "avant le pain j'étais à
 * 15 unités, 5 au début puis après je monte à 250 durant 4h avec des 0,8 tout le temps, ça installe
 * une résistance galère". That plateau genuinely needs the insulin. Damping it would hold them at
 * 250 for longer, which is the opposite of the point.
 *
 * The two are told apart by **the trough**, the same test [MealKnownGate.bgMinSinceArm] already
 * records: a tail has come back near normal before rising again, a plateau never comes down at all.
 *
 * Reduction only, bolus channel only — the temporary basal is left alone, so a real rise can still
 * be held without stacking boluses on top of it.
 *
 * ## Why this is a damper and not a budget
 *
 * The first version of this gate capped each SMB at 0.4 U and gave the whole second rise a 3 U
 * budget. Replayed against the pasta lunch of 2026-09-11 — five separate rises over seven hours,
 * 7.9 U, no hypo — that budget would have run out at t+2h20 and closed the bolus channel for waves
 * three, four and five, on insulin the meal really needed. A fixed budget cannot know how much is
 * left to come. Scaling with the age of the meal can.
 */
object SecondWaveGate {

    /**
     * Before this much time has passed, the rise is still the first wave of the meal and must be
     * dosed normally. Two hours also puts the gate after the fast carb peak.
     */
    const val MIN_ELAPSED_MIN = 120L

    /** Past this, the meal's clock is no longer relevant to the rise. */
    const val MAX_ELAPSED_MIN = MealKnownGate.MEAL_TAIL_MIN

    /** Under this, nothing is stacked yet, so there is nothing to protect against. */
    const val MIN_IOB_U = 2.5

    /** A real rise, not noise. */
    const val MIN_DELTA_MGDL = 2.0

    /** Under this there is no rise worth damping, whatever the delta says. */
    const val MIN_BG_MGDL = 140.0

    /**
     * The shape test. A tail has come back near normal since the meal and climbed away from that
     * low again; a fat plateau never comes down.
     */
    const val TROUGH_MAX_MGDL = 140.0
    const val REBOUND_MIN_MGDL = 30.0

    /**
     * How much of the proposed bolus survives, by meal age.
     *
     * At t+2h the meal can still have most of itself ahead, so nothing is taken. By t+5h what is
     * left is a tail of a few grams, and the loop is dosing it as if a whole meal were coming.
     * Read against 2026-09-14: the 6.65 U given between t+2h30 and t+5h30 become roughly 2.5 U.
     *
     * Never below [MIN_FACTOR] — this reduces a dose, it never refuses one outright. The loop keeps
     * its own safety guards underneath.
     */
    const val MIN_FACTOR = 0.35

    private const val MIN_MS = 60_000L

    data class Verdict(
        /** True when this tick is inside a damped meal tail. */
        val active: Boolean,
        /** Multiplier to apply to the bolus, or null when the gate does not limit this tick. */
        val factor: Double?,
        /** Short reason for `rT.reason` and the logs. */
        val reason: String,
    ) {

        companion object {

            val INACTIVE = Verdict(active = false, factor = null, reason = "idle")
        }
    }

    /**
     * The shape that tells a tail from a meal that never came down. Kept apart so it can be unit
     * tested. See [TROUGH_MAX_MGDL].
     */
    fun looksLikeSecondRise(bgMgdl: Double, troughMgdl: Double): Boolean =
        troughMgdl <= TROUGH_MAX_MGDL && bgMgdl - troughMgdl >= REBOUND_MIN_MGDL

    /**
     * How much of the bolus survives at this meal age: 1.0 up to [MIN_ELAPSED_MIN], then down to
     * [MIN_FACTOR] at [MAX_ELAPSED_MIN], linearly. Kept apart so it can be unit tested.
     */
    fun dampingFactor(elapsedMin: Long): Double {
        if (elapsedMin <= MIN_ELAPSED_MIN) return 1.0
        if (elapsedMin >= MAX_ELAPSED_MIN) return MIN_FACTOR
        val span = (MAX_ELAPSED_MIN - MIN_ELAPSED_MIN).toDouble()
        val progress = (elapsedMin - MIN_ELAPSED_MIN).toDouble() / span
        return 1.0 - progress * (1.0 - MIN_FACTOR)
    }

    /**
     * Looks at one tick. This function writes nothing, so the verdict can be worked out and
     * exported on every tick even when the feature is off. That way the effect can be measured
     * before it is turned on, the same way upstream ships `DescentRedoseGuard`.
     *
     * @param bgMinSinceArmMgdl lowest glucose since the meal was declared — see [TROUGH_MAX_MGDL].
     */
    fun evaluate(
        preferences: Preferences,
        now: Long,
        iobU: Double,
        declaredCobG: Double,
        bgMgdl: Double,
        deltaMgdl: Double,
        bgMinSinceArmMgdl: Double?,
    ): Verdict {
        if (declaredCobG > 0.0) return Verdict.INACTIVE
        val elapsedMs = MealKnownGate.msSinceArm(preferences, now) ?: return Verdict.INACTIVE
        val elapsedMin = elapsedMs / MIN_MS
        if (elapsedMin < MIN_ELAPSED_MIN || elapsedMin > MAX_ELAPSED_MIN) return Verdict.INACTIVE
        if (iobU < MIN_IOB_U) return Verdict.INACTIVE
        if (bgMgdl < MIN_BG_MGDL) return Verdict.INACTIVE
        if (deltaMgdl < MIN_DELTA_MGDL) return Verdict.INACTIVE
        // Shape: a tail, not a meal that never came down. A fat plateau is left alone on purpose.
        val trough = bgMinSinceArmMgdl ?: return Verdict.INACTIVE
        if (!looksLikeSecondRise(bgMgdl, trough)) {
            return Verdict(active = false, factor = null, reason = "plateau t+${elapsedMin}min trough=${trough.toInt()} — left alone")
        }

        val factor = dampingFactor(elapsedMin)
        return Verdict(
            active = true,
            factor = factor,
            reason = "tail t+${elapsedMin}min iob=${"%.1f".format(iobU)}U " +
                "trough=${trough.toInt()} ×${"%.2f".format(factor)}",
        )
    }
}
