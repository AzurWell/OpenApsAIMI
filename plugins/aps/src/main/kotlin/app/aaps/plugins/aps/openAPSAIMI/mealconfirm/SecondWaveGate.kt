package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import app.aaps.core.keys.interfaces.Preferences

/**
 * What to do with a rise that comes after the first wave of a declared meal has come down.
 *
 * A second wave is recognised in two ways: the first wave peaked and came down
 * ([firstWaveCameDown]), or, late in the meal, glucose climbed well above its late trough whatever
 * the first wave looked like ([lateRise]).
 *
 * Only the insulin on board is capped ([IOB_CAP_U], see [iobCapped]): below the cap a second wave
 * is dosed as usual from its first tick. The verdict also reports where the rise stands, for the
 * logs.
 *
 * Hours after a meal the loop has no memory of it: carbs read as zero, so every rise is
 * rediscovered as a brand new meal and dosed like one, on top of the insulin the first wave still
 * has on board.
 *
 * ## What it leaves alone
 * - **The first wave.** Nothing happens until glucose has climbed [FIRST_WAVE_MIN_MGDL] above where
 *   it was when the meal was declared, then come down [DESCENT_MIN_MGDL] from that peak. A prebolus
 *   dip before the carbs arrive is not a descent: there was no peak yet.
 * - **A new declaration.** A new meal note, or a new meal bolus, restarts the meal and its glucose
 *   history.
 * - **A rise that is not read as a meal anyway.** Once the declaration is over the loop no longer
 *   doses a rise like a meal, so there is nothing of the meal treatment left to cap.
 */
object SecondWaveGate {

    /** Before this, the rise is still the first wave, whatever its shape. */
    const val MIN_ELAPSED_MIN = 60L

    /** Past this, the meal's clock is no longer relevant to the rise. */
    const val MAX_ELAPSED_MIN = MealKnownGate.MEAL_TAIL_MIN

    /** The first wave counts as having happened once glucose climbed this far above the start. */
    const val FIRST_WAVE_MIN_MGDL = 20.0

    /** The first wave counts as treated once glucose came down this far from its peak. */
    const val DESCENT_MIN_MGDL = 30.0

    /** Report only: the rise above the trough is written against this in the reason. */
    private const val PROOF_MGDL = 35.0

    /** Report only: from this glucose up the reason says "above". */
    private const val HOLD_CEILING_MGDL = 200.0

    /**
     * On a second wave, nothing above the scheduled basal and no SMB once this much insulin is on
     * board, for every declared meal and whatever the glucose. Across the user's declared meals, the
     * insulin on board during the second wave was the only measure that told the second waves
     * followed by a low from the others on meals left out of the fit; the speed of the rise and
     * stopping at its inflection did not. Below the cap the second wave is dosed as usual from its
     * first tick: holding a hesitating rise only delayed the insulin, which then went in all at once
     * past 200 and stacked. A cap at 5 U held back too many second waves that needed their insulin.
     */
    const val IOB_CAP_U = 6.0

    /**
     * From this long after the declaration, a rise needs no first wave before it to be a second
     * wave: see [lateRise].
     */
    const val LATE_MIN_ELAPSED_MIN = 120L

    /** A late rise is a second wave once glucose stands this far above its late trough. */
    const val LATE_RISE_MGDL = 30.0

    private const val MIN_MS = 60_000L

    data class Verdict(
        /** Short reason for `rT.reason` and the logs. */
        val reason: String,
        /** True on any tick of a second wave: the first wave of the meal has come down, or a late rise was seen. */
        val secondWave: Boolean = false,
    ) {

        companion object {

            val INACTIVE = Verdict(reason = "idle")
        }
    }

    /** True once the first wave of the meal has peaked and come down. */
    fun firstWaveCameDown(startMgdl: Double, peakMgdl: Double, troughMgdl: Double): Boolean =
        peakMgdl - startMgdl >= FIRST_WAVE_MIN_MGDL && peakMgdl - troughMgdl >= DESCENT_MIN_MGDL

    /**
     * The second way to be a second wave, whatever the first wave looked like.
     *
     * [firstWaveCameDown] needs a first wave of [FIRST_WAVE_MIN_MGDL] above the glucose at the
     * declaration. A meal declared while glucose is already going up has most of its first wave
     * before the declaration: what is left of it above the start is too small to count, the shape is
     * never read as "came down", and the rise that follows two hours later is dosed as a brand new
     * first wave on top of the insulin already on board.
     *
     * So from [LATE_MIN_ELAPSED_MIN] after the declaration, glucose standing [LATE_RISE_MGDL] or more
     * above its lowest value since [MIN_ELAPSED_MIN] is a second wave. A smaller rise is left alone.
     *
     * @param lateTroughMgdl lowest glucose from [MIN_ELAPSED_MIN] after the declaration, 0 when unknown.
     */
    fun lateRise(elapsedMin: Long, bgMgdl: Double, lateTroughMgdl: Double): Boolean =
        elapsedMin >= LATE_MIN_ELAPSED_MIN && lateTroughMgdl > 0.0 && bgMgdl - lateTroughMgdl >= LATE_RISE_MGDL

    /** True when the second wave must stop adding: see [IOB_CAP_U]. */
    fun iobCapped(verdict: Verdict, iobU: Double): Boolean =
        verdict.secondWave && iobU.isFinite() && iobU >= IOB_CAP_U

    /**
     * Looks at one tick. This function writes nothing.
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
        if (!firstWaveCameDown(shape.startMgdl, shape.peakMgdl, shape.troughMgdl)) {
            // No first wave to speak of above the declaration: a late rise is a second wave all the
            // same, and stays one until the next declaration. Only [IOB_CAP_U] applies to it.
            // Not for a big meal: a big (often fatty) meal can bring its first wave late, and
            // BigMealGate keeps its own reading of the waves.
            if (!shape.lateWave || elapsedMin < LATE_MIN_ELAPSED_MIN) return Verdict.INACTIVE
            if (MealKnownGate.isBigMeal(preferences)) return Verdict.INACTIVE
            val riseMgdl = (bgMgdl - shape.lateTroughMgdl).toInt()
            return Verdict(
                reason = "2nd wave late t+${elapsedMin}min ${if (riseMgdl >= 0) "+" else ""}$riseMgdl/${LATE_RISE_MGDL.toInt()} " +
                    "over trough ${shape.lateTroughMgdl.toInt()} since t+${MIN_ELAPSED_MIN}min",
                secondWave = true,
            )
        }

        val rise = bgMgdl - shape.troughMgdl
        val where = "t+${elapsedMin}min +${rise.toInt()}/${PROOF_MGDL.toInt()} over trough " +
            "${shape.troughMgdl.toInt()} (peak ${shape.peakMgdl.toInt()})"
        // Same three wordings as when the rise was held until proven: only the text is left.
        val state = when {
            bgMgdl >= HOLD_CEILING_MGDL -> "above ${HOLD_CEILING_MGDL.toInt()} "
            rise < PROOF_MGDL -> ""
            else -> "proven "
        }
        return Verdict(reason = "2nd wave $state$where", secondWave = true)
    }
}
