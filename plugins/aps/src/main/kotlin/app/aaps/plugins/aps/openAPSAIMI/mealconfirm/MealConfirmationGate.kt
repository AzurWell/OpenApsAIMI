package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey

/**
 * User-arbitrated meal interpretation.
 *
 * AIMI cannot separate a fast endogenous morning rise from an undeclared meal on signals alone:
 * [app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase] is exclusive, so once the tick is
 * classified `MEAL_UNDECLARED` the endogenous drive collapses to 0 and the built-in
 * `falseMealSuppression` (`endogenousDrive > mealSignal + 0.15`) can no longer fire.
 *
 * This gate lets the user answer the question the algorithm cannot: when AIMI starts treating a
 * carb-free rise as a meal, it raises a prompt; if the user says "I am not eating", the meal
 * interpretation is suppressed for [suppressionWindowMinutes].
 *
 * Design rule: **silence keeps today's behaviour**. Only an explicit user action changes dosing,
 * so a real meal is never under-dosed because the prompt went unanswered.
 *
 * State is persisted through [AimiLongKey] so a plugin restart mid-window keeps the user's answer.
 */
object MealConfirmationGate {

    /** How long one "I am not eating" answer holds. */
    const val SUPPRESSION_WINDOW_MIN = 120L

    /** A prompt is not re-raised more often than this, to avoid nagging during one long rise. */
    const val PROMPT_COOLDOWN_MIN = 45L

    /**
     * A manual bolus this recent is treated as an implicit "yes, I am eating": the user doses
     * 2-3 U by hand at the start of a meal and then lets the loop run. No prompt is raised then,
     * so the question only ever appears on a rise the user did NOT bolus for.
     */
    const val MANUAL_BOLUS_LOOKBACK_MIN = 45L

    /** Below this, a bolus is a correction/SMB artefact rather than a meal bolus. */
    const val MANUAL_BOLUS_MEAL_THRESHOLD_U = 0.8

    /**
     * How long an explicit "I am eating" keeps the prompt quiet.
     *
     * A long meal (aperitif then dinner over three hours) keeps the rise going well past
     * [MANUAL_BOLUS_LOOKBACK_MIN] after the last manual bolus, so the plain
     * [PROMPT_COOLDOWN_MIN] would ask again every 45 min through the whole meal. Answering
     * "I am eating" once should settle it for the meal.
     */
    const val MEAL_CONFIRMED_QUIET_MIN = 180L

    private const val MIN_MS = 60_000L

    /** True while the user's "not eating" answer is still in force. */
    fun isMealSuppressedByUser(preferences: Preferences, now: Long): Boolean =
        now < preferences.get(AimiLongKey.MealDeniedUntil)

    /** Remaining minutes of an active suppression, 0 when inactive. */
    fun remainingSuppressionMinutes(preferences: Preferences, now: Long): Long {
        val until = preferences.get(AimiLongKey.MealDeniedUntil)
        return if (now < until) (until - now + MIN_MS - 1) / MIN_MS else 0L
    }

    /**
     * Cancels an active "I am not eating" as soon as real meal evidence shows up.
     *
     * The answer is a statement about the next couple of hours, and the user can be wrong: saying
     * "not eating" at 10:30 must not under-dose a real meal started at 11:30. Hard meal evidence
     * therefore overrides the earlier answer immediately, without asking again:
     *  - a manual bolus (the user's own meal dose), or
     *  - declared carbs.
     *
     * Called once per tick before anything reads [isMealSuppressedByUser], so the release lands on
     * the same tick as the bolus rather than one cycle later.
     *
     * @return true when an active suppression was cleared by this call.
     */
    fun clearIfMealEvidence(
        preferences: Preferences,
        now: Long,
        recentManualBolusU: Double,
        declaredCobG: Double,
    ): Boolean {
        if (!isMealSuppressedByUser(preferences, now)) return false
        val evidence = recentManualBolusU >= MANUAL_BOLUS_MEAL_THRESHOLD_U || declaredCobG > 0.0
        if (!evidence) return false
        preferences.put(AimiLongKey.MealDeniedUntil, 0L)
        // The user is eating after all: stay quiet for the meal instead of asking again at once.
        preferences.put(AimiLongKey.MealPromptQuietUntil, now + MEAL_CONFIRMED_QUIET_MIN * MIN_MS)
        return true
    }

    /**
     * Records the user's answer.
     *
     * @param eating true = "yes I am eating" (clears any suppression and lets AIMI proceed),
     *   false = "no I am not eating" (suppresses meal interpretation for the window).
     */
    fun recordUserAnswer(preferences: Preferences, now: Long, eating: Boolean) {
        preferences.put(
            AimiLongKey.MealDeniedUntil,
            if (eating) 0L else now + SUPPRESSION_WINDOW_MIN * MIN_MS,
        )
        // "I am eating" also buys quiet for the length of a long meal, not just one cooldown.
        preferences.put(
            AimiLongKey.MealPromptQuietUntil,
            if (eating) now + MEAL_CONFIRMED_QUIET_MIN * MIN_MS else 0L,
        )
        preferences.put(AimiLongKey.MealPromptLastShown, now)
    }

    /**
     * Whether a prompt should be raised for this tick.
     *
     * @param mealInterpretationActive AIMI is currently dosing this rise as a meal.
     * @param declaredCobG declared/advisor carbs — any real carbs mean there is nothing to ask.
     * @param recentManualBolusU manual insulin over the last [MANUAL_BOLUS_LOOKBACK_MIN] minutes.
     */
    fun shouldPrompt(
        preferences: Preferences,
        now: Long,
        mealInterpretationActive: Boolean,
        declaredCobG: Double,
        recentManualBolusU: Double = 0.0,
    ): Boolean {
        if (!mealInterpretationActive) return false
        if (declaredCobG > 0.0) return false
        // Implicit "I am eating": the user already bolused by hand for this rise.
        if (recentManualBolusU >= MANUAL_BOLUS_MEAL_THRESHOLD_U) return false
        // The user already said they are eating; stay quiet for the rest of the meal.
        if (now < preferences.get(AimiLongKey.MealPromptQuietUntil)) return false
        if (isMealSuppressedByUser(preferences, now)) return false
        val lastShown = preferences.get(AimiLongKey.MealPromptLastShown)
        return now - lastShown >= PROMPT_COOLDOWN_MIN * MIN_MS
    }

    /** Marks a prompt as raised, so the cooldown starts even if the user never answers. */
    fun markPromptShown(preferences: Preferences, now: Long) {
        preferences.put(AimiLongKey.MealPromptLastShown, now)
    }

    /** Compact state for `rT.reason` / logs. */
    fun statusLine(preferences: Preferences, now: Long): String =
        if (isMealSuppressedByUser(preferences, now)) {
            "🙅 MEAL_DENIED_BY_USER ${remainingSuppressionMinutes(preferences, now)}min left"
        } else if (now < preferences.get(AimiLongKey.MealPromptQuietUntil)) {
            "🍽️ meal-confirm: meal confirmed by user, quiet"
        } else {
            "🍽️ meal-confirm: idle"
        }
}
