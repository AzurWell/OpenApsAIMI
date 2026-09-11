package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey
import java.time.Instant
import java.time.ZoneId

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

    /**
     * Below this, a manual bolus is not read as a meal.
     *
     * SMBs are already excluded by type ([app.aaps.core.data.model.BS.Type.NORMAL] only), so the
     * only thing this threshold keeps out is a micro-correction typed by hand. The user this was
     * written for gives round doses of 2-3 U and almost never eats without carbs, so 0.8 U was
     * rejecting the small deliberate bolus they use to say "I am starting a meal". Lowered to 0.5 U
     * on 2026-09-11 at their request.
     */
    const val MANUAL_BOLUS_MEAL_THRESHOLD_U = 0.5

    /**
     * How long an explicit "I am eating" keeps the prompt quiet.
     *
     * A long meal (aperitif then dinner over three hours) keeps the rise going well past
     * [MANUAL_BOLUS_LOOKBACK_MIN] after the last manual bolus, so the plain
     * [PROMPT_COOLDOWN_MIN] would ask again every 45 min through the whole meal. Answering
     * "I am eating" once should settle it for the meal.
     */
    const val MEAL_CONFIRMED_QUIET_MIN = 180L

    /**
     * Second trigger, measured on 2026-09-11.
     *
     * The first trigger asks the phase engine whether this tick is a meal, and that morning it said
     * no: glucose went 99 → 145 mg/dL with no carbs between 08:17 and 09:08, and the phase stayed
     * `STRESS_CORTISOL` then `MALE_CIRCADIAN_HORMONAL`, never `MEAL_UNDECLARED`. AIMI still dosed it
     * like a meal — 1.05 U of SMB in 24 minutes, 0.75 U of it at the top of the rise — and glucose
     * fell to 72 mg/dL with 1.77 U still active. No question was ever asked, because the gate was
     * reading the label instead of the behaviour.
     *
     * So a rise with no carbs that AIMI is treating to this much insulin raises the prompt on its
     * own, whatever the phase engine calls it.
     */
    const val AGGRESSIVE_SMB_WINDOW_MIN = 30L
    const val AGGRESSIVE_SMB_THRESHOLD_U = 0.8

    /** A rise, not a plateau: a flat hyper being corrected is not a meal question. */
    const val AGGRESSIVE_MIN_DELTA_MGDL = 2.0

    /**
     * Hours when the prompt stays down (local time, start inclusive, end exclusive).
     *
     * Three prompts fired during the night of 2026-09-11 and all three expired unanswered — the
     * user was asleep, so they only burned the cooldown. The window starts at 02:00 and not at
     * 23:00 on purpose: a late meal after drinks is a real case, and that is exactly when the
     * question is worth asking.
     */
    const val QUIET_HOUR_START = 2
    const val QUIET_HOUR_END = 7

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
     * A meal declared through [MealKnownGate] — the button or the user's own prebolus — overrides an
     * earlier "I am not eating", exactly like [clearIfMealEvidence] does for a bolus.
     *
     * Without this the two declarations contradict each other and the older one wins: pressing the
     * button would open a meal window while the undeclared-carb estimator stayed gated.
     *
     * @return true when an active suppression was cleared by this call.
     */
    fun clearDenialForDeclaredMeal(preferences: Preferences, now: Long): Boolean {
        if (!isMealSuppressedByUser(preferences, now)) return false
        preferences.put(AimiLongKey.MealDeniedUntil, 0L)
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
     * AIMI is pushing meal-sized insulin into a carb-free rise, whatever phase it believes it is in.
     *
     * @param smbLast30MinU SMB delivered over the last [AGGRESSIVE_SMB_WINDOW_MIN] minutes.
     * @param deltaMgdl short delta now.
     */
    fun isAggressiveMealDosing(smbLast30MinU: Double, deltaMgdl: Double): Boolean =
        smbLast30MinU >= AGGRESSIVE_SMB_THRESHOLD_U && deltaMgdl >= AGGRESSIVE_MIN_DELTA_MGDL

    /** True inside the night window where the prompt stays down. */
    fun isQuietHour(now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        return hour >= QUIET_HOUR_START && hour < QUIET_HOUR_END
    }

    /**
     * Whether a prompt should be raised for this tick.
     *
     * @param mealInterpretationActive the phase engine calls this tick a meal.
     * @param aggressiveMealDosing AIMI is dosing the rise like a meal — see [isAggressiveMealDosing].
     *   Either trigger is enough; the second exists because the first missed the case of
     *   2026-09-11 entirely.
     * @param declaredCobG declared/advisor carbs — any real carbs mean there is nothing to ask.
     * @param recentManualBolusU manual insulin over the last [MANUAL_BOLUS_LOOKBACK_MIN] minutes.
     * @param mealAlreadyKnown a meal window is already open (MealKnownGate) — the user has declared
     *   this meal with their own prebolus or with the button, so there is nothing to ask.
     */
    fun shouldPrompt(
        preferences: Preferences,
        now: Long,
        mealInterpretationActive: Boolean,
        declaredCobG: Double,
        recentManualBolusU: Double = 0.0,
        aggressiveMealDosing: Boolean = false,
        mealAlreadyKnown: Boolean = false,
    ): Boolean {
        if (!mealInterpretationActive && !aggressiveMealDosing) return false
        if (declaredCobG > 0.0) return false
        // The user already declared this meal; asking again is pure noise.
        if (mealAlreadyKnown) return false
        // Implicit "I am eating": the user already bolused by hand for this rise.
        if (recentManualBolusU >= MANUAL_BOLUS_MEAL_THRESHOLD_U) return false
        // Asleep: the prompt would only expire unanswered and burn the cooldown.
        if (isQuietHour(now)) return false
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
