package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the shape that separates a second rise from a meal that never came down.
 *
 * Both cases are real meals of the user this gate was written for.
 */
class SecondWaveGateTest {

    /** Pasta, 2026-09-11: lunch at 14:46, peak 123, trough 72 at 15:47, back to 144 at 17:02. */
    @Test
    fun thePastaSecondRiseIsOne() {
        assertThat(SecondWaveGate.looksLikeSecondRise(bgMgdl = 144.0, troughMgdl = 72.0)).isTrue()
    }

    /** Baguette with a lot of cheese: 250 mg/dL for six hours, never coming down. */
    @Test
    fun theBaguetteWithCheesePlateauIsNotOne() {
        assertThat(SecondWaveGate.looksLikeSecondRise(bgMgdl = 250.0, troughMgdl = 210.0)).isFalse()
    }

    /** Even a deep plateau that dips a little stays out while the dip never reaches normal. */
    @Test
    fun aHighPlateauThatOnlyDipsIsNotOne() {
        assertThat(SecondWaveGate.looksLikeSecondRise(bgMgdl = 250.0, troughMgdl = 160.0)).isFalse()
    }

    /** Came back down properly, but has barely moved since: not a rise yet. */
    @Test
    fun aTroughWithoutARiseIsNotOne() {
        assertThat(SecondWaveGate.looksLikeSecondRise(bgMgdl = 95.0, troughMgdl = 80.0)).isFalse()
    }

    /** The two edges, exactly on the limits. */
    @Test
    fun theEdges() {
        assertThat(
            SecondWaveGate.looksLikeSecondRise(
                bgMgdl = SecondWaveGate.TROUGH_MAX_MGDL + SecondWaveGate.REBOUND_MIN_MGDL,
                troughMgdl = SecondWaveGate.TROUGH_MAX_MGDL,
            )
        ).isTrue()
        assertThat(
            SecondWaveGate.looksLikeSecondRise(
                bgMgdl = 300.0,
                troughMgdl = SecondWaveGate.TROUGH_MAX_MGDL + 1.0,
            )
        ).isFalse()
    }
}
