package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import kotlin.math.max

/**
 * The small meal button: an AAPS Automation that writes a note "petit".
 *
 * ## Why this exists
 *
 * The user does not enter carbs. When a meal is declared, the loop cannot tell a small meal (a salad
 * with some bread) from a usual one: it sees glucose go up and doses it like a usual meal, and it can
 * reach the normal max IOB. That is far too much insulin for a small meal.
 *
 * ## What it does
 *
 * The note opens the meal window like "eating". For the whole meal (first wave, second wave, all of
 * it), the insulin on board stays at or under [MAX_IOB_U]:
 * - the max IOB of the tick is lowered to [MAX_IOB_U];
 * - at the end of the tick, the SMB is cut so that IOB + SMB stays under [MAX_IOB_U], and once the
 *   limit is reached the basal goes back to the scheduled rate.
 * It never adds insulin. It wins over the big meal rules.
 *
 * Pure: no clock, no preference. The caller passes the state and owns the dose.
 */
object SmallMealGate {

    /** Max insulin on board during a small meal, in U. */
    const val MAX_IOB_U = 4.0

    /** A "petit" note this long before the meal window opened still belongs to this meal. */
    const val NOTE_BEFORE_ARM_MIN = 10L

    private const val MIN_MS = 60_000L

    /** True when the note text is the small meal button. Exact word, so "petit dej" does not count. */
    fun isSmallMealNote(note: String?): Boolean =
        note?.trim()?.equals("petit", ignoreCase = true) == true

    /**
     * True while a declared meal runs and the newest "petit" note belongs to it.
     *
     * @param armedAtMs when the current meal window opened, null when no meal is declared.
     * @param smallMealNoteMs newest "petit" note, 0 when there is none.
     */
    fun isActive(armedAtMs: Long?, smallMealNoteMs: Long): Boolean =
        armedAtMs != null && smallMealNoteMs > 0L && smallMealNoteMs >= armedAtMs - NOTE_BEFORE_ARM_MIN * MIN_MS

    /** The SMB allowed on this tick: what is left under [MAX_IOB_U], never more than asked. */
    fun allowedSmbU(smbU: Double, iobU: Double): Double {
        if (smbU <= 0.0) return smbU
        if (!iobU.isFinite()) return 0.0
        return minOf(smbU, max(0.0, MAX_IOB_U - iobU))
    }

    /** True when the basal must go back to the scheduled rate: the limit is reached. */
    fun basalCapped(iobU: Double): Boolean = !iobU.isFinite() || iobU >= MAX_IOB_U
}
