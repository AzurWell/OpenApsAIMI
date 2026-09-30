package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import app.aaps.core.keys.interfaces.LongNonPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Locks the "dose on proof" rule on the real meals it was measured on.
 *
 * Every sequence below is the user's Nightscout glucose, one value every ten minutes, starting at
 * the tick the meal was declared.
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
            MealKnownGate.trackBg(p, value.toDouble())
            SecondWaveGate.evaluate(p, now, declaredCobG = 0.0, bgMgdl = value.toDouble())
        }
    }

    // -----------------------------------------------------------------------------------------
    // The false second rises — held
    // -----------------------------------------------------------------------------------------

    /** 2026-09-24 lunch: pasta, peak 181, trough 108, then 1.9 U on a rise to 126 → hypo 50. */
    @Test
    fun theSmallRiseBeforeTheHypoOf24SeptemberIsHeld() {
        val v = replay(listOf(121, 120, 122, 117, 131, 159, 181, 176, 160, 150, 147, 141, 139, 139, 135, 124, 117, 111, 108, 111, 109, 111, 114, 120, 126, 121))
        assertThat(v.takeLast(6).all { it.hold }).isTrue()
    }

    /** 2026-09-27 lunch: peak 195, trough 154, then 5.1 U on 154 → 192 → hypo 64. */
    @Test
    fun theRiseBeforeTheHypoOf27SeptemberIsHeldUntilItIsProven() {
        val bg = listOf(122, 123, 133, 140, 154, 177, 190, 195, 195, 190, 181, 166, 156, 154, 154, 167, 176, 186, 191)
        val v = replay(bg)
        // 167, 176 and 186 are held: most of the 5.1 U went in there.
        assertThat(v.subList(15, 18).all { it.hold }).isTrue()
        // 191 is 37 above the trough: proven, the loop doses again.
        assertThat(v.last().hold).isFalse()
    }

    /** 2026-09-23 lunch: peak 228, trough 168, then 4.4 U on a rise to 183 that fell to 100. */
    @Test
    fun theFalseRiseOf23SeptemberIsHeld() {
        val v = replay(listOf(141, 151, 141, 132, 143, 167, 193, 216, 228, 227, 205, 201, 189, 181, 172, 168, 176, 183, 181))
        assertThat(v.takeLast(3).all { it.hold }).isTrue()
    }

    // -----------------------------------------------------------------------------------------
    // What is left alone
    // -----------------------------------------------------------------------------------------

    /** 2026-09-22 lunch: a real second wave to 218. The dip 197 → 173 is too small to count. */
    @Test
    fun theRealSecondWaveOf22SeptemberIsNeverHeld() {
        val v = replay(listOf(100, 138, 171, 192, 197, 197, 197, 190, 173, 175, 194, 203, 210, 218, 206))
        assertThat(v.none { it.hold }).isTrue()
    }

    /** The first wave itself, climbing from the declaration to its peak. */
    @Test
    fun theFirstWaveIsNeverHeld() {
        val v = replay(listOf(121, 120, 122, 117, 131, 159, 181))
        assertThat(v.none { it.hold }).isTrue()
    }

    /** A prebolus dip before the carbs arrive is not a descent: there was no peak yet. */
    @Test
    fun aPrebolusDipIsNotADescent() {
        val v = replay(listOf(150, 140, 125, 115, 112, 115, 120, 130, 140, 150))
        assertThat(v.none { it.hold }).isTrue()
    }

    /** A meal declared at 245 has no first wave to come down from: the rise after it is dosed. */
    @Test
    fun aMealDeclaredHighIsNeverHeld() {
        // 2026-09-24 evening, from the 2 U bolus at 20:00: the real second wave of the baguette.
        val v = replay(listOf(245, 233, 230, 212, 168, 170, 191, 193, 182, 191, 186, 241))
        assertThat(v.none { it.hold }).isTrue()
    }

    /** Bread with cheese: a high plateau that dips and comes back is left to the loop above 200. */
    @Test
    fun aHighPlateauIsNotHeldAbove200() {
        assertThat(SecondWaveGate.holds(bgMgdl = 205.0, startMgdl = 100.0, peakMgdl = 250.0, troughMgdl = 190.0)).isFalse()
    }

    /** Pressing "eating" again restarts the meal: the second wave is a first wave again. */
    @Test
    fun aNewDeclarationStartsOver() {
        val p = prefs()
        MealKnownGate.arm(p, t0)
        listOf(120, 180, 140).forEach { MealKnownGate.trackBg(p, it.toDouble()) }
        MealKnownGate.arm(p, t0 + 120 * min)
        MealKnownGate.trackBg(p, 150.0)
        assertThat(SecondWaveGate.evaluate(p, t0 + 200 * min, declaredCobG = 0.0, bgMgdl = 150.0).hold).isFalse()
    }

    /**
     * Once the rise is no longer read as a meal (declaration over, or "not eating"), the gate
     * stays out: a flat high plateau after a slow dinner keeps its small corrections.
     */
    @Test
    fun aRiseThatIsNotReadAsAMealIsNotHeld() {
        val p = prefs()
        MealKnownGate.arm(p, t0)
        listOf(150, 198, 165).forEach { MealKnownGate.trackBg(p, it.toDouble()) }
        val inMeal = SecondWaveGate.evaluate(p, t0 + 220 * min, declaredCobG = 0.0, bgMgdl = 165.0)
        val afterMeal = SecondWaveGate.evaluate(p, t0 + 250 * min, declaredCobG = 0.0, bgMgdl = 165.0, mealBlocked = true)
        assertThat(inMeal.hold).isTrue()
        assertThat(afterMeal.hold).isFalse()
    }

    /** Declared carbs give the loop its own memory of the meal: the gate stays out. */
    @Test
    fun declaredCarbsLeaveTheGateOut() {
        val p = prefs()
        MealKnownGate.arm(p, t0)
        listOf(120, 180, 140).forEach { MealKnownGate.trackBg(p, it.toDouble()) }
        assertThat(SecondWaveGate.evaluate(p, t0 + 120 * min, declaredCobG = 30.0, bgMgdl = 150.0).hold).isFalse()
    }
}
