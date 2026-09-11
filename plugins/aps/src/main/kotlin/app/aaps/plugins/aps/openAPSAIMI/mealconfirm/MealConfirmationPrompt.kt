package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import android.content.Context
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.R
import java.util.Locale

/**
 * Raises the "are you eating?" banner on the AAPS overview when AIMI starts dosing a carb-free
 * rise as a meal, and records the answer into [MealConfirmationGate].
 *
 * Dependencies are passed in rather than injected so this stays a plain object: the call site in
 * `DetermineBasalAIMI2` already holds a `NotificationManager`, a `Preferences` and a `Context`,
 * which keeps the upstream diff to a single call.
 */
object MealConfirmationPrompt {

    /** How long the banner stays before it expires on its own. */
    private const val VALID_MINUTES = 30

    /**
     * Posts the prompt when [MealConfirmationGate.shouldPrompt] allows it.
     *
     * @param recentBoluses boluses over the last [MealConfirmationGate.MANUAL_BOLUS_LOOKBACK_MIN]
     *   minutes; only manual ones ([BS.Type.NORMAL]) count as "I am eating".
     * @param exerciseLockoutActive sport mode or an activity context is running. A rise during
     *   effort is adrenaline, not food, and SMBs are already off, so the question would only be
     *   noise — and a "no" would burn a 120 min window that runs after the sport, when the user
     *   may really be eating.
     * @param smbLast30MinU SMB delivered over the last 30 minutes — the second trigger, for the
     *   rise AIMI doses like a meal without ever labelling it one.
     * @param deltaMgdl short delta now, used by that second trigger.
     * @param onAnswer optional hook invoked with the user's answer, for learning/telemetry.
     * @return true when a prompt was actually raised on this tick.
     */
    fun raiseIfNeeded(
        notificationManager: NotificationManager,
        preferences: Preferences,
        context: Context,
        now: Long,
        mealInterpretationActive: Boolean,
        declaredCobG: Double,
        bgMgdl: Double,
        recentBoluses: List<BS> = emptyList(),
        exerciseLockoutActive: Boolean = false,
        smbLast30MinU: Double = 0.0,
        deltaMgdl: Double = 0.0,
        onAnswer: ((eating: Boolean) -> Unit)? = null,
    ): Boolean {
        if (exerciseLockoutActive) return false
        // Manual meal boluses only: an SMB is the loop's own dose, not a statement of intent.
        val manualBolusU = recentBoluses
            .filter { it.isValid && it.type == BS.Type.NORMAL }
            .sumOf { it.amount }
        val allowed = MealConfirmationGate.shouldPrompt(
            preferences = preferences,
            now = now,
            mealInterpretationActive = mealInterpretationActive,
            declaredCobG = declaredCobG,
            recentManualBolusU = manualBolusU,
            aggressiveMealDosing = MealConfirmationGate.isAggressiveMealDosing(smbLast30MinU, deltaMgdl),
            mealAlreadyKnown = MealKnownGate.isMealKnown(preferences, now),
        )
        // Silence answered with noise: the banner went unseen and AIMI kept dosing.
        val escalate = MealConfirmationGate.shouldEscalate(
            preferences = preferences,
            now = now,
            smbLast30MinU = smbLast30MinU,
            deltaMgdl = deltaMgdl,
            declaredCobG = declaredCobG,
            recentManualBolusU = manualBolusU,
            mealAlreadyKnown = MealKnownGate.isMealKnown(preferences, now),
        )
        if (!allowed && !escalate) return false

        val bgText = String.format(Locale.US, "%.0f", bgMgdl)
        notificationManager.post(
            id = NotificationId.AIMI_MEAL_CONFIRMATION,
            text = context.getString(
                if (escalate) R.string.aimi_meal_confirm_notification_loud else R.string.aimi_meal_confirm_notification,
                bgText,
                String.format(Locale.US, "%.1f", smbLast30MinU),
            ),
            level = if (escalate) NotificationLevel.URGENT else NotificationLevel.NORMAL,
            validMinutes = VALID_MINUTES,
            soundRes = if (escalate) app.aaps.core.ui.R.raw.alarm else null,
            actions = listOf(
                NotificationAction(R.string.aimi_meal_confirm_action_not_eating) {
                    answer(notificationManager, preferences, eating = false, onAnswer = onAnswer)
                },
                NotificationAction(R.string.aimi_meal_confirm_action_eating) {
                    answer(notificationManager, preferences, eating = true, onAnswer = onAnswer)
                },
            ),
            // The banner is pointless once the user has already answered.
            validityCheck = { !MealConfirmationGate.isMealSuppressedByUser(preferences, System.currentTimeMillis()) },
        )
        if (escalate) MealConfirmationGate.markEscalated(preferences, now)
        else MealConfirmationGate.markPromptShown(preferences, now)
        return true
    }

    private fun answer(
        notificationManager: NotificationManager,
        preferences: Preferences,
        eating: Boolean,
        onAnswer: ((Boolean) -> Unit)?,
    ) {
        val now = System.currentTimeMillis()
        MealConfirmationGate.recordUserAnswer(preferences, now, eating)
        // "I am eating" is a declaration: open the meal window, the same one a manual prebolus
        // opens. "I am not eating" closes it, so an earlier meal cannot keep a second rise quiet.
        if (eating) MealKnownGate.arm(preferences, now) else MealKnownGate.clear(preferences)
        notificationManager.dismiss(NotificationId.AIMI_MEAL_CONFIRMATION)
        onAnswer?.invoke(eating)
    }
}
