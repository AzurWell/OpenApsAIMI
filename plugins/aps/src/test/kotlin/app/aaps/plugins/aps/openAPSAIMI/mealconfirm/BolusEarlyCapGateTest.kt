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
 * Locks the cap on what the loop adds in the first 30 minutes after the user's own meal bolus.
 */
class BolusEarlyCapGateTest {

    private val min = 60_000L
    private val t0 = 1_790_000_000_000L

    private fun evaluate(
        nowMin: Long,
        smbSince: Double = 0.0,
        smb: Double = 0.5,
        rate: Double? = 4.5,
        mealKnown: Boolean = true,
        bolusAt: Long = t0,
    ) = BolusEarlyCapGate.evaluate(
        now = t0 + nowMin * min,
        bolusAtMs = bolusAt,
        mealKnown = mealKnown,
        smbSinceBolusU = smbSince,
        proposedSmbU = smb,
        proposedRateUph = rate,
        scheduledUph = 0.8,
    )

    @Test
    fun noBolusMeansNoCap() {
        assertThat(evaluate(nowMin = 5, bolusAt = 0L).active).isFalse()
    }

    @Test
    fun theCapEndsAfterThirtyMinutes() {
        assertThat(evaluate(nowMin = 29).active).isTrue()
        assertThat(evaluate(nowMin = 30).active).isFalse()
    }

    /** A "correction" note closed the meal: the bolus was not a meal bolus. */
    @Test
    fun aClosedMealWindowMeansNoCap() {
        assertThat(evaluate(nowMin = 5, mealKnown = false).active).isFalse()
    }

    @Test
    fun smbIsLimitedToWhatIsLeftOfTheBudget() {
        val d = evaluate(nowMin = 15, smbSince = 0.6, smb = 0.7)
        assertThat(d.smbAllowedU).isWithin(1e-9).of(0.4)
        assertThat(d.cuts(0.7, 0.8)).isTrue()
    }

    @Test
    fun aSpentBudgetAllowsNoMoreSmb() {
        assertThat(evaluate(nowMin = 20, smbSince = 1.2, smb = 0.5).smbAllowedU).isEqualTo(0.0)
    }

    @Test
    fun aSmallSmbInsideTheBudgetIsNotCut() {
        val d = evaluate(nowMin = 10, smbSince = 0.2, smb = 0.3, rate = 0.5)
        assertThat(d.smbAllowedU).isWithin(1e-9).of(0.3)
        assertThat(d.cuts(0.3, 0.5)).isFalse()
    }

    @Test
    fun theBasalStaysAtTheProfileRate() {
        assertThat(evaluate(nowMin = 5, rate = 4.5).rateAllowedUph).isEqualTo(0.8)
        assertThat(evaluate(nowMin = 5, rate = 0.0).rateAllowedUph).isEqualTo(0.0)
        assertThat(evaluate(nowMin = 5, rate = null).rateAllowedUph).isNull()
    }

    // -----------------------------------------------------------------------------------------
    // Where the bolus time comes from (MealKnownGate)
    // -----------------------------------------------------------------------------------------

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
            every { it.get(IntKey.OApsAIMIDeclaredMealHorizonMin) } returns 0
        }
    }

    private fun bolus(atMs: Long, units: Double = 3.0) =
        BS(timestamp = atMs, amount = units, type = BS.Type.NORMAL, iCfg = ICfg(insulinLabel = "test", peak = 60, dia = 5.0, concentration = 1.0))

    @Test
    fun aMealBolusRecordsItsTime() {
        val p = prefs()
        MealKnownGate.armFromManualBolus(p, t0 + min, listOf(bolus(t0)), 120.0, 1.0)
        assertThat(MealKnownGate.bolusAtMs(p)).isEqualTo(t0)
    }

    /** The eating note alone is a light meal: nothing to cap. */
    @Test
    fun theEatingNoteAloneRecordsNoBolus() {
        val p = prefs()
        MealKnownGate.armFromNote(p, t0, t0)
        assertThat(MealKnownGate.bolusAtMs(p)).isEqualTo(0L)
    }

    /** The button pressed after the bolus: the bolus is still acting, the cap stays. */
    @Test
    fun anEatingNoteAfterTheBolusKeepsTheBolusTime() {
        val p = prefs()
        MealKnownGate.armFromManualBolus(p, t0 + min, listOf(bolus(t0)), 120.0, 1.0)
        MealKnownGate.armFromNote(p, t0 + 5 * min, t0 + 5 * min)
        assertThat(MealKnownGate.bolusAtMs(p)).isEqualTo(t0)
    }

    @Test
    fun aCorrectionNoteClearsTheBolusTime() {
        val p = prefs()
        MealKnownGate.armFromManualBolus(p, t0 + min, listOf(bolus(t0)), 120.0, 1.0)
        MealKnownGate.applyCorrectionNote(p, t0 + 5 * min)
        assertThat(MealKnownGate.bolusAtMs(p)).isEqualTo(0L)
    }

    /** A correction at high glucose opens no meal, so it records nothing. */
    @Test
    fun aHighCorrectionBolusRecordsNothing() {
        val p = prefs()
        MealKnownGate.armFromManualBolus(p, t0 + min, listOf(bolus(t0, 2.0)), 214.0, 2.0)
        assertThat(MealKnownGate.bolusAtMs(p)).isEqualTo(0L)
    }
}
