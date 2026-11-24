package ai.norva.policy

data class LocalSelection(
    val model: ModelSpec,
    val contextTokens: Int,
    /** true только если режим просит thinking и модель его поддерживает. */
    val thinking: Boolean,
)

object LocalModelSelector {
    private val order: Map<ThinkingMode, List<ModelTier>> = mapOf(
        ThinkingMode.FAST to listOf(ModelTier.SMALL, ModelTier.BALANCED, ModelTier.LITE),
        ThinkingMode.NORMAL to listOf(ModelTier.BALANCED, ModelTier.SMALL, ModelTier.LITE),
        ThinkingMode.DEEP to listOf(ModelTier.STRONG, ModelTier.BALANCED, ModelTier.SMALL, ModelTier.LITE),
    )

    fun select(
        profile: GenerationProfile,
        budgetBytes: Long,
        installedIds: Set<String>,
        catalog: List<ModelSpec> = DefaultCatalog.models,
        allowExperimental: Boolean = false,
        budgetFor: (ModelSpec) -> Long = { budgetBytes },
    ): LocalSelection? {
        val tiers = order.getValue(profile.mode)
        for (tier in tiers) {
            for (spec in catalog) {
                if (spec.tier != tier || spec.id !in installedIds) continue
                if (spec.experimental && !allowExperimental) continue
                val wanted = if (spec.tier == ModelTier.LITE) minOf(2048, profile.contextTokens) else profile.contextTokens
                val ctx = MemoryBudget.maxContext(spec, budgetFor(spec), wanted)
                val minimum = if (spec.tier == ModelTier.LITE) MemoryBudget.MIN_CONTEXT else profile.minContextTokens
                if (ctx >= minimum) {
                    return LocalSelection(spec, ctx, profile.thinking && spec.supportsThinking)
                }
            }
        }
        return null
    }
}
