package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.ICfg
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.LongNonPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Locks the test that tells a meal prebolus from a correction.
 *
 * The four reference cases are real boluses from the user this gate was written for. They are the
 * whole reason the gate can read a gesture instead of asking a question, so they are pinned here
 * with their measured numbers.
 */
class MealKnownGateTest {

    // -----------------------------------------------------------------------------------------
    // Reference cases — real boluses
    // -----------------------------------------------------------------------------------------

    /** Morning pastry: 5 U given at 110 mg/dL, before the rise. */
    @Test
    fun pastryPrebolusIsAMeal() {
        assertThat(MealKnownGate.looksLikeMealBolus(bgMgdl = 110.0, deltaMgdl = 0.5)).isTrue()
    }

    /** Lunch of 2026-08-13: 3 U given at 112 mg/dL. */
    @Test
    fun lunchPrebolusIsAMeal() {
        assertThat(MealKnownGate.looksLikeMealBolus(bgMgdl = 112.0, deltaMgdl = 1.0)).isTrue()
    }

    /** 2026-07-12, 15:30: 7 U given by hand at 333 mg/dL while falling. A correction. */
    @Test
    fun highFallingBolusIsACorrection() {
        assertThat(MealKnownGate.looksLikeMealBolus(bgMgdl = 333.0, deltaMgdl = -5.0)).isFalse()
    }

    /** Night-time hyper that would not come down: 5 U at 255 mg/dL, flat. A correction. */
    @Test
    fun nightHyperBolusIsACorrection() {
        assertThat(MealKnownGate.looksLikeMealBolus(bgMgdl = 255.0, deltaMgdl = 0.0)).isFalse()
    }

    // -----------------------------------------------------------------------------------------
    // One case per condition
    // -----------------------------------------------------------------------------------------

    /** Right on the ceiling still counts as a meal; the test is "above the ceiling". */
    @Test
    fun exactlyAtTheCeilingIsStillAMeal() {
        assertThat(
            MealKnownGate.looksLikeMealBolus(
                bgMgdl = MealKnownGate.MEAL_BG_CEILING_MGDL,
                deltaMgdl = 0.0,
            )
        ).isTrue()
    }

    /** One mg/dL above the ceiling is a correction. */
    @Test
    fun justAboveTheCeilingIsACorrection() {
        assertThat(
            MealKnownGate.looksLikeMealBolus(
                bgMgdl = MealKnownGate.MEAL_BG_CEILING_MGDL + 1.0,
                deltaMgdl = 0.0,
            )
        ).isFalse()
    }

    /**
     * A prebolus taken while glucose drifts down slowly near target is still a prebolus: the
     * falling test only applies above [MealKnownGate.MEAL_FALLING_GUARD_BG_MGDL].
     */
    @Test
    fun fallingNearTargetIsStillAMeal() {
        assertThat(MealKnownGate.looksLikeMealBolus(bgMgdl = 105.0, deltaMgdl = -6.0)).isTrue()
    }

    /** Falling fast well above target means the bolus follows something already covered. */
    @Test
    fun fallingFastHighIsACorrection() {
        assertThat(MealKnownGate.looksLikeMealBolus(bgMgdl = 165.0, deltaMgdl = -4.0)).isFalse()
    }

    /** Same glucose, but flat: nothing says this is a correction, so it opens a meal. */
    @Test
    fun flatHighButUnderTheCeilingIsAMeal() {
        assertThat(MealKnownGate.looksLikeMealBolus(bgMgdl = 165.0, deltaMgdl = 0.0)).isTrue()
    }

    // -----------------------------------------------------------------------------------------
    // Declared meal duration, set by the user
    // -----------------------------------------------------------------------------------------

    private val min = 60_000L
    private val t0 = 1_790_000_000_000L

    /** Preferences that keep what is written, with the duration key set to [horizonMin]. */
    private fun prefs(horizonMin: Int = 0): Preferences {
        val longs = mutableMapOf<String, Long>()
        return mockk<Preferences>(relaxed = true).also {
            every { it.get(any<LongNonPreferenceKey>()) } answers {
                val key = firstArg<LongNonPreferenceKey>()
                longs[key.key] ?: key.defaultValue
            }
            every { it.put(any<LongNonPreferenceKey>(), any<Long>()) } answers {
                longs[firstArg<LongNonPreferenceKey>().key] = secondArg()
            }
            every { it.get(IntKey.OApsAIMIDeclaredMealHorizonMin) } returns horizonMin
        }
    }

