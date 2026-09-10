package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import com.google.common.truth.Truth.assertThat
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
}
