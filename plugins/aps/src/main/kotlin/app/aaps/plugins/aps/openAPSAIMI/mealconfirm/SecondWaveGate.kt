package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import app.aaps.core.keys.interfaces.Preferences

/**
 * What to do with a rise that comes after the first wave of a declared meal has come down.
 *
 * Hours after a meal the loop has no memory of it: carbs read as zero, so every rise is
 * rediscovered as a brand new meal and dosed like one — rise exemption, 6.5 U/h, the largest SMB on
 * every tick — on top of the insulin the first wave still has on board.
 *
 * ## Dose on proof
 *
 * Measured on the user's meals of 2026-09-22 → 2026-09-27, tick by tick. Neither the insulin on
 * board (the real second waves ran at 6-7 U, the false ones at 0.4-3.3 U), nor minPredBG (39-51 in
 * both families), nor eventualBG (300-400 as soon as the rise exemption fires, in both) tells the
 * two apart. **The height of the rise above the trough does:**
 *
 * | meal | rise above trough | extra insulin sent | outcome |
 * |---|---|---|---|
 * | 2026-09-23 17:00 | +15 | 4.4 U | fell to 100 |
 * | 2026-09-24 16:00 | +16 | 1.9 U | **hypo 50** |
 * | 2026-09-27 14:20 | +38 | 5.1 U | **hypo 64** |
 * | 2026-09-22 lunch | +42 | — | real wave, 215 |
 * | 2026-09-24 evening, baguette | +73 | — | real wave, 241 |
 *
 * The loop sends everything on the first tick of the rise, before anyone can know which of the two
 * it is. So once the first wave has come down, nothing goes above the profile — no SMB, no basal
 * above the scheduled rate — until the rise has climbed [PROOF_MGDL] above its trough. Then the
 * loop doses as usual. The price is paid by the real waves: they start being dosed 20-40 minutes
 * later, and peak a little higher.
 *
 * The scheduled basal always keeps running. This holds back the meal treatment, never the basal.
 *
 * ## What it leaves alone
 * - **The first wave.** Nothing happens until glucose has climbed [FIRST_WAVE_MIN_MGDL] above where
 *   it was when the meal was declared, then come down [DESCENT_MIN_MGDL] from that peak. A prebolus
 *   dip before the carbs arrive is not a descent: there was no peak yet.
 * - **The fat plateau.** Bread with cheese sits at 200-250 for hours and needs its insulin. Above
 *   [HOLD_CEILING_MGDL] nothing is held.
 * - **A new declaration.** Pressing "eating" again, or a new meal bolus, restarts the meal and its
 *   glucose history: the user saying "more is coming" beats this gate.
 * - **A rise that is not read as a meal anyway.** Once the declaration is over (with "a meal must
 *   be declared"), or after a "not eating" answer, the loop no longer doses a rise like a meal, so
 *   there is nothing of the meal treatment left to hold. Holding there only blocked the small
 *   corrections of a flat 145-170 after a slow dinner, for two hours, with glucose never under 143.
 *
 * ## Why not the earlier damper
 * The previous version scaled the SMB down with the age of the meal, only above 140 mg/dL and with
 * 2.5 U on board, and left the basal alone. It would have missed the hypo of 2026-09-24 entirely
 * (125 mg/dL, 1.9 U on board), and the boosted basal is about 45 % of the extra insulin on these
 * rises.
 */
object SecondWaveGate {

    /** Before this, the rise is still the first wave, whatever its shape. */
    const val MIN_ELAPSED_MIN = 60L

    /** Past this, the meal's clock is no longer relevant to the rise. */
    const val MAX_ELAPSED_MIN = MealKnownGate.MEAL_TAIL_MIN

    /** The first wave counts as having happened once glucose climbed this far above the start. */
    const val FIRST_WAVE_MIN_MGDL = 20.0

    /**
     * The first wave counts as treated once glucose came down this far from its peak. The small
     * dip of 2026-09-22 lunch (199 → 173) stays under it, and that meal was a real wave.
     */
    const val DESCENT_MIN_MGDL = 30.0

    /** A second rise has proven itself once it climbed this far above its trough. */
    const val PROOF_MGDL = 35.0

    /** Above this, glucose is left to the loop: a high plateau needs its insulin. */
    const val HOLD_CEILING_MGDL = 200.0

    private const val MIN_MS = 60_000L

    data class Verdict(
        /** True when nothing above the scheduled basal may be given on this tick. */
        val hold: Boolean,
        /** Short reason for `rT.reason` and the logs. */
        val reason: String,
    ) {

        companion object {

            val INACTIVE = Verdict(hold = false, reason = "idle")
        }
    }

    /** True once the first wave of the meal has peaked and come down. */
    fun firstWaveCameDown(startMgdl: Double, peakMgdl: Double, troughMgdl: Double): Boolean =
        peakMgdl - startMgdl >= FIRST_WAVE_MIN_MGDL && peakMgdl - troughMgdl >= DESCENT_MIN_MGDL

    /**
     * The whole rule on plain numbers, kept apart so it can be unit tested.
     *
     * @param troughMgdl lowest glucose since the peak.
     */
    fun holds(bgMgdl: Double, startMgdl: Double, peakMgdl: Double, troughMgdl: Double): Boolean =
        firstWaveCameDown(startMgdl, peakMgdl, troughMgdl) &&
            bgMgdl - troughMgdl < PROOF_MGDL &&
            bgMgdl < HOLD_CEILING_MGDL

    /**
     * Looks at one tick. This function writes nothing, so the verdict can be worked out and
     * reported on every tick even when the feature is off. That way the effect can be measured
     * before it is turned on.
     *
     * @param mealBlocked true when the loop does not read a rise as a meal on this tick, see
     * [MealConfirmationGate.isMealInterpretationBlocked].
     */
    fun evaluate(
        preferences: Preferences,
        now: Long,
        declaredCobG: Double,
        bgMgdl: Double,
        mealBlocked: Boolean = false,
    ): Verdict {
        if (mealBlocked) return Verdict.INACTIVE
        if (declaredCobG > 0.0) return Verdict.INACTIVE
        if (!bgMgdl.isFinite() || bgMgdl <= 0.0) return Verdict.INACTIVE
        val elapsedMin = (MealKnownGate.msSinceArm(preferences, now) ?: return Verdict.INACTIVE) / MIN_MS
        if (elapsedMin < MIN_ELAPSED_MIN || elapsedMin > MAX_ELAPSED_MIN) return Verdict.INACTIVE
        val shape = MealKnownGate.bgShape(preferences) ?: return Verdict.INACTIVE
        if (!firstWaveCameDown(shape.startMgdl, shape.peakMgdl, shape.troughMgdl)) return Verdict.INACTIVE

        val rise = bgMgdl - shape.troughMgdl
        val where = "t+${elapsedMin}min +${rise.toInt()}/${PROOF_MGDL.toInt()} over trough " +
            "${shape.troughMgdl.toInt()} (peak ${shape.peakMgdl.toInt()})"
        return when {
            holds(bgMgdl, shape.startMgdl, shape.peakMgdl, shape.troughMgdl) ->
                Verdict(hold = true, reason = "2nd rise held $where")
            bgMgdl >= HOLD_CEILING_MGDL -> Verdict(hold = false, reason = "2nd rise above ${HOLD_CEILING_MGDL.toInt()}, left alone $where")
            else -> Verdict(hold = false, reason = "2nd rise proven $where")
        }
    }
}
