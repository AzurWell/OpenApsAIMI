package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import java.time.Instant
import java.time.ZoneId

/**
 * A meal must be declared: a rise with no declaration is not a meal.
 *
 * The loop cannot tell a fast endogenous rise from an undeclared meal on its signals alone. The
 * user declares every meal through [MealKnownGate] (a manual bolus or a meal note), so a rise with
 * no declaration behind it is not read as a meal: no meal phase, no meal priority, and the basal is
 * held by [deniedBasalCeilingUph].
 */
object MealConfirmationGate {

    /**
     * From this glucose up, the basal is no longer held at the profile rate when no meal is declared.
     *
     * Below it the rise is not treated as a meal and the basal stays at the profile rate. From it
     * up it is a real high that must still be corrected, only without the meal bypass.
     */
    const val DENIED_HYPER_LINE_MGDL = 180.0

    /**
     * Highest temp basal allowed while [isMealInterpretationBlocked] is true.
     *
     * Blocking the meal reading on the SMB channel alone left the basal with its meal treatment:
     * several paths (Autodrive V3, meal-mode basal boost, trajectory bridge, the dynamic basal
     * controller) each pushed the max basal on their own.
     *
     * - under [DENIED_ELEVATED_LINE_MGDL]: the profile basal. The rise is not a meal, so no meal extra.
     * - from [DENIED_ELEVATED_LINE_MGDL] up to [DENIED_HYPER_LINE_MGDL]: the profile basal times
     *   [elevatedFactor], never above the normal limit. See [elevatedBasalFactor].
     * - from [DENIED_HYPER_LINE_MGDL] up: the normal limit AIMI uses when no meal is active
     *   ([normalMaxBasalUph]), so a real high is still corrected, just not with the meal bypass.
     *
     * This is a ceiling only. It never raises a rate and never touches a suspend.
     */
    fun deniedBasalCeilingUph(
        bgMgdl: Double,
        profileBasalUph: Double,
        normalMaxBasalUph: Double,
        elevatedFactor: Double = 1.0,
    ): Double {
        val highCeiling = maxOf(profileBasalUph, normalMaxBasalUph)
        return when {
            bgMgdl >= DENIED_HYPER_LINE_MGDL   -> highCeiling
            bgMgdl >= DENIED_ELEVATED_LINE_MGDL -> minOf(profileBasalUph * maxOf(1.0, elevatedFactor), highCeiling)
            else                               -> profileBasalUph
        }
    }

    /**
     * From this glucose up, and under [DENIED_HYPER_LINE_MGDL], the ceiling may sit above the
     * profile basal.
     *
     * With the ceiling at the profile rate all the way to 180, the loop holds a flat 145-170 but
     * does not bring it down: in a field log three nights out of six stayed on such a plateau while
     * the loop asked for about 2 U/h more on most ticks. Replayed on those ticks, a ceiling at 1.5
     * times the profile rate added 0 to 3.5 U per night, nothing on the nights already in range,
     * and none of these ticks was followed by a glucose under 80 in the next 3 hours.
     */
    const val DENIED_ELEVATED_LINE_MGDL = 140.0

    /**
     * True when a night high with no meal is stuck where it is: the ceiling of
     * [deniedBasalCeilingUph] is then lifted to the normal limit.
     *
     * With the ceiling on, a flat 160-180 night with little insulin on board can stay there for
     * hours: the loop asks for 3 to 4.5 U/h and gets the profile rate times the factor. In a field
     * log the ceiling took 3 to 8 U per night away above 140, and the night mean went up by about
     * 25 mg/dL. Replayed on those nights, lifting it only here gave about 3.4 U more per night.
     *
     * All of these must be true:
     *  - glucose at [STUCK_HIGH_MIN_MGDL] or more,
     *  - not falling: short and long average deltas both at [STUCK_HIGH_MIN_DELTA] or more,
     *  - insulin on board under [STUCK_HIGH_MAX_IOB_U],
     *  - local hour from [STUCK_HIGH_FROM_HOUR] to [STUCK_HIGH_UNTIL_HOUR] (excluded).
     */
    fun isStuckNightHigh(
        bgMgdl: Double,
        shortAvgDelta: Double,
        longAvgDelta: Double,
        iobU: Double,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (!bgMgdl.isFinite() || bgMgdl < STUCK_HIGH_MIN_MGDL) return false
        if (!(shortAvgDelta >= STUCK_HIGH_MIN_DELTA && longAvgDelta >= STUCK_HIGH_MIN_DELTA)) return false
        if (!iobU.isFinite() || iobU >= STUCK_HIGH_MAX_IOB_U) return false
        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        return hour >= STUCK_HIGH_FROM_HOUR || hour < STUCK_HIGH_UNTIL_HOUR
    }

    const val STUCK_HIGH_MIN_MGDL = 150.0
    /** mg/dL per 5 minutes: about -5 over 30 minutes. */
    const val STUCK_HIGH_MIN_DELTA = -1.0
    const val STUCK_HIGH_MAX_IOB_U = 2.5
    const val STUCK_HIGH_FROM_HOUR = 22
    const val STUCK_HIGH_UNTIL_HOUR = 9

    /** Used when [IntKey.OApsAIMINoMealElevatedBasalPct] holds a value outside its own range. */
    const val DEFAULT_ELEVATED_BASAL_FACTOR = 1.5

    /**
     * The factor in force between [DENIED_ELEVATED_LINE_MGDL] and [DENIED_HYPER_LINE_MGDL], from
     * [IntKey.OApsAIMINoMealElevatedBasalPct]. 100 % gives the profile basal all the way to 180.
     */
    fun elevatedBasalFactor(preferences: Preferences): Double {
        val key = IntKey.OApsAIMINoMealElevatedBasalPct
        val value = preferences.get(key)
        return if (value in key.min..key.max) value / 100.0 else DEFAULT_ELEVATED_BASAL_FACTOR
    }

    /**
     * True when a rise must not be read as a meal on this tick: no meal was declared (meal note or
     * manual bolus) in the last [MealKnownGate.declaredMealHorizonMin] minutes. Every meal reader
     * asks this function.
     */
    fun isMealInterpretationBlocked(preferences: Preferences, now: Long): Boolean =
        !MealKnownGate.isWithinDeclaredMeal(preferences, now)

    /** Compact state for `rT.reason` / logs. */
    fun statusLine(preferences: Preferences, now: Long): String =
        if (isMealInterpretationBlocked(preferences, now)) {
            "🚫 meal-confirm: not declared, not a meal"
        } else {
            "🍽️ meal-confirm: idle"
        }
}
