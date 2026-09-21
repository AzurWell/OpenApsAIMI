package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.AutoDriveGater.GateKind
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextRepository
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The rise after a rescue: sugar taken before BG reached 75, nothing declared, then BG rises at
 * +6 per 5 min under 120. With the old line, V3 engaged and dosed into the sugar. The line is now a
 * parameter, and the caller raises it to the LGS threshold when nothing is declared.
 */
class AutoDriveGaterRescueRiseTest {

    private lateinit var gater: AutoDriveGater

    @BeforeEach
    fun setUp() {
        val healthRepo: HealthContextRepository = mock()
        whenever(healthRepo.fetchSnapshotForAutodriveGater()).thenReturn(HealthContextSnapshot())
        gater = AutoDriveGater(healthRepo, mock<AAPSLogger>())
    }

    private fun rise(minBg75: Double, threshold: Double? = null) =
        if (threshold == null) {
            gater.shouldEngageV3(bg = 96.0, combinedDelta = 6.0, minBgLookback75m = minBg75)
        } else {
            gater.shouldEngageV3(
                bg = 96.0, combinedDelta = 6.0, minBgLookback75m = minBg75,
                reboundLookbackThresholdMgdl = threshold,
            )
        }

    @Test
    fun `the default line still only holds back a rise out of a real hypo`() {
        assertThat(rise(minBg75 = 70.0).engage).isFalse()
        assertThat(rise(minBg75 = 86.0).engage).isTrue()
    }

    @Test
    fun `raised to the LGS threshold, the rise out of an 86 dip no longer engages`() {
        val result = rise(minBg75 = 86.0, threshold = 95.0)

        assertThat(result.engage).isFalse()
        assertThat(result.kind).isEqualTo(GateKind.RISE_TOO_WEAK)
    }

    @Test
    fun `a rise that never went under the line still engages`() {
        assertThat(rise(minBg75 = 97.0, threshold = 95.0).engage).isTrue()
    }

    @Test
    fun `from 120 up the line does not apply, a real climb is still handled`() {
        val result = gater.shouldEngageV3(
            bg = 125.0, combinedDelta = 3.0, minBgLookback75m = 86.0,
            reboundLookbackThresholdMgdl = 95.0,
        )

        assertThat(result.engage).isTrue()
    }
}
