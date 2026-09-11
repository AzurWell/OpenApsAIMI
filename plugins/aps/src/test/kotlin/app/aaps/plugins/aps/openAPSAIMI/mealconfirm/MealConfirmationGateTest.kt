package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import com.google.common.truth.Truth.assertThat
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
}
