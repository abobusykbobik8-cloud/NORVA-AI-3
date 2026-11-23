package ai.norva.policy

data class LocalSelection(
    val model: ModelSpec,
    val contextTokens: Int,
    /** true только если режим просит thinking и модель его поддерживает. */
    val thinking: Boolean,
)

object LocalModelSelector {
    private val order: Map<ThinkingMode, List<ModelTier>> = mapOf(
        ThinkingMode.FAST to listOf(ModelTier.SMALL, ModelTier.BALANCED),
        ThinkingMode.NORMAL to listOf(ModelTier.BALANCED, ModelTier.SMALL),
        ThinkingMode.DEEP to listOf(ModelTier.STRONG, ModelTier.BALANCED, ModelTier.SMALL),
    )

    fun select(
        profile: GenerationProfile,
        budgetBytes: Long,
        installedIds: Set<String>,
        catalog: List<ModelSpec> = DefaultCatalog.models,
        allowExperimental: Boolean = false,
    ): LocalSelection? {
        val tiers = order.getValue(profile.mode)
        for (tier in tiers) {
            for (spec in catalog) {
                if (spec.tier != tier || spec.id !in installedIds) continue
                if (spec.experimental && !allowExperimental) continue
                val ctx = MemoryBudget.maxContext(spec, budgetBytes, profile.contextTokens)
                if (ctx >= profile.minContextTokens) {
                    return LocalSelection(spec, ctx, profile.thinking && spec.supportsThinking)
                }
            }
        }
        return null
    }
}