    private fun bolus(atMs: Long, units: Double = 1.5) =
        BS(timestamp = atMs, amount = units, type = BS.Type.NORMAL, iCfg = ICfg(insulinLabel = "test", peak = 60, dia = 5.0, concentration = 1.0))

    /** Nothing set, or a value out of range: four hours, as before the setting existed. */
    @Test
    fun anUnsetDurationKeepsFourHours() {
        assertThat(MealKnownGate.declaredMealHorizonMin(prefs(0))).isEqualTo(240L)
        assertThat(MealKnownGate.declaredMealHorizonMin(prefs(1000))).isEqualTo(240L)
    }

    /**
     * A quick lunch: bolus at t0, a small rise dosed into a low at t+3h26. With the duration at
     * three hours that rise is no longer read as the meal.
     */
    @Test
    fun aShorterDurationEndsTheMealEarlier() {
        val p = prefs(180)
        MealKnownGate.arm(p, t0)
        assertThat(MealKnownGate.isWithinDeclaredMeal(p, t0 + 179 * min)).isTrue()
        assertThat(MealKnownGate.isWithinDeclaredMeal(p, t0 + 206 * min)).isFalse()
    }

    /** A pasta dinner still arriving at t+3h40 stays covered with a longer setting. */
    @Test
    fun aLongerDurationCoversASlowMeal() {
        val p = prefs(300)
        MealKnownGate.arm(p, t0)
        assertThat(MealKnownGate.isWithinDeclaredMeal(p, t0 + 220 * min)).isTrue()
    }

    // -----------------------------------------------------------------------------------------
    // "This bolus is a correction"
    // -----------------------------------------------------------------------------------------

    /** Bolus first, button a few minutes later: the window the bolus opened is closed. */
    @Test
    fun theCorrectionNoteClosesTheWindowABolusOpened() {
        val p = prefs()
        assertThat(MealKnownGate.armFromManualBolus(p, t0 + min, listOf(bolus(t0)), 145.0, 0.0)).isNotNull()
        assertThat(MealKnownGate.applyCorrectionNote(p, t0 + 10 * min)).isTrue()
        assertThat(MealKnownGate.isWithinDeclaredMeal(p, t0 + 11 * min)).isFalse()
        assertThat(MealKnownGate.isMealKnown(p, t0 + 11 * min)).isFalse()
    }

    /** Button first, bolus next: the bolus opens nothing. */
    @Test
    fun aBolusRightAfterTheCorrectionNoteOpensNothing() {
        val p = prefs()
        MealKnownGate.applyCorrectionNote(p, t0)
        assertThat(MealKnownGate.armFromManualBolus(p, t0 + 5 * min, listOf(bolus(t0 + 3 * min)), 145.0, 0.0)).isNull()
        assertThat(MealKnownGate.isWithinDeclaredMeal(p, t0 + 5 * min)).isFalse()
    }

    /** The shield does not last: a meal bolus an hour later is a meal again. */
    @Test
    fun aMealBolusLongAfterTheCorrectionNoteStillOpensTheWindow() {
        val p = prefs()
        MealKnownGate.applyCorrectionNote(p, t0)
        assertThat(MealKnownGate.armFromManualBolus(p, t0 + 61 * min, listOf(bolus(t0 + 60 * min)), 110.0, 0.0)).isNotNull()
    }

    /** An eating note pressed after the correction note wins: the meal stays declared. */
    @Test
    fun anEatingNoteNewerThanTheCorrectionNoteWins() {
        val p = prefs()
        MealKnownGate.armFromNote(p, t0 + 5 * min, noteStartMs = t0 + 5 * min)
        assertThat(MealKnownGate.applyCorrectionNote(p, t0)).isFalse()
        assertThat(MealKnownGate.isWithinDeclaredMeal(p, t0 + 6 * min)).isTrue()
    }

    /** One note acts once: a meal declared after it is not closed again on the next ticks. */
    @Test
    fun theSameCorrectionNoteActsOnce() {
        val p = prefs()
        assertThat(MealKnownGate.applyCorrectionNote(p, t0)).isFalse()
        MealKnownGate.arm(p, t0 + 40 * min)
        assertThat(MealKnownGate.applyCorrectionNote(p, t0)).isFalse()
        assertThat(MealKnownGate.isWithinDeclaredMeal(p, t0 + 41 * min)).isTrue()
    }
}
