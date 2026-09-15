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

    // -----------------------------------------------------------------------------------------
    // Damping factor — by meal age
    // -----------------------------------------------------------------------------------------

    /** Before the gate opens, nothing is taken. */
    @Test
    fun nothingIsDampedBeforeTheGateOpens() {
        assertThat(SecondWaveGate.dampingFactor(60)).isEqualTo(1.0)
        assertThat(SecondWaveGate.dampingFactor(SecondWaveGate.MIN_ELAPSED_MIN)).isEqualTo(1.0)
    }

    /** At the far end of the meal, only the floor survives. */
    @Test
    fun theOldestTailKeepsOnlyTheFloor() {
        assertThat(SecondWaveGate.dampingFactor(SecondWaveGate.MAX_ELAPSED_MIN))
            .isEqualTo(SecondWaveGate.MIN_FACTOR)
        assertThat(SecondWaveGate.dampingFactor(SecondWaveGate.MAX_ELAPSED_MIN + 120))
            .isEqualTo(SecondWaveGate.MIN_FACTOR)
    }

    /** It only ever goes down with age, and never past the floor. */
    @Test
    fun theFactorDecreasesMonotonically() {
        var previous = 1.1
        for (minutes in 0L..(SecondWaveGate.MAX_ELAPSED_MIN + 60) step 15) {
            val f = SecondWaveGate.dampingFactor(minutes)
            assertThat(f).isAtMost(previous)
            assertThat(f).isAtLeast(SecondWaveGate.MIN_FACTOR)
            assertThat(f).isAtMost(1.0)
            previous = f
        }
    }

    /**
     * The tail of 2026-09-14: the heaviest dosing ran from about t+2h30 to t+5h30 and put 6.65 U
     * into a few grams of bread. Roughly half of that is what the damper is aiming to remove.
     */
    @Test
    fun theBaguetteTailIsHalved() {
        val early = SecondWaveGate.dampingFactor(150)
        val late = SecondWaveGate.dampingFactor(330)
        assertThat(early).isGreaterThan(late)
        assertThat((early + late) / 2.0).isLessThan(0.75)
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
