package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the big meal rule: after the big meal note or a bolus of 4 U or more, the big meal max IOB
 * until the end of the big meal, and a push only while glucose is 140 or more and going up.
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
    fun aRiseOfABigMealRaisesTheMaxIobAndPushes() {
        val d = evaluate(nowMin = 95)
        assertThat(d.bigMeal).isTrue()
        assertThat(d.release).isTrue()
        assertThat(d.push).isTrue()
        assertThat(d.riseMgdl).isEqualTo(45.0)
        assertThat(d.stepMgdl).isEqualTo(15.0)
        assertThat(d.raisedMaxIobU).isEqualTo(10.0)
        assertThat(d.extraRoomU).isWithin(1e-9).of(3.0)
    }

    @Test
    fun theMaxIobIsRaisedFromTheStartAndWithoutARise() {
        assertThat(evaluate(nowMin = 5).release).isTrue()
        val flat = evaluate(nowMin = 95, readings = curve(95, 230, 232, 228, 225))
        assertThat(flat.release).isTrue()
        assertThat(flat.push).isFalse()
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
        assertThat(evaluate(nowMin = 95, bigMealMaxIob = 5.0).raisedMaxIobU).isEqualTo(7.0)
    }

    @Test
    fun aSlowRiseOfThreeMgdlPer5MinutesIsEnoughToPush() {
        assertThat(evaluate(nowMin = 95, readings = curve(95, 200, 203, 206, 209)).push).isTrue()
        assertThat(evaluate(nowMin = 95, readings = curve(95, 200, 203, 205, 208)).push).isFalse()
    }

    @Test
    fun aFlatOrFallingLastStepStopsThePush() {
        assertThat(evaluate(nowMin = 95, readings = curve(95, 180, 195, 210, 210)).push).isFalse()
        assertThat(evaluate(nowMin = 95, readings = curve(95, 180, 195, 212, 210)).push).isFalse()
    }

    @Test
    fun noPushUnder140() {
        assertThat(evaluate(nowMin = 95, readings = curve(95, 100, 115, 125, 139)).push).isFalse()
        assertThat(evaluate(nowMin = 95, readings = curve(95, 100, 115, 125, 140)).push).isTrue()
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
        assertThat(evaluate(nowMin = 95, bolus = 4.0).push).isTrue()
    }

    @Test
    fun theBigMealNoteIsEnoughWithoutABolus() {
        val d = evaluate(nowMin = 95, bolus = 0.0, note = t0)
        assertThat(d.byNote).isTrue()
        assertThat(d.push).isTrue()
    }

    @Test
    fun aBigMealNoteOfAnEarlierMealDoesNotCount() {
        val old = t0 - (BigMealGate.BOLUS_BEFORE_ARM_MIN + 1) * min
        assertThat(evaluate(nowMin = 95, bolus = 2.0, note = old)).isEqualTo(BigMealGate.Decision.INACTIVE)
    }

    @Test
    fun aGapInTheDataIsNotARise() {
        val gap = listOf(
            BigMealGate.Reading(t0 + 60 * min, 150.0),
            BigMealGate.Reading(t0 + 95 * min, 210.0),
        )
        val d = evaluate(nowMin = 95, readings = gap)
        assertThat(d.release).isTrue()
        assertThat(d.push).isFalse()
    }

    @Test
    fun theRuleEndsWithTheBigMeal() {
        assertThat(evaluate(nowMin = 360, readings = curve(360, 160, 175, 190, 205)).push).isTrue()
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
    // -----------------------------------------------------------------------------------------
    // The second wave is pushed less
    // -----------------------------------------------------------------------------------------

    /** Glucose every 5 minutes from the declaration (t0) to [mgdl].size - 1 readings later. */
    private fun fromArm(vararg mgdl: Int) =
        mgdl.mapIndexed { i, v -> BigMealGate.Reading(t0 + i * 5L * min, v.toDouble()) }

    /** First wave 131 -> 199, down to 105, then a slow second wave 105 -> 148 (+12 per 15 min). */
    private val slowSecondWave = fromArm(
        131, 150, 172, 190, 199, 196, 185, 170, 150, 130, 115, 107, 105,
        108, 112, 115, 119, 123, 126, 130, 135, 141, 148,
    )

    @Test
    fun aSecondWavePushesOnlyUpToFiveUnitsOnBoard() {
        val now = (slowSecondWave.size - 1) * 5L
        val low = evaluate(nowMin = now, readings = slowSecondWave, iob = 1.1)
        assertThat(low.secondWave).isTrue()
        assertThat(low.push).isTrue()
        assertThat(low.pushMaxIobU).isEqualTo(BigMealGate.SECOND_WAVE_PUSH_MAX_IOB_U)
        // The big meal max IOB is gone on the second wave.
        assertThat(low.release).isFalse()
        assertThat(low.raisedMaxIobU).isEqualTo(7.0)
        val full = evaluate(nowMin = now, readings = slowSecondWave, iob = 5.0)
        assertThat(full.push).isFalse()
    }

    @Test
    fun theFirstWaveStillPushesUpToTheBigMealMaxIob() {
        val firstWave = fromArm(131, 150, 172, 190)
        val d = evaluate(nowMin = 15, readings = firstWave, iob = 6.0)
        assertThat(d.secondWave).isFalse()
        assertThat(d.push).isTrue()
        assertThat(d.release).isTrue()
        assertThat(d.pushMaxIobU).isEqualTo(10.0)
    }

    /** A second wave climbing above the first peak is still a second wave. */
    @Test
    fun aSecondWaveAboveTheFirstPeakIsStillASecondWave() {
        val r = fromArm(160, 190, 229, 210, 180, 150, 144, 160, 180, 200, 215, 235)
        val d = evaluate(nowMin = (r.size - 1) * 5L, readings = r, iob = 4.0)
        assertThat(d.secondWave).isTrue()
        assertThat(d.push).isTrue()
        assertThat(evaluate(nowMin = (r.size - 1) * 5L, readings = r, iob = 5.2).push).isFalse()
    }

    /** A dip after the bolus, before any peak, is not a first wave come down. */
    @Test
    fun aPrebolusDipIsNotAFirstWave() {
        assertThat(BigMealGate.firstWaveCameDown(t0, fromArm(150, 140, 125, 115, 120, 140, 160))).isFalse()
    }
}
