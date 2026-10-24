package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the cap on what the loop adds on a second rise that has proven itself.
 */
class SecondWaveRateGateTest {

    private fun evaluate(
        proven: Boolean = true,
        bg: Double = 140.0,
        smbLastWindow: Double = 0.0,
        smb: Double = 0.6,
        rate: Double? = 4.5,
    ) = SecondWaveRateGate.evaluate(
        proven = proven,
        bgMgdl = bg,
        smbLastWindowU = smbLastWindow,
        proposedSmbU = smb,
        proposedRateUph = rate,
        scheduledUph = 0.9,
    )

    @Test
    fun aRiseNotProvenIsNotConcerned() {
        assertThat(evaluate(proven = false).active).isFalse()
    }

    /** A big wave is dosed as usual from 200 up. */
    @Test
    fun nothingIsLimitedFrom200Up() {
        assertThat(evaluate(bg = 199.0).active).isTrue()
        assertThat(evaluate(bg = 200.0).active).isFalse()
    }

    @Test
    fun smbIsLimitedToWhatIsLeftOfTheBudget() {
        val d = evaluate(smbLastWindow = 0.6, smb = 0.6)
        assertThat(d.smbAllowedU).isWithin(1e-9).of(0.4)
        assertThat(d.cuts(0.6, 0.9)).isTrue()
    }

    @Test
    fun aSpentBudgetAllowsNoMoreSmb() {
        assertThat(evaluate(smbLastWindow = 1.1, smb = 0.6).smbAllowedU).isEqualTo(0.0)
    }

    @Test
    fun aSmallSmbInsideTheBudgetIsNotCut() {
        val d = evaluate(smbLastWindow = 0.3, smb = 0.5, rate = 0.5)
        assertThat(d.smbAllowedU).isWithin(1e-9).of(0.5)
        assertThat(d.cuts(0.5, 0.5)).isFalse()
    }

    @Test
    fun theBasalStaysAtTheProfileRate() {
        assertThat(evaluate(rate = 4.5).rateAllowedUph).isEqualTo(0.9)
        assertThat(evaluate(rate = 0.0).rateAllowedUph).isEqualTo(0.0)
        assertThat(evaluate(rate = null).rateAllowedUph).isNull()
    }
}
