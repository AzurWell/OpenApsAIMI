package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.ICfg
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.LongNonPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Locks how a meal is declared: a manual bolus or the eating note opens it, a "correction" note
 * next to a bolus keeps it closed, and the user sets how long it lasts.
 */
class MealKnownGateTest {

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
    // Big meal: its own duration, set by the user
    // -----------------------------------------------------------------------------------------

    /** A big meal stays declared for the big meal duration and is active until 1 h before its end. */
    @Test
    fun aBigMealLastsLonger() {
        val p = prefs(240)
        every { p.get(IntKey.OApsAIMIBigMealHorizonMin) } returns 360
        MealKnownGate.arm(p, t0)
        assertThat(MealKnownGate.markBigMeal(p)).isTrue()
        assertThat(MealKnownGate.isBigMeal(p)).isTrue()
        assertThat(MealKnownGate.isWithinDeclaredMeal(p, t0 + 359 * min)).isTrue()
        assertThat(MealKnownGate.isWithinDeclaredMeal(p, t0 + 361 * min)).isFalse()
        assertThat(MealKnownGate.isMealKnown(p, t0 + 299 * min)).isTrue()
        assertThat(MealKnownGate.isMealKnown(p, t0 + 301 * min)).isFalse()
        assertThat(MealKnownGate.isMealStillAbsorbing(p, t0 + 359 * min)).isTrue()
    }

    /** Marking twice changes nothing, and a new meal starts as a normal one. */
    @Test
    fun aNewMealIsNotBigAnyMore() {
        val p = prefs(240)
        MealKnownGate.arm(p, t0)
        MealKnownGate.markBigMeal(p)
        assertThat(MealKnownGate.markBigMeal(p)).isFalse()
        MealKnownGate.arm(p, t0 + 400 * min)
        assertThat(MealKnownGate.isBigMeal(p)).isFalse()
        assertThat(MealKnownGate.declaredMealHorizonMin(p)).isEqualTo(240L)
    }

    /** No meal running: nothing to mark. */
    @Test
    fun noMealNothingToMark() {
        assertThat(MealKnownGate.markBigMeal(prefs(240))).isFalse()
    }

    // -----------------------------------------------------------------------------------------
    // "This bolus is a correction"
    // -----------------------------------------------------------------------------------------

    /** A bolus given high is a meal too: a dinner can start above 180 mg/dL. */
    @Test
    fun aBolusGivenHighOpensTheWindow() {
        val p = prefs()
        assertThat(MealKnownGate.armFromManualBolus(p, t0 + min, listOf(bolus(t0, 3.0)))).isNotNull()
        assertThat(MealKnownGate.isMealKnown(p, t0 + 2 * min)).isTrue()
    }

    /** A correction given high is told apart by the note, not by glucose. */
    @Test
    fun aHighCorrectionWithItsNoteOpensNothing() {
        val p = prefs()
        MealKnownGate.applyCorrectionNote(p, t0)
        assertThat(MealKnownGate.armFromManualBolus(p, t0 + 3 * min, listOf(bolus(t0 + 2 * min, 3.0)))).isNull()
        assertThat(MealKnownGate.isMealKnown(p, t0 + 3 * min)).isFalse()
    }

    /** Bolus first, button a few minutes later: the window the bolus opened is closed. */
    @Test
    fun theCorrectionNoteClosesTheWindowABolusOpened() {
        val p = prefs()
        assertThat(MealKnownGate.armFromManualBolus(p, t0 + min, listOf(bolus(t0)))).isNotNull()
        assertThat(MealKnownGate.applyCorrectionNote(p, t0 + 10 * min)).isTrue()
        assertThat(MealKnownGate.isWithinDeclaredMeal(p, t0 + 11 * min)).isFalse()
        assertThat(MealKnownGate.isMealKnown(p, t0 + 11 * min)).isFalse()
    }

    /** Button first, bolus next: the bolus opens nothing. */
    @Test
    fun aBolusRightAfterTheCorrectionNoteOpensNothing() {
        val p = prefs()
        MealKnownGate.applyCorrectionNote(p, t0)
        assertThat(MealKnownGate.armFromManualBolus(p, t0 + 5 * min, listOf(bolus(t0 + 3 * min)))).isNull()
        assertThat(MealKnownGate.isWithinDeclaredMeal(p, t0 + 5 * min)).isFalse()
    }

    /** The shield does not last: a meal bolus an hour later is a meal again. */
    @Test
    fun aMealBolusLongAfterTheCorrectionNoteStillOpensTheWindow() {
        val p = prefs()
        MealKnownGate.applyCorrectionNote(p, t0)
        assertThat(MealKnownGate.armFromManualBolus(p, t0 + 61 * min, listOf(bolus(t0 + 60 * min)))).isNotNull()
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
