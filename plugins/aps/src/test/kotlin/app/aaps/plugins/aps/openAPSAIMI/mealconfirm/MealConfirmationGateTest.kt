package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Locks the basal ceiling with no meal declared and the "a meal must be declared" rule.
 */
class MealConfirmationGateTest {

    private val paris = ZoneId.of("Europe/Paris")

    private fun at(hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 9, 11, hour, minute, 0, 0, paris).toInstant().toEpochMilli()

    // -----------------------------------------------------------------------------------------
    // Basal ceiling while no meal is declared
    // -----------------------------------------------------------------------------------------

    private val profileBasal = 0.85
    private val normalMax = 3.4

    /**
     * No meal declared, BG 120 and rising, close to the max basal asked. The rise is not a meal,
     * so the basal goes back to the profile rate.
     */
    @Test
    fun aDeniedRiseUnder180GetsTheProfileBasal() {
        assertThat(MealConfirmationGate.deniedBasalCeilingUph(120.0, profileBasal, normalMax)).isEqualTo(profileBasal)
    }

    /** Just under the line it is still not a high. */
    @Test
    fun justUnderTheHyperLineStaysAtProfile() {
        assertThat(MealConfirmationGate.deniedBasalCeilingUph(179.0, profileBasal, normalMax)).isEqualTo(profileBasal)
    }

    /** A real high is still corrected, with the normal limit and not the meal bypass. */
    @Test
    fun aRealHighKeepsTheNormalLimit() {
        assertThat(MealConfirmationGate.deniedBasalCeilingUph(180.0, profileBasal, normalMax)).isEqualTo(normalMax)
        assertThat(MealConfirmationGate.deniedBasalCeilingUph(250.0, profileBasal, normalMax)).isEqualTo(normalMax)
    }

    /** A broken normal limit under the profile rate never pushes a high below the profile basal. */
    @Test
    fun theCeilingIsNeverBelowTheProfileBasalOnAHigh() {
        assertThat(MealConfirmationGate.deniedBasalCeilingUph(250.0, profileBasal, 0.5)).isEqualTo(profileBasal)
    }

    /** A flat 150 with no meal may get more than the profile basal, up to the factor. */
    @Test
    fun anElevatedGlucoseGetsTheProfileBasalTimesTheFactor() {
        assertThat(MealConfirmationGate.deniedBasalCeilingUph(140.0, profileBasal, normalMax, 1.5)).isWithin(1e-9).of(1.275)
        assertThat(MealConfirmationGate.deniedBasalCeilingUph(179.0, profileBasal, normalMax, 1.5)).isWithin(1e-9).of(1.275)
    }

    /** Under 140 the factor changes nothing: a night already in range is left as it is. */
    @Test
    fun theFactorDoesNothingUnderTheElevatedLine() {
        assertThat(MealConfirmationGate.deniedBasalCeilingUph(139.0, profileBasal, normalMax, 2.0)).isEqualTo(profileBasal)
    }

    /** The factor never lifts the ceiling above the normal limit, and never lowers it. */
    @Test
    fun theFactorStaysBetweenTheProfileBasalAndTheNormalLimit() {
        assertThat(MealConfirmationGate.deniedBasalCeilingUph(150.0, profileBasal, 1.0, 2.0)).isEqualTo(1.0)
        assertThat(MealConfirmationGate.deniedBasalCeilingUph(150.0, profileBasal, normalMax, 0.5)).isEqualTo(profileBasal)
    }

    /** The key gives the factor in percent; a value outside its range falls back to 1.5. */
    @Test
    fun theFactorComesFromTheKey() {
        fun prefs(pct: Int) = mockk<Preferences> { every { get(IntKey.OApsAIMINoMealElevatedBasalPct) } returns pct }
        assertThat(MealConfirmationGate.elevatedBasalFactor(prefs(100))).isEqualTo(1.0)
        assertThat(MealConfirmationGate.elevatedBasalFactor(prefs(180))).isEqualTo(1.8)
        assertThat(MealConfirmationGate.elevatedBasalFactor(prefs(300))).isEqualTo(3.0)
        assertThat(MealConfirmationGate.elevatedBasalFactor(prefs(0))).isEqualTo(MealConfirmationGate.DEFAULT_ELEVATED_BASAL_FACTOR)
    }

