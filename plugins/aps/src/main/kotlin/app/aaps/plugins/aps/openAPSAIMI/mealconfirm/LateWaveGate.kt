package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import kotlin.math.max
import kotlin.math.min

/**
 * A late second wave that starts suddenly after a long flat: add nothing once the insulin on board
 * is already at [IOB_CAP_U].
 *
 * ## Why this exists
 *
 * Once a second rise has proven itself ([SecondWaveGate]), the loop doses it like a brand new meal:
 * rise exemption, the max basal and the largest SMB on every tick. When that rise comes late and
 * suddenly, after the glucose sat flat near its trough, it is usually the end of the meal arriving
 * at once: it peaks within half an hour of the proof, and everything the loop sent lands on the way
 * down. In a field log a lunch went flat at 130 for 40 minutes, rose +35 in 20 minutes 2 h 10 after
 * the meal, got about 5.6 U in 35 minutes (insulin on board 6.8 U), peaked 30 minutes after the
 * proof and fell to 59 within the hour.
 *
 * Replayed on 26 second waves of declared meals: capping every proven wave cost 36 to 55 U over the
 * month for one low avoided, because the early waves and the slow ones needed their insulin. This
 * shape alone (late, after a flat, sudden) held 12 of the 26 waves and both lows behind a wave, and
 * the cap below would have kept about 10 U back in all. Thresholds were set on that same log, so
 * the rule is a shadow first.
 *
 * ## The shape
 *
 * Read on the glucose since the meal was declared:
 * - the first wave peaked and came down [DESCENT_MGDL] from its peak (that low point is the trough);
 * - a rise then climbed [PROOF_MGDL] above the trough (the proof), at least [MIN_DELAY_MIN] after
 *   the declaration and within [MAX_PROOF_MIN];
 * - before rising, glucose stayed within [FLAT_BAND_MGDL] of the trough for [MIN_FLAT_MIN] or more;
 * - the rise went from [RISE_START_MGDL] above the trough to the proof in [MAX_RISE_MIN] or less.
 *
 * ## What it does
 *
 * For [CAP_WINDOW_MIN] after the proof, the loop's own insulin may not take the insulin on board above
 * [IOB_CAP_U]: SMB is trimmed to what is left under it, and at or above it the basal stays at the
 * scheduled rate. Under the cap nothing changes. The user's own bolus is never touched, and pressing
 * "eating" again starts the meal over, so a new declaration always wins.
 *
 * Pure: no clock, no preference. The caller passes the glucose and owns the dose.
 */
object LateWaveGate {

    const val DESCENT_MGDL = 30.0
    const val PROOF_MGDL = 35.0
    const val MIN_DELAY_MIN = 120L
    const val MAX_PROOF_MIN = 300L
    const val FLAT_BAND_MGDL = 20.0
    const val MIN_FLAT_MIN = 30L
    const val RISE_START_MGDL = 10.0
    const val MAX_RISE_MIN = 30L
    const val CAP_WINDOW_MIN = 120L
    const val IOB_CAP_U = 4.0

    private const val MIN_MS = 60_000L

    /** One glucose reading, in ms and mg/dL. */
    data class Reading(val timestamp: Long, val mgdl: Double)

    /** A late, flat-then-sudden second wave, as found on the meal's glucose. */
    data class Wave(
        val proofAtMs: Long,
        val troughMgdl: Double,
        /** Minutes from the declaration to the proof. */
        val delayMin: Long,
        /** Minutes glucose stayed near the trough before rising. */
        val flatMin: Long,
        /** Minutes from the start of the rise to the proof. */
        val riseMin: Long,
    )

    data class Decision(
        val active: Boolean,
        val wave: Wave? = null,
        val minutesSinceProof: Long = 0L,
        val iobU: Double = 0.0,
        /** The SMB the tick may send. Equal to the proposed SMB when nothing is cut. */
        val smbAllowedU: Double = 0.0,
        /** The basal the tick may use, or null when the rate is not changed. */
        val rateAllowedUph: Double? = null,
    ) {
        fun cuts(proposedSmbU: Double, proposedRateUph: Double?): Boolean =
            smbAllowedU < proposedSmbU || (rateAllowedUph != null && proposedRateUph != null && rateAllowedUph < proposedRateUph)

        companion object {
            val INACTIVE = Decision(active = false)
        }
    }

