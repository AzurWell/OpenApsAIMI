package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import app.aaps.core.keys.interfaces.LongNonPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Locks what counts as a second wave of a declared meal, and the insulin-on-board cap on it.
 *
 * Every sequence below is one glucose value every ten minutes, starting at the tick the meal was
 * declared.
 */
class SecondWaveGateTest {

    private val min = 60_000L
    private val t0 = 1_790_000_000_000L

    /** Preferences that keep what is written. */
    private fun prefs(): Preferences {
        val longs = mutableMapOf<String, Long>()
        return mockk<Preferences>(relaxed = true).also {
            every { it.get(any<LongNonPreferenceKey>()) } answers {
                val key = firstArg<LongNonPreferenceKey>()
                longs[key.key] ?: key.defaultValue
            }
            every { it.put(any<LongNonPreferenceKey>(), any<Long>()) } answers {
                longs[firstArg<LongNonPreferenceKey>().key] = secondArg()
            }
        }
    }

    /** Replays [bg] tick by tick, as the loop does, and returns the verdict of every tick. */
    private fun replay(bg: List<Int>, stepMin: Long = 10): List<SecondWaveGate.Verdict> {
        val p = prefs()
        MealKnownGate.arm(p, t0)
        return bg.mapIndexed { i, value ->
            val now = t0 + i * stepMin * min
            MealKnownGate.trackBg(p, value.toDouble(), now)
            SecondWaveGate.evaluate(p, now, declaredCobG = 0.0, bgMgdl = value.toDouble())
        }
    }

    // -----------------------------------------------------------------------------------------
    // The shape: first wave up, then down, then up again
    // -----------------------------------------------------------------------------------------

    /** Peak 80 above the start, 73 down from it, then a small rise: a second wave, written as such. */
    @Test
    fun aRiseAfterTheFirstWaveCameDownIsASecondWave() {
        val v = replay(listOf(100, 120, 150, 180, 170, 150, 130, 115, 107, 110, 120))
        assertThat(v.take(6).none { it.secondWave }).isTrue()
        assertThat(v.drop(6).all { it.secondWave }).isTrue()
        assertThat(v.last().reason).isEqualTo("2nd wave t+100min +13/35 over trough 107 (peak 180)")
    }

    /** The first wave itself, climbing from the declaration to its peak. */
    @Test
    fun theFirstWaveIsNotASecondWave() {
        val v = replay(listOf(120, 120, 122, 117, 131, 159, 181))
        assertThat(v.none { it.secondWave }).isTrue()
    }

    /** A prebolus dip before the carbs arrive is not a descent: there was no peak yet. */
    @Test
    fun aPrebolusDipIsNotADescent() {
        val v = replay(listOf(150, 140, 125, 115, 112, 115, 120, 130, 140, 150))
        assertThat(v.none { it.secondWave }).isTrue()
    }

    /** A dip of 24 from the peak is too small to count as the first wave coming down. */
    @Test
    fun aSmallDipIsNotTheEndOfTheFirstWave() {
        val v = replay(listOf(100, 140, 170, 190, 197, 197, 190, 173, 175, 195, 205))
        assertThat(v.none { it.secondWave }).isTrue()
    }

    /** Pressing "eating" again restarts the meal: the second wave is a first wave again. */
    @Test
    fun aNewDeclarationStartsOver() {
        val p = prefs()
        MealKnownGate.arm(p, t0)
        listOf(120, 180, 140).forEach { MealKnownGate.trackBg(p, it.toDouble()) }
        MealKnownGate.arm(p, t0 + 120 * min)
        MealKnownGate.trackBg(p, 150.0)
        assertThat(SecondWaveGate.evaluate(p, t0 + 200 * min, declaredCobG = 0.0, bgMgdl = 150.0).secondWave).isFalse()
    }