    // -----------------------------------------------------------------------------------------
    // "A meal must be declared"
    // -----------------------------------------------------------------------------------------

    private fun prefs(mealArmedAt: Long = 0L): Preferences =
        mockk<Preferences>(relaxed = true).also {
            every { it.get(AimiLongKey.MealKnownArmedAt) } returns mealArmedAt
        }

    /** Nothing eaten, nothing declared: the rise is not a meal. */
    @Test
    fun anUndeclaredRiseIsNotAMeal() {
        val now = at(17, 3)
        assertThat(MealConfirmationGate.isMealInterpretationBlocked(prefs(), now)).isTrue()
        assertThat(MealConfirmationGate.statusLine(prefs(), now)).isEqualTo("🚫 meal-confirm: not declared, not a meal")
    }

    /** The eating note opens the meal, and its second wave 2.5 h later is covered. */
    @Test
    fun aDeclaredMealAndItsSecondWaveAreNotBlocked() {
        val p = prefs(mealArmedAt = at(12, 38))
        assertThat(MealConfirmationGate.isMealInterpretationBlocked(p, at(13, 0))).isFalse()
        assertThat(MealConfirmationGate.statusLine(p, at(13, 0))).isEqualTo("🍽️ meal-confirm: idle")
        assertThat(MealConfirmationGate.isMealInterpretationBlocked(p, at(15, 7))).isFalse()
    }

    /** A slow meal: the third rise at t+3h43 is still the same meal. */
    @Test
    fun aSlowMealIsCoveredForFourHours() {
        val p = prefs(mealArmedAt = at(14, 44))
        assertThat(MealConfirmationGate.isMealInterpretationBlocked(p, at(18, 27))).isFalse()
    }

    /** t+4h25 after the note, nothing eaten: not a meal any more. */
    @Test
    fun theRiseAfterFourHoursIsNotAMeal() {
        val p = prefs(mealArmedAt = at(12, 38))
        assertThat(MealConfirmationGate.isMealInterpretationBlocked(p, at(17, 3))).isTrue()
    }

    /** A flat 165 at 4:00 with little insulin on board: the ceiling is lifted. */
    @Test
    fun aFlatNightHighWithLittleInsulinIsStuck() {
        assertThat(MealConfirmationGate.isStuckNightHigh(165.0, 0.2, -0.5, 2.1, at(4, 0), paris)).isTrue()
        assertThat(MealConfirmationGate.isStuckNightHigh(150.0, -1.0, -1.0, 0.0, at(23, 30), paris)).isTrue()
    }

    /** Going down, already enough insulin, under 150 or in the day: the ceiling stays. */
    @Test
    fun theCeilingStaysWhenTheHighIsNotStuck() {
        assertThat(MealConfirmationGate.isStuckNightHigh(165.0, -2.0, -0.5, 1.0, at(4, 0), paris)).isFalse()
        assertThat(MealConfirmationGate.isStuckNightHigh(165.0, 0.0, -1.5, 1.0, at(4, 0), paris)).isFalse()
        assertThat(MealConfirmationGate.isStuckNightHigh(165.0, 0.0, 0.0, 2.5, at(4, 0), paris)).isFalse()
        assertThat(MealConfirmationGate.isStuckNightHigh(149.0, 0.0, 0.0, 1.0, at(4, 0), paris)).isFalse()
        assertThat(MealConfirmationGate.isStuckNightHigh(165.0, 0.0, 0.0, 1.0, at(9, 0), paris)).isFalse()
        assertThat(MealConfirmationGate.isStuckNightHigh(165.0, 0.0, 0.0, 1.0, at(15, 0), paris)).isFalse()
    }
}
