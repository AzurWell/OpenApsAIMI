package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks when a big meal raises the max IOB: only after the big meal note or a bolus of 4 U or more,
 * only on a rise above 180 after the first 45 minutes, and only until the end of the big meal.
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
        note: Long = 0L,
        bigMealMaxIob: Double = 10.0,
        horizon: Long = 360L,
    ) = BigMealGate.evaluate(
        now = t0 + nowMin * min,
        armedAtMs = armedAt,
        bolusU = bolus,
        readings = readings,
        iobU = iob,
        maxIobU = maxIob,
        bigMealMaxIobU = bigMealMaxIob,
        horizonMin = horizon,
        bigMealNoteMs = note,
    )

    @Test
    fun aFastRiseOfABigMealRaisesTheMaxIob() {
        val d = evaluate(nowMin = 95)
        assertThat(d.bigMeal).isTrue()
        assertThat(d.release).isTrue()
        assertThat(d.riseMgdl).isEqualTo(45.0)
        assertThat(d.raisedMaxIobU).isEqualTo(10.0)
        assertThat(d.extraRoomU).isWithin(1e-9).of(3.0)
    }

    @Test
    fun theBigMealMaxIobIsTheUsersSetting() {
        val d = evaluate(nowMin = 95, bigMealMaxIob = 12.0)
        assertThat(d.raisedMaxIobU).isEqualTo(12.0)
        assertThat(d.extraRoomU).isWithin(1e-9).of(5.0)
    }

    @Test
    fun aBigMealMaxIobUnderTheUsualOneChangesNothing() {
        assertThat(evaluate(nowMin = 95, bigMealMaxIob = 7.0).release).isFalse()
        assertThat(evaluate(nowMin = 95, bigMealMaxIob = 5.0).release).isFalse()
    }

    @Test
    fun aSteadyRiseOfOneMgdlPerMinuteIsEnough() {
        assertThat(evaluate(nowMin = 95, readings = curve(95, 190, 195, 200, 205)).release).isTrue()
        assertThat(evaluate(nowMin = 95, readings = curve(95, 190, 194, 198, 204)).release).isFalse()
    }

    @Test
    fun theExtraRoomCountsOnlyWhatTheUsualMaxIobDoesNotGive() {
        assertThat(evaluate(nowMin = 95, iob = 4.0).extraRoomU).isWithin(1e-9).of(3.0)
        assertThat(evaluate(nowMin = 95, iob = 8.5).extraRoomU).isWithin(1e-9).of(1.5)
        assertThat(evaluate(nowMin = 95, iob = 10.5).extraRoomU).isWithin(1e-9).of(0.0)
    }

    @Test
    fun aBolusUnderFourUnitsChangesNothing() {
        assertThat(evaluate(nowMin = 95, bolus = 3.9)).isEqualTo(BigMealGate.Decision.INACTIVE)
        assertThat(evaluate(nowMin = 95, bolus = 4.0).release).isTrue()
    }

    @Test
    fun theBigMealNoteIsEnoughWithoutABolus() {
        val d = evaluate(nowMin = 95, bolus = 0.0, note = t0)
        assertThat(d.byNote).isTrue()
        assertThat(d.release).isTrue()
    }

    @Test
    fun aBigMealNoteOfAnEarlierMealDoesNotCount() {
        val old = t0 - (BigMealGate.BOLUS_BEFORE_ARM_MIN + 1) * min
        assertThat(evaluate(nowMin = 95, bolus = 2.0, note = old)).isEqualTo(BigMealGate.Decision.INACTIVE)
    }

    @Test
    fun theFirstMinutesAreLeftToTheBolus() {
        val d = evaluate(nowMin = 40)
        assertThat(d.bigMeal).isTrue()
        assertThat(d.release).isFalse()
    }

    @Test
    fun aSlowRiseOrALowGlucoseOnlyWatches() {
        assertThat(evaluate(nowMin = 95, readings = curve(95, 190, 192, 195, 198)).release).isFalse()
        assertThat(evaluate(nowMin = 95, readings = curve(95, 230, 232, 228, 225)).release).isFalse()
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
    fun theRuleEndsWithTheBigMeal() {
        assertThat(evaluate(nowMin = 360, readings = curve(360, 160, 175, 190, 205)).release).isTrue()
        assertThat(evaluate(nowMin = 361).bigMeal).isFalse()
        assertThat(evaluate(nowMin = 241, horizon = 240L).bigMeal).isFalse()
    }

    @Test
    fun noDeclaredMealNoRule() {
        assertThat(evaluate(nowMin = 95, armedAt = 0L)).isEqualTo(BigMealGate.Decision.INACTIVE)
    }

    @Test
    fun theMealBolusAddsTheBolusesAroundTheDeclaration() {
        // The second part of the bolus restarted the declaration at t0.
        val boluses = listOf(
            t0 - 180 * min to 2.0, // too early: an earlier meal
            t0 - 70 * min to 3.0, // first part, at the start of the meal
            t0 to 3.0, // second part, when glucose started to rise
        )
        assertThat(BigMealGate.mealBolusU(t0, t0 + 60 * min, boluses)).isWithin(1e-9).of(6.0)
    }
}
