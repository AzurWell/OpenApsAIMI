package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the stuck high rule: no meal, 200 or more for an hour and not coming down, IOB under 5 U
 * -> at least 0.5 U of SMB per 15 minutes, 3 U at most per 2 hours, never past the max IOB.
 */
class StuckHighGateTest {

    private val min = 60_000L
    private val now = 1_790_000_000_000L

    /** Glucose every 5 minutes, the last reading now. */
    private fun curve(vararg mgdl: Int) =
        mgdl.mapIndexed { i, v -> StuckHighGate.Reading(now - (mgdl.size - 1 - i) * 5L * min, v.toDouble()) }

    /** 65 minutes of a flat 250. */
    private val flatHigh = curve(*IntArray(14) { 250 })

    private fun evaluate(
        readings: List<StuckHighGate.Reading> = flatHigh,
        meal: Boolean = false,
        activity: Boolean = false,
        iob: Double = 3.0,
        maxIob: Double = 7.0,
        proposed: Double = 0.1,
        smbs: List<Pair<Long, Double>> = emptyList(),
    ) = StuckHighGate.evaluate(
        now = now,
        mealDeclared = meal,
        activity = activity,
        readings = readings,
        iobU = iob,
        maxIobU = maxIob,
        proposedSmbU = proposed,
        smbTimesAndUnits = smbs,
    )

    @Test
    fun `flat high for an hour with no meal gives half a unit`() {
        val d = evaluate()
        assertThat(d.stuck).isTrue()
        assertThat(d.smbTargetU).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `slow rise counts as stuck`() {
        val d = evaluate(readings = curve(205, 210, 215, 220, 225, 230, 235, 240, 245, 250, 255, 260, 265, 270))
        assertThat(d.smbTargetU).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `a declared meal or an activity does nothing`() {
        assertThat(evaluate(meal = true).stuck).isFalse()
        assertThat(evaluate(activity = true).stuck).isFalse()
    }

    @Test
    fun `less than an hour above 200 does nothing`() {
        val d = evaluate(readings = curve(190, 195, 205, 210, 215, 220, 225, 230, 235, 240, 245, 250, 255, 260))
        assertThat(d.stuck).isFalse()
    }

    @Test
    fun `a hole in the curve does nothing`() {
        val d = evaluate(readings = flatHigh.filterIndexed { i, _ -> i !in 4..7 })
        assertThat(d.stuck).isFalse()
    }

    @Test
    fun `coming down does nothing`() {
        // -20 over 30 minutes.
        val d = evaluate(readings = curve(270, 270, 270, 270, 270, 270, 270, 270, 266, 263, 260, 257, 253, 250))
        assertThat(d.stuck).isFalse()
        // -10 over the last 15 minutes.
        assertThat(evaluate(readings = curve(260, 260, 260, 260, 260, 260, 260, 260, 260, 260, 260, 257, 253, 250)).stuck).isFalse()
    }

    @Test
    fun `iob of 5 or more adds nothing`() {
        val d = evaluate(iob = 5.0)
        assertThat(d.stuck).isTrue()
        assertThat(d.smbTargetU).isEqualTo(0.0)
    }

    @Test
    fun `smb already given in the last 15 minutes counts`() {
        assertThat(evaluate(smbs = listOf(now - 5 * min to 0.2)).smbTargetU).isWithin(1e-9).of(0.3)
        assertThat(evaluate(smbs = listOf(now - 5 * min to 0.5)).smbTargetU).isEqualTo(0.0)
        // Older than 15 minutes: a new half unit.
        assertThat(evaluate(smbs = listOf(now - 20 * min to 0.5)).smbTargetU).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `no more than 3 units over 2 hours`() {
        val given = (1..6).map { now - (20L + it * 15L) * min to 0.45 } // 2.7 U
        assertThat(evaluate(smbs = given).smbTargetU).isWithin(1e-9).of(0.3)
        val full = (1..6).map { now - (20L + it * 15L) * min to 0.5 } // 3 U
        assertThat(evaluate(smbs = full).smbTargetU).isEqualTo(0.0)
    }

    @Test
    fun `never past the max iob`() {
        assertThat(evaluate(iob = 4.8, maxIob = 5.0).smbTargetU).isWithin(1e-9).of(0.2)
    }

    @Test
    fun `never lowers a bigger proposed dose`() {
        assertThat(evaluate(proposed = 0.7).smbTargetU).isEqualTo(0.0)
    }

    @Test
    fun `under 200 now does nothing`() {
        val d = evaluate(readings = curve(*IntArray(13) { 250 }, 199))
        assertThat(d.stuck).isFalse()
    }
}