    /**
     * Finds the wave on the glucose since the declaration, or null when this meal has none of that
     * shape. [readings] may come in any order; readings before [armedAtMs] are ignored.
     */
    fun findWave(armedAtMs: Long, readings: List<Reading>): Wave? {
        val meal = readings
            .filter { it.timestamp >= armedAtMs && it.timestamp <= armedAtMs + MAX_PROOF_MIN * MIN_MS && it.mgdl.isFinite() && it.mgdl > 39.0 }
            .sortedBy { it.timestamp }
        if (meal.isEmpty()) return null
        var peak = meal.first().mgdl
        var trough: Double? = null
        var proofIndex = -1
        for ((i, r) in meal.withIndex()) {
            val t = trough
            if (t == null) {
                if (r.mgdl > peak) peak = r.mgdl
                else if (r.mgdl <= peak - DESCENT_MGDL) trough = r.mgdl
            } else if (r.mgdl < t) {
                trough = r.mgdl
            } else if (r.mgdl >= t + PROOF_MGDL) {
                proofIndex = i
                break
            }
        }
        val troughMgdl = trough ?: return null
        if (proofIndex < 0) return null
        val proofAt = meal[proofIndex].timestamp
        val delayMin = (proofAt - armedAtMs) / MIN_MS
        if (delayMin < MIN_DELAY_MIN) return null

        val before = meal.subList(0, proofIndex).asReversed()
        val riseStart = before.firstOrNull { it.mgdl <= troughMgdl + RISE_START_MGDL }?.timestamp ?: return null
        // The flat: the readings near the trough just before the rise, walking back until one is above it.
        var flatStart = proofAt
        var seen = false
        for (r in before) {
            if (r.mgdl <= troughMgdl + FLAT_BAND_MGDL) {
                seen = true
                flatStart = r.timestamp
            } else if (seen) break
        }
        val flatMin = (riseStart - flatStart) / MIN_MS
        val riseMin = (proofAt - riseStart) / MIN_MS
        if (flatMin < MIN_FLAT_MIN || riseMin > MAX_RISE_MIN) return null
        return Wave(proofAt, troughMgdl, delayMin, flatMin, riseMin)
    }

    /**
     * @param armedAtMs when the meal was declared ([MealKnownGate]), 0 when there is none.
     * @param iobU insulin on board now.
     * @param proposedSmbU what the tick wants to send now.
     * @param proposedRateUph the basal the tick will run, or null when it sets none.
     * @param scheduledUph the profile basal now.
     */
    fun evaluate(
        now: Long,
        armedAtMs: Long,
        readings: List<Reading>,
        iobU: Double,
        proposedSmbU: Double,
        proposedRateUph: Double?,
        scheduledUph: Double,
    ): Decision {
        if (armedAtMs <= 0L || now < armedAtMs || !iobU.isFinite()) return Decision.INACTIVE
        val wave = findWave(armedAtMs, readings) ?: return Decision.INACTIVE
        if (now < wave.proofAtMs) return Decision.INACTIVE
        val sinceProof = (now - wave.proofAtMs) / MIN_MS
        if (sinceProof >= CAP_WINDOW_MIN) return Decision.INACTIVE
        val proposed = if (proposedSmbU.isFinite()) max(proposedSmbU, 0.0) else 0.0
        val left = max(IOB_CAP_U - iobU, 0.0)
        val rateAllowed = if (iobU >= IOB_CAP_U && proposedRateUph != null && proposedRateUph.isFinite() && scheduledUph.isFinite()) {
            min(proposedRateUph, scheduledUph)
        } else {
            null
        }
        return Decision(
            active = true,
            wave = wave,
            minutesSinceProof = sinceProof,
            iobU = iobU,
            smbAllowedU = min(proposed, left),
            rateAllowedUph = rateAllowed,
        )
    }
}
