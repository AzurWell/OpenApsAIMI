package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import app.aaps.core.data.model.BS
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey

/**
 * "A meal is running" — kept apart from the meal modes.
 *
 * AIMI already has meal modes (`mealTime`, `lunchTime`, `dinnerTime`, and so on). They are the only
 * way to make `declaredMeal` true, but they also send a fixed prebolus, raise the SMB ceiling to
 * `maxSMBHB` and raise the basal cap to `meal_modes_max_basal`. Knowing about a meal and dosing
 * hard for one are tied together, so a user who wants the first must accept the second. Some users
 * then declare nothing at all, which is worse for everyone.
 *
 * This gate carries **only the knowledge**. It never delivers insulin, never changes a ceiling and
 * never raises a cap. Other code can ask "is a meal already running?" without paying for a mode.
 *
 * Three things arm it, and none of them asks the user for a number:
 *  - a **manual bolus** given in meal-like conditions: the user's own prebolus is the declaration;
 *  - a note holding "eating", written by an AAPS Automation: the light meal button;
 *  - an explicit "I am eating" answer, through [MealConfirmationGate].
 *
 * Same rule as [MealConfirmationGate]: **silence keeps today's behaviour**. A bolus that is not
 * clearly a meal bolus arms nothing, so a doubt can never under-dose a real meal.
 */
object MealKnownGate {

    /** How long a meal stays "known" after it was declared. */
    const val MEAL_WINDOW_MIN = 180L

    /** Under this, a bolus is a correction or an SMB, not a meal prebolus. */
    const val MANUAL_BOLUS_MEAL_THRESHOLD_U = MealConfirmationGate.MANUAL_BOLUS_MEAL_THRESHOLD_U

    /**
     * A prebolus is given near normal glucose, before the rise. A correction is given high.
     *
     * Checked against real boluses from the user this was written for: 5 U at 110 mg/dL before a
     * pastry (meal), 3 U at 112 mg/dL (meal), 7 U at 333 mg/dL while falling (correction), 5 U at
     * over 250 mg/dL at night against a hyper that would not come down (correction). This ceiling
     * separates the two groups cleanly.
     */
    const val MEAL_BG_CEILING_MGDL = 180.0

    /**
     * Falling fast means the bolus follows something that is already covered, so it does not open
     * a meal. Only used above [MEAL_FALLING_GUARD_BG_MGDL], because a prebolus taken while glucose
     * drifts down slowly near target is still a prebolus.
     */
    const val MEAL_FALLING_DELTA_MGDL = -3.0
    const val MEAL_FALLING_GUARD_BG_MGDL = 140.0

    private const val MIN_MS = 60_000L

    /** True while a declared meal is still seen as running. */
    fun isMealKnown(preferences: Preferences, now: Long): Boolean =
        now < preferences.get(AimiLongKey.MealKnownUntil)

    /** Minutes left in the meal window, 0 when no meal is known. */
    fun remainingMinutes(preferences: Preferences, now: Long): Long {
        val until = preferences.get(AimiLongKey.MealKnownUntil)
        return if (now < until) (until - now + MIN_MS - 1) / MIN_MS else 0L
    }

    /** Milliseconds since the window was opened, or null when no meal is known. */
    fun elapsedSinceArmMs(preferences: Preferences, now: Long): Long? {
        if (!isMealKnown(preferences, now)) return null
        val armedAt = preferences.get(AimiLongKey.MealKnownArmedAt)
        return if (armedAt > 0L) now - armedAt else null
    }

    /** Opens, or restarts, the meal window. A later meal always wins over an earlier one. */
    fun arm(preferences: Preferences, now: Long) {
        preferences.put(AimiLongKey.MealKnownUntil, now + MEAL_WINDOW_MIN * MIN_MS)
        preferences.put(AimiLongKey.MealKnownArmedAt, now)
    }

    /** Closes the window, for example when the user says they are not eating after all. */
    fun clear(preferences: Preferences) {
        preferences.put(AimiLongKey.MealKnownUntil, 0L)
        preferences.put(AimiLongKey.MealKnownArmedAt, 0L)
    }

    /**
     * Reads the user's own gesture. A manual bolus that is big enough, given in meal-like
     * conditions, opens the meal window with nothing else to do.
     *
     * Only [BS.Type.NORMAL] counts, because an SMB is the loop's own dose and says nothing about
     * what the user is doing. Each bolus is looked at once only
     * ([AimiLongKey.MealKnownLastBolusMs]), so the same dose cannot keep pushing the end of the
     * window further away on every tick.
     *
     * @param boluses recent boluses, in any order.
     * @param bgMgdl glucose now. The bolus is seen one or two ticks after it was given.
     * @param deltaMgdl short delta now, used only to reject a bolus that follows a fast fall.
     * @return the bolus that opened the window, or null when nothing was opened.
     */
    fun armFromManualBolus(
        preferences: Preferences,
        now: Long,
        boluses: List<BS>,
        bgMgdl: Double,
        deltaMgdl: Double,
    ): BS? {
        val lastSeen = preferences.get(AimiLongKey.MealKnownLastBolusMs)
        val candidate = boluses
            .filter { it.isValid && it.type == BS.Type.NORMAL }
            .filter { it.amount >= MANUAL_BOLUS_MEAL_THRESHOLD_U }
            .filter { it.timestamp > lastSeen }
            .maxByOrNull { it.timestamp }
            ?: return null

        // Mark it as seen either way, so a correction is not looked at again on every later tick.
        preferences.put(AimiLongKey.MealKnownLastBolusMs, candidate.timestamp)
        if (!looksLikeMealBolus(bgMgdl, deltaMgdl)) return null

        // The meal started when the bolus was given, not when the tick noticed it. Normally one or
        // two ticks apart; after an install or a restart the bolus can be much older, and anchoring
        // on it is what keeps the second-rise elapsed time honest instead of restarting the clock.
        arm(preferences, candidate.timestamp.coerceAtMost(now))
        return candidate
    }

    /**
     * The light meal button: an AAPS Automation writes a note holding "eating", and that note opens
     * the window. Nothing is dosed, no carb count is asked for.
     *
     * Unlike a manual bolus there is nothing to tell apart here — the user pressed the button on
     * purpose — so no glucose test is applied.
     *
     * @param noteStartMs timestamp of the active note, 0 when none is running.
     * @return true when this call opened the window.
     */
    fun armFromNote(preferences: Preferences, now: Long, noteStartMs: Long): Boolean {
        if (noteStartMs <= 0L) return false
        if (noteStartMs <= preferences.get(AimiLongKey.MealKnownLastNoteMs)) return false
        preferences.put(AimiLongKey.MealKnownLastNoteMs, noteStartMs)
        arm(preferences, now)
        return true
    }

    /** The test that tells a meal prebolus from a correction. Kept apart so it can be unit tested. */
    fun looksLikeMealBolus(bgMgdl: Double, deltaMgdl: Double): Boolean {
        if (bgMgdl > MEAL_BG_CEILING_MGDL) return false
        if (bgMgdl > MEAL_FALLING_GUARD_BG_MGDL && deltaMgdl <= MEAL_FALLING_DELTA_MGDL) return false
        return true
    }

    /** Short state line for `rT.reason` and the logs. */
    fun statusLine(preferences: Preferences, now: Long): String =
        if (isMealKnown(preferences, now)) {
            "🍽️ meal-known: ${remainingMinutes(preferences, now)}min left"
        } else {
            "🍽️ meal-known: idle"
        }
}
