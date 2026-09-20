package app.aaps.plugins.aps.openAPSAIMI.basal

import android.content.Context
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.AIMIAdaptiveBasal
import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BasalDecisionEngineTest {

    private val context = mockk<Context>(relaxed = true)
    private val aimiAdaptiveBasal = mockk<AIMIAdaptiveBasal>()
    private val basalPlanner = mockk<BasalPlanner>()
    private val engine = BasalDecisionEngine(context, aimiAdaptiveBasal, basalPlanner)

    @Test
    fun `decide - should delegate to AIMIAdaptiveBasal when GS is available`() {
        // Arrange
        val rt = RT(algorithm = APSResult.Algorithm.AIMI, runningDynamicIsf = true)
        val gs = mockk<GlucoseStatusAIMI>()
        every { gs.glucose } returns 150.0
        every { gs.delta } returns 2.0
        every { gs.shortAvgDelta } returns 1.5
        every { gs.longAvgDelta } returns 1.0
        every { gs.bgAcceleration } returns 0.2
        every { gs.corrSqu } returns 0.8
        every { gs.parabolaMinutes } returns 20.0

        val input = BasalDecisionEngine.Input(
            bg = 150.0,
            profileCurrentBasal = 1.0,
            basalEstimate = 1.0,
            tdd7P = 50.0,
            tdd7Days = 50.0,
            variableSensitivity = 45.0,
            profileSens = 45.0,
            predictedBg = 160.0,
            targetBg = 100.0,
            minBg = 70.0,
            lgsThreshold = 75.0,
            eventualBg = 170.0,
            iob = 1.0,
            maxIob = 5.0,
            allowMealHighIob = false,
            safetyDecision = mockk<SafetyDecision>(relaxed = true),
            mealData = mockk<MealData>(relaxed = true),
            delta = 2.0,
            shortAvgDelta = 1.5,
            longAvgDelta = 1.0,
            combinedDelta = 2.5,
            bgAcceleration = 0.2,
            slopeFromMaxDeviation = 0.1,
            slopeFromMinDeviation = 0.1,
            forcedBasal = 1.0,
            forcedMealActive = false,
            isMealActive = false,
            runtimeMinValue = 0,
            snackTime = false,
            snackRuntimeMin = 0,
            fastingTime = false,
            sportTime = false,
            honeymoon = false,
            pregnancyEnable = false,
            mealTime = false,
            mealRuntimeMin = 0,
            bfastTime = false,
            bfastRuntimeMin = 0,
            lunchTime = false,
            lunchRuntimeMin = 0,
            dinnerTime = false,
            dinnerRuntimeMin = 0,
            highCarbTime = false,
            highCarbRuntimeMin = 0,
            timenow = 12,
            sixAmHour = 6,
            recentSteps5Minutes = 0,
            nightMode = false,
            modesCondition = true,
            autodrive = true,
            currentTemp = mockk<CurrentTemp>(relaxed = true),
            glucoseStatus = gs,
            featuresCombinedDelta = 2.5,
            smbToGive = 0.0,
            zeroSinceMin = 0,
            minutesSinceLastChange = 0,
            pumpCaps = mockk<PumpCaps>(relaxed = true)
        )

        every { basalPlanner.plan(any()) } returns null
        every { aimiAdaptiveBasal.suggest(any()) } returns AIMIAdaptiveBasal.Decision(1.2, 30, "AIMI+ active")

        val helpers = BasalDecisionEngine.Helpers(
            calculateRate = { _, _, mult, _ -> 1.0 * mult },
            calculateBasalRate = { _, _, mult -> 1.0 * mult },
            detectMealOnset = { _, _, _, _, _ -> false },
            round = { v, _ -> v }
        )

        // Act
        val decision = engine.decide(input, rt, helpers)

        // Assert
        assertNotNull(decision)
        assertEquals(1.2, decision.rate, 0.01)
        assertEquals(30, decision.duration)
    }

    // ---- Early-meal forced TBR guards (report ②) -------------------------------------------------
    // glucoseStatus = null so the AIMIAdaptiveBasal delegation (guarded by glucoseStatus?.let) is
    // skipped and chosenRate enters the forced-TBR branch as null; detectMealOnset is forced true.

    private fun forcedTbrInput(
        iob: Double = 1.0,
        maxIob: Double = 10.0,
        eventualBg: Double = 170.0,
        minPredBg: Double? = 150.0,
        lgsThreshold: Double = 75.0,
        delta: Double = 3.0,
        nightMode: Boolean = false,
        forcedBasal: Double = 5.0,
    ) = BasalDecisionEngine.Input(
        bg = 145.0,
        profileCurrentBasal = 0.8,
        basalEstimate = 0.8,
        tdd7P = 40.0,
        tdd7Days = 40.0,
        variableSensitivity = 50.0,
        profileSens = 50.0,
        predictedBg = 160.0,
        targetBg = 100.0,
        minBg = 70.0,
        lgsThreshold = lgsThreshold,
        eventualBg = eventualBg,
        minPredBg = minPredBg,
        iob = iob,
        maxIob = maxIob,
        allowMealHighIob = false,
        safetyDecision = mockk<SafetyDecision>(relaxed = true),
        mealData = mockk<MealData>(relaxed = true),
        delta = delta,
        shortAvgDelta = delta,
        longAvgDelta = delta,
        combinedDelta = delta,
        bgAcceleration = 0.5,
        slopeFromMaxDeviation = 0.1,
        slopeFromMinDeviation = 0.1,
        forcedBasal = forcedBasal,
        forcedMealActive = false,
        isMealActive = false,
        runtimeMinValue = 0,
        snackTime = false,
        snackRuntimeMin = 0,
        fastingTime = false,
        sportTime = false,
        honeymoon = false,
        pregnancyEnable = false,
        mealTime = false,
        mealRuntimeMin = 0,
        bfastTime = false,
        bfastRuntimeMin = 0,
        lunchTime = false,
        lunchRuntimeMin = 0,
        dinnerTime = false,
        dinnerRuntimeMin = 0,
        highCarbTime = false,
        highCarbRuntimeMin = 0,
        timenow = 12,
        sixAmHour = 6,
        recentSteps5Minutes = 0,
        nightMode = nightMode,
        modesCondition = true,
        autodrive = true,
        currentTemp = mockk<CurrentTemp>(relaxed = true),
        glucoseStatus = null,
        featuresCombinedDelta = delta,
        smbToGive = 0.0,
        zeroSinceMin = 0,
        minutesSinceLastChange = 0,
        pumpCaps = mockk<PumpCaps>(relaxed = true)
    )

    private fun mealOnsetHelpers() = BasalDecisionEngine.Helpers(
        calculateRate = { _, _, mult, _ -> 1.0 * mult },
        calculateBasalRate = { _, _, mult -> 1.0 * mult },
        detectMealOnset = { _, _, _, _, _ -> true },
        round = { v, _ -> v }
    )

    private fun decideForcedTbr(input: BasalDecisionEngine.Input): Pair<BasalDecisionEngine.Decision, RT> {
        every { basalPlanner.plan(any()) } returns null
        val rt = RT(algorithm = APSResult.Algorithm.AIMI, runningDynamicIsf = true)
        val decision = engine.decide(input, rt, mealOnsetHelpers())
        return decision to rt
    }

    @Test
    fun `forced early-meal TBR fires when all guards are clear`() {
        val (decision, rt) = decideForcedTbr(forcedTbrInput())
        assertTrue(decision.overrideSafety) { "override should be granted when guards clear" }
        assertTrue(rt.reason.toString().contains("AD_EARLY_TBR_TRIGGER")) { rt.reason.toString() }
    }

    @Test
    fun `forced TBR suppressed when IOB above absolute cap`() {
        val (decision, rt) = decideForcedTbr(forcedTbrInput(iob = 5.0))
        assertFalse(decision.overrideSafety) { "forced override must not fire while stacking" }
        val reason = rt.reason.toString()
        assertTrue(reason.contains("AD_EARLY_TBR_BLOCKED")) { reason }
        assertTrue(reason.contains("iob=")) { reason }
    }

    @Test
    fun `forced TBR suppressed at night without a steep rise`() {
        val (decision, rt) = decideForcedTbr(forcedTbrInput(nightMode = true, delta = 3.0))
        assertFalse(decision.overrideSafety)
        val reason = rt.reason.toString()
        assertTrue(reason.contains("AD_EARLY_TBR_BLOCKED")) { reason }
        assertTrue(reason.contains("nightDelta=")) { reason }
    }

    @Test
    fun `forced TBR allowed at night on a genuinely steep rise`() {
        val (decision, rt) = decideForcedTbr(forcedTbrInput(nightMode = true, delta = 9.0))
        assertTrue(decision.overrideSafety)
        assertTrue(rt.reason.toString().contains("AD_EARLY_TBR_TRIGGER")) { rt.reason.toString() }
    }

    @Test
    fun `forced TBR suppressed when prediction heads toward the LGS threshold`() {
        // eventualBg 85 < lgsThreshold 75 + 15 margin → dropping toward a low, do not force a high TBR.
        val (decision, rt) = decideForcedTbr(forcedTbrInput(eventualBg = 85.0))
        assertFalse(decision.overrideSafety)
        val reason = rt.reason.toString()
        assertTrue(reason.contains("AD_EARLY_TBR_BLOCKED")) { reason }
        assertTrue(reason.contains("evBG=")) { reason }
    }

    // The field case this guard was widened for: the curve dips into a low and comes back up, so
    // eventualBg reads high and clears the threshold on its own while the dip goes under it.
    // On an 11-day field log that shape produced 106 of 291 forced TBRs, the worst at minPredBG 39
    // against an eventualBg of 250-400.
    @Test
    fun `forced TBR suppressed when the curve dips low before coming back up`() {
        val (decision, rt) = decideForcedTbr(forcedTbrInput(eventualBg = 300.0, minPredBg = 39.0))
        assertFalse(decision.overrideSafety) { "a predicted low must suppress the forced TBR" }
        val reason = rt.reason.toString()
        assertTrue(reason.contains("AD_EARLY_TBR_BLOCKED")) { reason }
        assertTrue(reason.contains("minPredBG=")) { reason }
        assertFalse(reason.contains("evBG=")) { "eventualBg was fine; only minPredBG blocked" }
    }

    @Test
    fun `forced TBR still fires when both predictions clear the threshold`() {
        val (decision, rt) = decideForcedTbr(forcedTbrInput(eventualBg = 200.0, minPredBg = 120.0))
        assertTrue(decision.overrideSafety)
        assertTrue(rt.reason.toString().contains("AD_EARLY_TBR_TRIGGER")) { rt.reason.toString() }
    }

    // A missing prediction must not silently tighten the loop: fall back to the previous behaviour.
    @Test
    fun `forced TBR falls back to eventualBg alone when no prediction is available`() {
        val (decision, rt) = decideForcedTbr(forcedTbrInput(minPredBg = null))
        assertTrue(decision.overrideSafety)
        assertTrue(rt.reason.toString().contains("AD_EARLY_TBR_TRIGGER")) { rt.reason.toString() }
    }
}