    /**
     * Once the rise is no longer read as a meal (declaration over), the gate stays out.
     */
    @Test
    fun aRiseThatIsNotReadAsAMealIsNotASecondWave() {
        val p = prefs()
        MealKnownGate.arm(p, t0)
        listOf(150, 198, 165).forEach { MealKnownGate.trackBg(p, it.toDouble()) }
        val inMeal = SecondWaveGate.evaluate(p, t0 + 220 * min, declaredCobG = 0.0, bgMgdl = 165.0)
        val afterMeal = SecondWaveGate.evaluate(p, t0 + 250 * min, declaredCobG = 0.0, bgMgdl = 165.0, mealBlocked = true)
        assertThat(inMeal.secondWave).isTrue()
        assertThat(afterMeal.secondWave).isFalse()
    }

    /** Declared carbs give the loop its own memory of the meal: the gate stays out. */
    @Test
    fun declaredCarbsLeaveTheGateOut() {
        val p = prefs()
        MealKnownGate.arm(p, t0)
        listOf(120, 180, 140).forEach { MealKnownGate.trackBg(p, it.toDouble()) }
        assertThat(SecondWaveGate.evaluate(p, t0 + 120 * min, declaredCobG = 30.0, bgMgdl = 150.0).secondWave).isFalse()
    }

    /** A second wave that climbs above the first peak stays a second wave. */
    @Test
    fun aSecondWaveAboveTheFirstPeakKeepsItsShape() {
        val v = replay(listOf(143, 150, 160, 167, 160, 150, 140, 137, 150, 165, 173, 185, 195))
        // 173 is above the first peak 167: still a second wave, not idle.
        assertThat(v[10].secondWave).isTrue()
        assertThat(v[10].reason).isEqualTo("2nd wave proven t+100min +36/35 over trough 137 (peak 167)")
        assertThat(v[12].secondWave).isTrue()
    }

    /** Any tick of a second wave stops adding at 6 U on board, whatever the glucose; below 6 U it is dosed as usual. */
    @Test
    fun aSecondWaveStopsAddingAtSixUnitsOnBoard() {
        val v = replay(listOf(143, 150, 160, 167, 160, 150, 140, 137, 150, 165, 173, 185, 195))
        val early = v[8]
        val proven = v[12]
        assertThat(early.secondWave).isTrue()
        assertThat(SecondWaveGate.iobCapped(early, 6.0)).isTrue()
        assertThat(SecondWaveGate.iobCapped(early, 3.0)).isFalse()
        assertThat(SecondWaveGate.iobCapped(proven, 6.2)).isTrue()
        assertThat(SecondWaveGate.iobCapped(proven, 5.9)).isFalse()
        // The first wave is never capped by this rule.
        assertThat(v[3].secondWave).isFalse()
        assertThat(SecondWaveGate.iobCapped(v[3], 8.0)).isFalse()
    }

    // -----------------------------------------------------------------------------------------
    // The late rise: a second wave whatever the first wave looked like
    // -----------------------------------------------------------------------------------------

    /**
     * A meal declared while glucose was already going up: the first wave is only 5 above the
     * declaration, comes down, and glucose climbs again from one hour on. From two hours on that
     * rise is a second wave, and it stops adding at 6 U on board.
     */
    @Test
    fun aLateRiseAfterATinyFirstWaveIsASecondWave() {
        val v = replay(listOf(140, 143, 145, 138, 125, 112, 104, 110, 121, 133, 146, 158, 171, 183, 190))
        // Before two hours the rise is left alone, even 54 above its trough.
        assertThat(v.take(12).none { it.secondWave }).isTrue()
        // t+120: 67 above the trough of 104.
        assertThat(v[12].secondWave).isTrue()
        assertThat(v[12].reason).isEqualTo("2nd wave late t+120min +67/30 over trough 104 since t+60min")
        assertThat(SecondWaveGate.iobCapped(v[12], 6.5)).isTrue()
        assertThat(SecondWaveGate.iobCapped(v[12], 4.0)).isFalse()
        assertThat(v.drop(12).all { it.secondWave }).isTrue()
    }

