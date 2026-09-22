package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Locks the two triggers of the prompt, both written from measured mornings.
 */
class MealConfirmationGateTest {

    private val paris = ZoneId.of("Europe/Paris")

    private fun at(hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 9, 11, hour, minute, 0, 0, paris).toInstant().toEpochMilli()

    // -----------------------------------------------------------------------------------------
    // Second trigger — the morning of 2026-09-11
    // -----------------------------------------------------------------------------------------

    /**
     * 08:38 → 09:02: 1.05 U of SMB into a rise from 99 to 145 mg/dL with no carbs, while the phase
     * engine called it `STRESS_CORTISOL`. Nothing asked, and glucose fell to 72 with 1.77 U active.
     */
    @Test
    fun theMorningThatWasMissedNowTriggers() {
        assertThat(MealConfirmationGate.isAggressiveMealDosing(smbLast30MinU = 1.05, deltaMgdl = 4.0)).isTrue()
    }

    /** Just under the threshold the user chose: still quiet. */
    @Test
    fun justUnderTheThresholdStaysQuiet() {
        assertThat(MealConfirmationGate.isAggressiveMealDosing(smbLast30MinU = 0.79, deltaMgdl = 4.0)).isFalse()
    }

    /** Exactly at the threshold counts — the test is "under", not "at". */
    @Test
    fun exactlyAtTheThresholdTriggers() {
        assertThat(
            MealConfirmationGate.isAggressiveMealDosing(
                smbLast30MinU = MealConfirmationGate.AGGRESSIVE_SMB_THRESHOLD_U,
                deltaMgdl = 4.0,
            )
        ).isTrue()
    }

    /** A flat hyper being corrected is not a meal question, however much insulin it takes. */
    @Test
    fun aFlatHyperCorrectionIsNotAMealQuestion() {
        assertThat(MealConfirmationGate.isAggressiveMealDosing(smbLast30MinU = 2.0, deltaMgdl = 0.0)).isFalse()
    }

    /** Neither is a correction given while glucose falls. */
    @Test
    fun aFallingCorrectionIsNotAMealQuestion() {
        assertThat(MealConfirmationGate.isAggressiveMealDosing(smbLast30MinU = 2.0, deltaMgdl = -3.0)).isFalse()
    }

    // -----------------------------------------------------------------------------------------
    // Night window — 02:00 to 07:00, local
    // -----------------------------------------------------------------------------------------

    /** The three prompts of the night of 2026-09-11 expired unanswered; 03:53 is now silent. */
    @Test
    fun theNightPromptsAreSilenced() {
        assertThat(MealConfirmationGate.isQuietHour(at(3, 53), paris)).isTrue()
    }

    /** A late meal after drinks is a real case and the question is worth asking then. */
    @Test
    fun midnightStaysAwake() {
        assertThat(MealConfirmationGate.isQuietHour(at(0, 30), paris)).isFalse()
        assertThat(MealConfirmationGate.isQuietHour(at(1, 45), paris)).isFalse()
    }

    /** Start is inclusive, end is exclusive. */
    @Test
    fun theWindowEdges() {
        assertThat(MealConfirmationGate.isQuietHour(at(2, 0), paris)).isTrue()
        assertThat(MealConfirmationGate.isQuietHour(at(6, 59), paris)).isTrue()
        assertThat(MealConfirmationGate.isQuietHour(at(7, 0), paris)).isFalse()
    }

    /** The morning that matters is outside the window. */
    @Test
    fun theMorningIsAwake() {
        assertThat(MealConfirmationGate.isQuietHour(at(8, 57), paris)).isFalse()
    }

    // -----------------------------------------------------------------------------------------
    // Basal ceiling while "not eating" is in force
    // -----------------------------------------------------------------------------------------

    private val profileBasal = 0.85
    private val normalMax = 3.4

    /**
     * Answer on screen, BG 120 and rising, close to the max basal asked. The rise is not a meal,
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

    // -----------------------------------------------------------------------------------------
    // "A meal must be declared"
    // -----------------------------------------------------------------------------------------

    private fun prefs(deniedUntil: Long = 0L, mealArmedAt: Long = 0L): Preferences =
        mockk<Preferences>(relaxed = true).also {
            every { it.get(AimiLongKey.MealDeniedUntil) } returns deniedUntil
            every { it.get(AimiLongKey.MealKnownArmedAt) } returns mealArmedAt
        }

    /** Nothing eaten, nothing declared. With the switch on, the rise is not a meal. */
    @Test
    fun anUndeclaredRiseIsNotAMealWhenDeclarationIsRequired() {
        val now = at(17, 3)
        assertThat(MealConfirmationGate.isMealInterpretationBlocked(prefs(), now, requireDeclaration = true)).isTrue()
    }

    /** Switch off: silence keeps today's behaviour. */
    @Test
    fun anUndeclaredRiseIsLeftAloneWhenTheSwitchIsOff() {
        val now = at(17, 3)
        assertThat(MealConfirmationGate.isMealInterpretationBlocked(prefs(), now, requireDeclaration = false)).isFalse()
    }

    /** The eating note opens the meal, and its second wave 2.5 h later is covered. */
    @Test
    fun aDeclaredMealAndItsSecondWaveAreNotBlocked() {
        val p = prefs(mealArmedAt = at(12, 38))
        assertThat(MealConfirmationGate.isMealInterpretationBlocked(p, at(13, 0), requireDeclaration = true)).isFalse()
        assertThat(MealConfirmationGate.isMealInterpretationBlocked(p, at(15, 7), requireDeclaration = true)).isFalse()
    }

    /** A slow meal: the third rise at t+3h43 is still the same meal. */
    @Test
    fun aSlowMealIsCoveredForFourHours() {
        val p = prefs(mealArmedAt = at(14, 44))
        assertThat(MealConfirmationGate.isMealInterpretationBlocked(p, at(18, 27), requireDeclaration = true)).isFalse()
    }

    /** t+4h25 after the note, nothing eaten: not a meal any more. */
    @Test
    fun theRiseAfterFourHoursIsNotAMeal() {
        val p = prefs(mealArmedAt = at(12, 38))
        assertThat(MealConfirmationGate.isMealInterpretationBlocked(p, at(17, 3), requireDeclaration = true)).isTrue()
    }

    /** An explicit "I am not eating" blocks with or without the switch. */
    @Test
    fun theAnswerBlocksWithOrWithoutTheSwitch() {
        val now = at(9, 13)
        val p = prefs(deniedUntil = at(10, 1))
        assertThat(MealConfirmationGate.isMealInterpretationBlocked(p, now, requireDeclaration = false)).isTrue()
        assertThat(MealConfirmationGate.isMealInterpretationBlocked(p, now, requireDeclaration = true)).isTrue()
    }
}
