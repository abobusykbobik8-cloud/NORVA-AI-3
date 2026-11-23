package ai.norva.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerAndRouterTest {
    private fun power(thermal: Int = 0, battery: Int = 80, charging: Boolean = false, saver: Boolean = false) =
        PowerPolicy.decide(PowerState(thermal, battery, charging, saver))

    @Test fun coolPhoneRunsAtFullSpeed() {
        val t = power()
        assertEquals(ThrottleLevel.NORMAL, t.level)
        assertEquals(4, t.decodeThreads)
        assertEquals(6, t.prefillThreads)
    }

    @Test fun moderateThermalDisablesThinkingAndPrefersCloud() {
        val t = power(thermal = 2)
        assertEquals(ThrottleLevel.MINIMAL, t.level)
        assertFalse(t.allowThinking)
        assertTrue(t.preferCloud)
    }

    @Test fun severeThermalBlocksLocal() {
        val t = power(thermal = 3)
        assertEquals(ThrottleLevel.BLOCKED, t.level)
        assertFalse(t.allowLocal)
    }

    @Test fun lowBatteryOnlyMattersWhenNotCharging() {
        assertEquals(ThrottleLevel.REDUCED, power(battery = 12).level)
        assertEquals(ThrottleLevel.NORMAL, power(battery = 12, charging = true).level)
        assertEquals(ThrottleLevel.MINIMAL, power(battery = 5).level)
        assertEquals(ThrottleLevel.NORMAL, power(battery = -1).level)
    }

    @Test fun powerSaverReducesLoad() {
        assertEquals(ThrottleLevel.REDUCED, power(saver = true).level)
    }

    @Test fun throttleShrinksDeepProfileButKeepsMinimumContext() {
        val deep = ModeProfiles.of(ThinkingMode.DEEP)
        val minimal = deep.under(power(thermal = 2))
        assertEquals(4096, minimal.contextTokens)
        assertEquals(1536, minimal.maxNewTokens)
        assertFalse(minimal.thinking)
        assertEquals(0, minimal.thinkingBudgetTokens)
        assertFalse(minimal.selfCheckPass)

        val reduced = deep.under(power(saver = true))
        assertEquals(8192, reduced.contextTokens)
        assertEquals(2304, reduced.maxNewTokens)
        assertTrue(reduced.thinking)
    }

    private fun input(
        pref: SourcePreference = SourcePreference.AUTO,
        mode: ThinkingMode = ThinkingMode.NORMAL,
        cloud: Boolean = true,
        consent: Boolean = true,
        local: Boolean = true,
        localCtx: Int = 4096,
        prompt: Int = 500,
        throttle: Throttle = power(),
    ) = RouteInput(pref, cloud, consent, local, localCtx, prompt, throttle, ModeProfiles.of(mode))

    private fun use(d: RouteDecision) = d as RouteDecision.Use

    @Test fun manualCloudWithoutCloudIsUnavailable() {
        val d = Router.decide(input(pref = SourcePreference.CLOUD_ONLY, cloud = false))
        assertEquals(RouteDecision.Unavailable(UnavailableReason.CLOUD_NOT_READY), d)
    }

    @Test fun manualLocalIsUnavailableWhenBlockedByHeat() {
        val d = Router.decide(input(pref = SourcePreference.LOCAL_ONLY, throttle = power(thermal = 3)))
        assertEquals(RouteDecision.Unavailable(UnavailableReason.LOCAL_NOT_READY), d)
    }

    @Test fun autoStaysLocalWithoutConsentEvenInDeepMode() {
        val d = use(Router.decide(input(mode = ThinkingMode.DEEP, consent = false)))
        assertEquals(Source.LOCAL, d.source)
    }

    @Test fun autoDeepGoesToCloudWithConsent() {
        val d = use(Router.decide(input(mode = ThinkingMode.DEEP)))
        assertEquals(Source.CLOUD, d.source)
        assertEquals(RouteReason.DEEP_MODE_CLOUD, d.reason)
    }

    @Test fun autoNormalShortPromptStaysLocal() {
        val d = use(Router.decide(input()))
        assertEquals(Source.LOCAL, d.source)
        assertEquals(RouteReason.DEFAULT_LOCAL, d.reason)
    }

    @Test fun autoLongPromptMovesToCloud() {
        val d = use(Router.decide(input(prompt = 4000)))
        assertEquals(Source.CLOUD, d.source)
        assertEquals(RouteReason.PROMPT_TOO_LONG_FOR_LOCAL, d.reason)
    }

    @Test fun autoOverheatedPhoneFallsBackToCloud() {
        val d = use(Router.decide(input(throttle = power(thermal = 3))))
        assertEquals(Source.CLOUD, d.source)
        assertEquals(RouteReason.DEVICE_THROTTLED, d.reason)
    }

    @Test fun autoOfflineUsesLocal() {
        val d = use(Router.decide(input(cloud = false)))
        assertEquals(Source.LOCAL, d.source)
    }

    @Test fun nothingAvailableIsReported() {
        val d = Router.decide(input(cloud = false, local = false))
        assertEquals(RouteDecision.Unavailable(UnavailableReason.NOTHING_AVAILABLE), d)
    }
}
