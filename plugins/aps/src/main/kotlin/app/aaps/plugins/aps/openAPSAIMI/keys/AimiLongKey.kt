package app.aaps.plugins.aps.openAPSAIMI.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class AimiLongKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    // 🤰 Pregnancy Due Date (Timestamp MS)
    PregnancyDueDate("oa_aimi_pregnancy_due_date_ms", 0L),

    // 💉 Last Manual Prebolus Timestamp (Timestamp MS)
    LastPrebolusTime("oa_aimi_last_prebolus_time_ms", 0L),

    // 🍱 Legacy meal prebolus delivery tracking (carry-forward retry)
    LastLegacyPrebolusTime("oa_aimi_last_legacy_prebolus_time_ms", 0L),
    PendingLegacyPrebolusUnitMilli("oa_aimi_pending_legacy_prebolus_unit_milli", 0L),
    PendingLegacyPrebolusExpiry("oa_aimi_pending_legacy_prebolus_expiry_ms", 0L),

    // 🙅 User-arbitrated meal interpretation (MealConfirmationGate)
    /** Meal interpretation suppressed by the user until this wall-clock ms. 0 = not suppressed. */
    MealDeniedUntil("oa_aimi_meal_denied_until_ms", 0L),
    /** Last time the "are you eating?" prompt was raised, for the cooldown. */
    MealPromptLastShown("oa_aimi_meal_prompt_last_shown_ms", 0L),
    /** User confirmed a meal: stay quiet until this wall-clock ms (covers a long meal). */
    MealPromptQuietUntil("oa_aimi_meal_prompt_quiet_until_ms", 0L),

    // 🍽️ Meal knowledge, decoupled from the meal modes (MealKnownGate)
    /** A meal is considered in progress until this wall-clock ms. 0 = no meal known. */
    MealKnownUntil("oa_aimi_meal_known_until_ms", 0L),
    /** When the current meal window was opened, for the second-wave elapsed time. */
    MealKnownArmedAt("oa_aimi_meal_known_armed_at_ms", 0L),
    /** Timestamp of the last manual bolus already examined, so one bolus arms at most once. */
    MealKnownLastBolusMs("oa_aimi_meal_known_last_bolus_ms", 0L),
    /** Timestamp of the last "eating" note already used, so one note arms at most once. */
    MealKnownLastNoteMs("oa_aimi_meal_known_last_note_ms", 0L),
    /** Lowest glucose (mg/dL) seen since the meal was declared. 0 = nothing recorded yet. */
    MealKnownBgMinMgdl("oa_aimi_meal_known_bg_min_mgdl", 0L)
}