    /** A first wave of 14, a shallow trough, then a slow rise: a second wave once it is 30 above the trough. */
    @Test
    fun aSlowLateRiseIsASecondWaveOnceItIsThirtyAboveItsTrough() {
        val v = replay(listOf(125, 131, 137, 139, 134, 128, 124, 121, 123, 127, 133, 139, 143, 146, 149, 152, 158, 164, 170))
        // t+120 to t+140: 22 to 28 above the trough of 121, not enough.
        assertThat(v.take(15).none { it.secondWave }).isTrue()
        // t+150: 31 above.
        assertThat(v[15].secondWave).isTrue()
        assertThat(SecondWaveGate.iobCapped(v[18], 6.7)).isTrue()
    }

    /** A rise of 20 above the trough at t+150 is not a second wave. */
    @Test
    fun aSmallLateRiseIsLeftAlone() {
        val v = replay(listOf(120, 128, 136, 138, 132, 126, 120, 116, 114, 112, 113, 118, 122, 127, 130, 132, 131, 128))
        assertThat(v.none { it.secondWave }).isTrue()
        assertThat(v.all { it.reason == "idle" }).isTrue()
        assertThat(SecondWaveGate.iobCapped(v[15], 8.0)).isFalse()
    }

    /** Once seen, the late rise stays a second wave when glucose comes back down and climbs again. */
    @Test
    fun aLateRiseStaysASecondWaveForTheRestOfTheMeal() {
        // The rise tops out 10 above the declaration: the first-wave shape never applies here.
        val v = replay(listOf(140, 143, 145, 138, 125, 112, 104, 110, 121, 130, 138, 144, 150, 146, 135, 122, 112, 118, 125))
        assertThat(v[12].secondWave).isTrue()
        // 125 is only 21 above the trough, on the way up again with insulin still on board.
        assertThat(v[18].reason).isEqualTo("2nd wave late t+180min +21/30 over trough 104 since t+60min")
        assertThat(v[18].secondWave).isTrue()
        assertThat(SecondWaveGate.iobCapped(v[18], 6.0)).isTrue()
    }

    /** The late trough starts one hour after the declaration: a prebolus dip before that does not count. */
    @Test
    fun theLateTroughStartsOneHourAfterTheDeclaration() {
        // Lowest value 95 at t+30; from t+60 the lowest is 118.
        val v = replay(listOf(120, 108, 100, 95, 102, 110, 118, 124, 130, 134, 138, 140, 142, 144, 149))
        // t+120 to t+130: 24 and 26 above 118. Measured from 95 they would be 47 and 49.
        assertThat(v.take(14).none { it.secondWave }).isTrue()
        // t+140: 31 above 118.
        assertThat(v[14].secondWave).isTrue()
    }

    /** A new declaration forgets the late rise of the meal before it. */
    @Test
    fun aNewDeclarationForgetsTheLateRise() {
        val p = prefs()
        MealKnownGate.arm(p, t0)
        MealKnownGate.trackBg(p, 140.0, t0)
        MealKnownGate.trackBg(p, 105.0, t0 + 60 * min)
        MealKnownGate.trackBg(p, 150.0, t0 + 130 * min)
        assertThat(SecondWaveGate.evaluate(p, t0 + 130 * min, declaredCobG = 0.0, bgMgdl = 150.0).secondWave).isTrue()
        MealKnownGate.arm(p, t0 + 135 * min)
        MealKnownGate.trackBg(p, 155.0, t0 + 200 * min)
        assertThat(SecondWaveGate.evaluate(p, t0 + 200 * min, declaredCobG = 0.0, bgMgdl = 155.0).secondWave).isFalse()
    }

    /** The rule on plain numbers. */
    @Test
    fun theLateRiseNeedsTwoHoursAndThirtyAboveTheTrough() {
        assertThat(SecondWaveGate.lateRise(elapsedMin = 120, bgMgdl = 141.0, lateTroughMgdl = 111.0)).isTrue()
        assertThat(SecondWaveGate.lateRise(elapsedMin = 119, bgMgdl = 190.0, lateTroughMgdl = 111.0)).isFalse()
        assertThat(SecondWaveGate.lateRise(elapsedMin = 150, bgMgdl = 140.0, lateTroughMgdl = 111.0)).isFalse()
        assertThat(SecondWaveGate.lateRise(elapsedMin = 150, bgMgdl = 140.0, lateTroughMgdl = 0.0)).isFalse()
    }
}
