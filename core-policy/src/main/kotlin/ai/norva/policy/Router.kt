package ai.norva.policy

enum class SourcePreference { AUTO, LOCAL_ONLY, CLOUD_ONLY }
enum class Source { LOCAL, CLOUD }

enum class RouteReason {
    MANUAL_LOCAL, MANUAL_CLOUD, OFFLINE_OR_NO_CLOUD, DEVICE_THROTTLED,
    DEEP_MODE_CLOUD, PROMPT_TOO_LONG_FOR_LOCAL, DEFAULT_LOCAL, LOCAL_UNAVAILABLE_FALLBACK_CLOUD,
}

enum class UnavailableReason { CLOUD_NOT_READY, LOCAL_NOT_READY, NOTHING_AVAILABLE }

sealed interface RouteDecision {
    data class Use(val source: Source, val reason: RouteReason) : RouteDecision
    data class Unavailable(val reason: UnavailableReason) : RouteDecision
}

data class RouteInput(
    val preference: SourcePreference,
    /** Облако настроено (провайдер, ключ) и есть сеть. */
    val cloudReady: Boolean,
    /** Пользователь явно разрешил автоматически отправлять запросы в облако в режиме AUTO. */
    val autoCloudConsent: Boolean,
    /** Локальная модель установлена и помещается в бюджет памяти. */
    val localReady: Boolean,
    val localContextTokens: Int,
    val promptTokens: Int,
    val throttle: Throttle,
    val profile: GenerationProfile,
)

object Router {
    fun decide(i: RouteInput): RouteDecision {
        val localOk = i.localReady && i.throttle.allowLocal
        return when (i.preference) {
            SourcePreference.CLOUD_ONLY ->
                if (i.cloudReady) RouteDecision.Use(Source.CLOUD, RouteReason.MANUAL_CLOUD)
                else RouteDecision.Unavailable(UnavailableReason.CLOUD_NOT_READY)

            SourcePreference.LOCAL_ONLY ->
                if (localOk) RouteDecision.Use(Source.LOCAL, RouteReason.MANUAL_LOCAL)
                else RouteDecision.Unavailable(UnavailableReason.LOCAL_NOT_READY)

            SourcePreference.AUTO -> auto(i, localOk)
        }
    }

    private fun auto(i: RouteInput, localOk: Boolean): RouteDecision {
        val cloudOk = i.cloudReady && i.autoCloudConsent
        if (!cloudOk) {
            return if (localOk) RouteDecision.Use(Source.LOCAL, RouteReason.OFFLINE_OR_NO_CLOUD)
            else RouteDecision.Unavailable(UnavailableReason.NOTHING_AVAILABLE)
        }
        if (!localOk) {
            val why = if (!i.throttle.allowLocal) RouteReason.DEVICE_THROTTLED
            else RouteReason.LOCAL_UNAVAILABLE_FALLBACK_CLOUD
            return RouteDecision.Use(Source.CLOUD, why)
        }
        if (i.throttle.preferCloud) return RouteDecision.Use(Source.CLOUD, RouteReason.DEVICE_THROTTLED)
        if (i.profile.preferCloudWhenAvailable) return RouteDecision.Use(Source.CLOUD, RouteReason.DEEP_MODE_CLOUD)
        if (i.promptTokens + i.profile.maxNewTokens > i.localContextTokens) {
            return RouteDecision.Use(Source.CLOUD, RouteReason.PROMPT_TOO_LONG_FOR_LOCAL)
        }
        return RouteDecision.Use(Source.LOCAL, RouteReason.DEFAULT_LOCAL)
    }
}
