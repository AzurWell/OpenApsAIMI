package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the shape of a late, flat-then-sudden second wave, and the 4 U cap that follows it.
 */
class LateWaveGateTest {

    private val min = 60_000L
    private val t0 = 1_790_000_000_000L

    /** Glucose every 5 minutes from [startMin], in mg/dL. */
    private fun curve(startMin: Long, vararg mgdl: Int) =
        mgdl.mapIndexed { i, v -> LateWaveGate.Reading(t0 + (startMin + i * 5L) * min, v.toDouble()) }

    /**
     * A lunch: dip after the bolus, first wave to 178, down to a flat at 129-136 for about an hour,
     * then +35 in 15 minutes, proven at t+135 min.
     */
    private val lunch = curve(
        0,
        120, 110, 100, 99, 105, 120, 140, 160, 175, 178, // 0-45
        170, 160, 150, 146, 140, 136, 133, 131, 130, 129, // 50-95
        130, 131, 131, 132, 136, 145, 155, 165, 180, 200, // 100-145
    )

    private fun evaluate(
        nowMin: Long,
        iob: Double,
        smb: Double = 0.7,
        rate: Double? = 4.5,
        readings: List<LateWaveGate.Reading> = lunch,
        armedAt: Long = t0,
    ) = LateWaveGate.evaluate(
        now = t0 + nowMin * min,
        armedAtMs = armedAt,
        readings = readings,
        iobU = iob,
        proposedSmbU = smb,
        proposedRateUph = rate,
        scheduledUph = 0.8,
    )

    @Test
    fun findsTheLateFlatSuddenWave() {
        val wave = LateWaveGate.findWave(t0, lunch)
        assertThat(wave).isNotNull()
        assertThat(wave!!.proofAtMs).isEqualTo(t0 + 135 * min)
        assertThat(wave.troughMgdl).isEqualTo(129.0)
        assertThat(wave.delayMin).isEqualTo(135L)
        assertThat(wave.riseMin).isEqualTo(15L)
        assertThat(wave.flatMin).isAtLeast(LateWaveGate.MIN_FLAT_MIN)
    }

    @Test
    fun atOrAboveTheCapNothingIsAdded() {
        val d = evaluate(nowMin = 150, iob = 5.0)
        assertThat(d.active).isTrue()
        assertThat(d.smbAllowedU).isEqualTo(0.0)
        assertThat(d.rateAllowedUph).isEqualTo(0.8)
        assertThat(d.cuts(0.7, 4.5)).isTrue()
    }

    @Test
    fun underTheCapTheSmbIsTrimmedAndTheBasalLeftAlone() {
        val d = evaluate(nowMin = 140, iob = 3.6)
        assertThat(d.smbAllowedU).isWithin(1e-9).of(0.4)
        assertThat(d.rateAllowedUph).isNull()
    }

    @Test
    fun wellUnderTheCapNothingIsCut() {
        val d = evaluate(nowMin = 140, iob = 2.0, smb = 0.5)
        assertThat(d.active).isTrue()
        assertThat(d.cuts(0.5, 4.5)).isFalse()
    }

    @Test
    fun beforeTheProofNothingHappens() {
        assertThat(evaluate(nowMin = 130, iob = 5.0, readings = lunch.filter { it.timestamp <= t0 + 130 * min }).active).isFalse()
    }

    @Test
    fun theCapEndsTwoHoursAfterTheProof() {
        assertThat(evaluate(nowMin = 135 + LateWaveGate.CAP_WINDOW_MIN - 1, iob = 5.0).active).isTrue()
        assertThat(evaluate(nowMin = 135 + LateWaveGate.CAP_WINDOW_MIN, iob = 5.0).active).isFalse()
    }

    @Test
    fun anEarlyWaveIsNotConcerned() {
        // Same glucose, but the meal was declared 30 min later: the proof comes at t+105 min.
        assertThat(LateWaveGate.findWave(t0 + 30 * min, lunch)).isNull()
    }

    @Test
    fun aSlowRiseIsNotConcerned() {
        val slow = curve(
            0,
            120, 110, 100, 99, 105, 120, 140, 160, 175, 178,
            170, 160, 150, 146, 140, 136, 133, 131, 130, 129,
            130, 131, 131, 132, 136, 140, 144, 148, 152, 156, 160, 165, // +35 in 35 min
        )
        assertThat(LateWaveGate.findWave(t0, slow)).isNull()
    }

    @Test
    fun aRiseStraightOutOfTheTroughIsNotConcerned() {
        // No flat: glucose comes down slowly to 129 at t+130 and climbs right away.
        val noFlat = curve(
            0,
            120, 110, 100, 99, 105, 120, 140, 160, 175, 178,
            176, 174, 172, 170, 168, 166, 164, 162, 160, 158,
            155, 152, 149, 146, 143, 140, 135, 129, 145, 165,
        )
        assertThat(LateWaveGate.findWave(t0, noFlat)).isNull()
    }

    @Test
    fun noDeclarationNoCap() {
        assertThat(evaluate(nowMin = 150, iob = 5.0, armedAt = 0L).active).isFalse()
    }
}
