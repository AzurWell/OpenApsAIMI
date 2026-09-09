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
        onAnswer: ((eating: Boolean) -> Unit)? = null,
    ): Boolean {
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
        )
        if (!allowed) return false

        val bgText = String.format(Locale.US, "%.0f", bgMgdl)
        notificationManager.post(
            id = NotificationId.AIMI_MEAL_CONFIRMATION,
            text = context.getString(R.string.aimi_meal_confirm_notification, bgText),
            level = NotificationLevel.NORMAL,
            validMinutes = VALID_MINUTES,
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
        MealConfirmationGate.markPromptShown(preferences, now)
        return true
    }

    private fun answer(
        notificationManager: NotificationManager,
        preferences: Preferences,
        eating: Boolean,
        onAnswer: ((Boolean) -> Unit)?,
    ) {
        MealConfirmationGate.recordUserAnswer(preferences, System.currentTimeMillis(), eating)
        notificationManager.dismiss(NotificationId.AIMI_MEAL_CONFIRMATION)
        onAnswer?.invoke(eating)
    }
}
