package app.aaps.plugins.aps.openAPSAIMI.smb

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the curve that decides how much of a bolus survives when the loop's own insulin-only
 * projection already lands below target.
 */
class PredictionGapDamperTest {

    /** A projection at or above target changes nothing. */
    @Test
    fun aProjectionAtTargetIsNotDamped() {
        assertThat(PredictionGapDamper.factorForGap(0.0)).isEqualTo(1.0)
        assertThat(PredictionGapDamper.factorForGap(-40.0)).isEqualTo(1.0)
    }

    /** Neither does a projection just under it — that is ordinary dosing. */
    @Test
    fun aSmallGapIsNotDamped() {
        assertThat(PredictionGapDamper.factorForGap(PredictionGapDamper.GAP_FREE_MGDL)).isEqualTo(1.0)
    }

    /**
     * 2026-09-14, 17:08: `IOBpredBG 39` against a target of 104, and 0.70 U went out. Gap 65, which
     * lands in the damped band and takes roughly half of it away.
     */
    @Test
    fun theEpisodeOf14SeptemberIsDamped() {
        val factor = PredictionGapDamper.factorForGap(104.0 - 39.0)
        assertThat(factor).isLessThan(0.55)
        assertThat(factor).isGreaterThan(PredictionGapDamper.MIN_FACTOR)
        assertThat(0.70 * factor).isLessThan(0.40)
    }

    /** Past the far end, only the floor survives — and it never goes below it. */
    @Test
    fun theFloorHolds() {
        assertThat(PredictionGapDamper.factorForGap(PredictionGapDamper.GAP_FULL_MGDL))
            .isEqualTo(PredictionGapDamper.MIN_FACTOR)
        assertThat(PredictionGapDamper.factorForGap(300.0)).isEqualTo(PredictionGapDamper.MIN_FACTOR)
    }

    /** Monotonic, and always inside [MIN_FACTOR, 1]. */
    @Test
    fun theCurveOnlyGoesDown() {
        var previous = 1.1
        var gap = -20.0
        while (gap <= 200.0) {
            val f = PredictionGapDamper.factorForGap(gap)
            assertThat(f).isAtMost(previous)
            assertThat(f).isAtLeast(PredictionGapDamper.MIN_FACTOR)
            assertThat(f).isAtMost(1.0)
            previous = f
            gap += 5.0
        }
    }

    /** A missing projection must never damp anything. */
    @Test
    fun aMissingProjectionIsInactive() {
        assertThat(PredictionGapDamper.evaluate(null, 100.0).active).isFalse()
        assertThat(PredictionGapDamper.evaluate(Double.NaN, 100.0).active).isFalse()
        assertThat(PredictionGapDamper.evaluate(39.0, 0.0).active).isFalse()
    }

    /** And a real one carries its numbers into the log line. */
    @Test
    fun theVerdictSaysWhy() {
        val v = PredictionGapDamper.evaluate(39.0, 104.0)
        assertThat(v.active).isTrue()
        assertThat(v.reason).contains("iobPred=39")
        assertThat(v.reason).contains("target=104")
        assertThat(v.reason).contains("gap=65")
    }
}
