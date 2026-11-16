package app.aaps.plugins.aps.openAPSAIMI.mealconfirm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the small meal button: the "petit" note keeps the whole meal under 4 U on board.
 */
class SmallMealGateTest {

    private val min = 60_000L
    private val t0 = 1_790_000_000_000L

    @Test
    fun `only the exact word is the button`() {
        assertThat(SmallMealGate.isSmallMealNote("petit")).isTrue()
        assertThat(SmallMealGate.isSmallMealNote(" Petit ")).isTrue()
        assertThat(SmallMealGate.isSmallMealNote("petit dej")).isFalse()
        assertThat(SmallMealGate.isSmallMealNote("eating")).isFalse()
        assertThat(SmallMealGate.isSmallMealNote(null)).isFalse()
    }

    @Test
    fun `active only while a meal runs and the note belongs to it`() {
        assertThat(SmallMealGate.isActive(t0, t0)).isTrue()
        assertThat(SmallMealGate.isActive(t0, t0 - 5 * min)).isTrue()
        assertThat(SmallMealGate.isActive(t0, t0 - 30 * min)).isFalse()
        assertThat(SmallMealGate.isActive(null, t0)).isFalse()
        assertThat(SmallMealGate.isActive(t0, 0L)).isFalse()
    }

    @Test
    fun `SMB is cut to what is left under 4 U`() {
        assertThat(SmallMealGate.allowedSmbU(0.5, 2.0)).isWithin(1e-9).of(0.5)
        assertThat(SmallMealGate.allowedSmbU(0.7, 3.6)).isWithin(1e-9).of(0.4)
        assertThat(SmallMealGate.allowedSmbU(0.5, 4.2)).isWithin(1e-9).of(0.0)
        assertThat(SmallMealGate.allowedSmbU(0.0, 5.0)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `basal goes back to the schedule at 4 U`() {
        assertThat(SmallMealGate.basalCapped(3.9)).isFalse()
        assertThat(SmallMealGate.basalCapped(4.0)).isTrue()
    }
}
