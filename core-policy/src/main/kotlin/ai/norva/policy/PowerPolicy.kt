package ai.norva.policy

/**
 * thermalStatus - значение PowerManager.getCurrentThermalStatus():
 * 0 NONE, 1 LIGHT, 2 MODERATE, 3 SEVERE, 4 CRITICAL, 5 EMERGENCY, 6 SHUTDOWN.
 * batteryPercent = -1, если неизвестен.
 */
data class PowerState(
    val thermalStatus: Int,
    val batteryPercent: Int,
    val charging: Boolean,
    val powerSave: Boolean,
)

enum class ThrottleLevel { NORMAL, REDUCED, MINIMAL, BLOCKED }

data class Throttle(
    val level: ThrottleLevel,
    val decodeThreads: Int,
    val prefillThreads: Int,
    val contextScale: Double,
    val tokensScale: Double,
    val allowThinking: Boolean,
    val allowLocal: Boolean,
    val preferCloud: Boolean,
)

object PowerPolicy {
    fun decide(p: PowerState): Throttle {
        var level = when {
            p.thermalStatus >= 3 -> ThrottleLevel.BLOCKED
            p.thermalStatus == 2 -> ThrottleLevel.MINIMAL
            p.thermalStatus == 1 -> ThrottleLevel.REDUCED
            else -> ThrottleLevel.NORMAL
        }
        val lowBattery = !p.charging && p.batteryPercent in 0..14
        val criticalBattery = !p.charging && p.batteryPercent in 0..7
        if (p.powerSave || lowBattery) level = maxOf(level, ThrottleLevel.REDUCED)
        if (criticalBattery) level = maxOf(level, ThrottleLevel.MINIMAL)

        // Декод упирается в пропускную способность памяти, поэтому потоков для него меньше, чем для prefill.
        return when (level) {
            ThrottleLevel.NORMAL -> Throttle(level, 4, 6, 1.0, 1.0, true, true, false)
            ThrottleLevel.REDUCED -> Throttle(level, 3, 4, 1.0, 0.75, true, true, false)
            ThrottleLevel.MINIMAL -> Throttle(level, 2, 3, 0.5, 0.5, false, true, true)
            ThrottleLevel.BLOCKED -> Throttle(level, 0, 0, 0.0, 0.0, false, false, true)
        }
    }
}

fun GenerationProfile.under(t: Throttle): GenerationProfile {
    val scaledCtx = (contextTokens * t.contextScale).toInt() / MemoryBudget.CONTEXT_STEP * MemoryBudget.CONTEXT_STEP
    val ctx = minOf(contextTokens, maxOf(minContextTokens, scaledCtx))
    val thinkingOn = thinking && t.allowThinking
    return copy(
        contextTokens = ctx,
        maxNewTokens = maxOf(64, (maxNewTokens * t.tokensScale).toInt()),
        thinking = thinkingOn,
        thinkingBudgetTokens = if (thinkingOn) thinkingBudgetTokens else 0,
        selfCheckPass = selfCheckPass && t.allowThinking,
    )
}
