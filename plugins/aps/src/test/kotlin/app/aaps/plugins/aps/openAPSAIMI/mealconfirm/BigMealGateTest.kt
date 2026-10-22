package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks when a big meal bolus would raise the max IOB: only a bolus of 5 U or more, only on a fast
 * rise above 180 after the first 45 minutes.
 */
class BigMealGateTest {

    private val min = 60_000L
    private val t0 = 1_790_000_000_000L

    /** Glucose every 5 minutes, the last reading at [nowMin]. */
    private fun curve(nowMin: Long, vararg mgdl: Int) =
        mgdl.mapIndexed { i, v -> BigMealGate.Reading(t0 + (nowMin - (mgdl.size - 1 - i) * 5L) * min, v.toDouble()) }

    private fun evaluate(
        nowMin: Long,
        bolus: Double = 5.0,
        readings: List<BigMealGate.Reading> = curve(nowMin, 160, 175, 190, 205),
        iob: Double = 6.9,
        maxIob: Double = 7.0,
        armedAt: Long = t0,
    ) = BigMealGate.evaluate(
        now = t0 + nowMin * min,
        armedAtMs = armedAt,
        bolusU = bolus,
        readings = readings,
        iobU = iob,
        maxIobU = maxIob,
    )

    @Test
    fun aFastRiseOfABigMealRaisesTheMaxIob() {
        val d = evaluate(nowMin = 95)
        assertThat(d.bigMeal).isTrue()
        assertThat(d.release).isTrue()
        assertThat(d.riseMgdl).isEqualTo(45.0)
        assertThat(d.extraRoomU).isWithin(1e-9).of(3.0)
    }

    @Test
    fun theExtraRoomCountsOnlyWhatTheUsualMaxIobDoesNotGive() {
        assertThat(evaluate(nowMin = 95, iob = 4.0).extraRoomU).isWithin(1e-9).of(3.0)
        assertThat(evaluate(nowMin = 95, iob = 8.5).extraRoomU).isWithin(1e-9).of(1.5)
        assertThat(evaluate(nowMin = 95, iob = 10.5).extraRoomU).isWithin(1e-9).of(0.0)
    }

    @Test
    fun aBolusUnderFiveUnitsChangesNothing() {
        assertThat(evaluate(nowMin = 95, bolus = 4.9)).isEqualTo(BigMealGate.Decision.INACTIVE)
    }

    @Test
    fun theFirstMinutesAreLeftToTheBolus() {
        val d = evaluate(nowMin = 40)
        assertThat(d.bigMeal).isTrue()
        assertThat(d.release).isFalse()
    }

    @Test
    fun aSlowRiseOrALowGlucoseOnlyWatches() {
        assertThat(evaluate(nowMin = 95, readings = curve(95, 190, 195, 200, 210)).release).isFalse()
        assertThat(evaluate(nowMin = 95, readings = curve(95, 120, 135, 150, 170)).release).isFalse()
    }

    @Test
    fun aGapInTheDataIsNotARise() {
        val gap = listOf(
            BigMealGate.Reading(t0 + 60 * min, 150.0),
            BigMealGate.Reading(t0 + 95 * min, 210.0),
        )
        assertThat(evaluate(nowMin = 95, readings = gap).release).isFalse()
    }

    @Test
    fun theRuleEndsFiveHoursAfterTheMeal() {
        assertThat(evaluate(nowMin = 301).bigMeal).isFalse()
    }

    @Test
    fun noDeclaredMealNoRule() {
        assertThat(evaluate(nowMin = 95, armedAt = 0L)).isEqualTo(BigMealGate.Decision.INACTIVE)
    }

    @Test
    fun theMealBolusAddsTheBolusesAroundTheDeclaration() {
        val boluses = listOf(
            t0 - 30 * min to 2.0, // too early: an earlier meal or a correction
            t0 - 10 * min to 3.0, // the bolus, then the eating note
            t0 + 20 * min to 2.0, // a second bolus for the same meal
        )
        assertThat(BigMealGate.mealBolusU(t0, t0 + 60 * min, boluses)).isWithin(1e-9).of(5.0)
    }
}
