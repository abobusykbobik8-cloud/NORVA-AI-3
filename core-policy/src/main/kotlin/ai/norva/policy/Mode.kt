package ai.norva.policy

enum class ThinkingMode { FAST, NORMAL, DEEP }

enum class ModelTier { LITE, SMALL, BALANCED, STRONG }

/**
 * Параметры, которые режим реально меняет в движке и в сборке промпта.
 * Сэмплинг по карточке Qwen/Qwen3.5-4B: с рассуждениями temp 1.0, top_p 0.95, top_k 20, presence_penalty 1.5;
 * без рассуждений temp 0.7, top_p 0.8, top_k 20, presence_penalty 1.5. Для других моделей значения подбираются заново.
 */
data class GenerationProfile(
    val mode: ThinkingMode,
    val preferredTier: ModelTier,
    val contextTokens: Int,
    val minContextTokens: Int,
    val maxNewTokens: Int,
    val thinking: Boolean,
    val thinkingBudgetTokens: Int,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val repeatPenalty: Float,
    val memoryTopK: Int,
    val selfCheckPass: Boolean,
    val preferCloudWhenAvailable: Boolean,
    val presencePenalty: Float = 0f,
)

object ModeProfiles {
    fun of(mode: ThinkingMode): GenerationProfile = when (mode) {
        ThinkingMode.FAST -> GenerationProfile(
            mode = mode, preferredTier = ModelTier.SMALL,
            contextTokens = 2048, minContextTokens = 1024, maxNewTokens = 384,
            thinking = false, thinkingBudgetTokens = 0,
            temperature = 0.7f, topP = 0.8f, topK = 20, repeatPenalty = 1.0f, presencePenalty = 1.5f,
            memoryTopK = 0, selfCheckPass = false, preferCloudWhenAvailable = false,
        )
        ThinkingMode.NORMAL -> GenerationProfile(
            mode = mode, preferredTier = ModelTier.BALANCED,
            contextTokens = 4096, minContextTokens = 2048, maxNewTokens = 1024,
            thinking = false, thinkingBudgetTokens = 0,
            temperature = 0.7f, topP = 0.8f, topK = 20, repeatPenalty = 1.0f, presencePenalty = 1.5f,
            memoryTopK = 4, selfCheckPass = false, preferCloudWhenAvailable = false,
        )
        ThinkingMode.DEEP -> GenerationProfile(
            mode = mode, preferredTier = ModelTier.BALANCED,
            contextTokens = 8192, minContextTokens = 4096, maxNewTokens = 3072,
            thinking = true, thinkingBudgetTokens = 1536,
            temperature = 1.0f, topP = 0.95f, topK = 20, repeatPenalty = 1.0f, presencePenalty = 1.5f,
            memoryTopK = 8, selfCheckPass = true, preferCloudWhenAvailable = true,
        )
    }
}

/** Qwen3.5 model-card presets for general text and visual language tasks. */
fun GenerationProfile.qwenSampling(reasoning: Boolean, vision: Boolean, tier: ModelTier): GenerationProfile = when {
    reasoning && vision -> copy(temperature = 0.6f, topP = 0.95f, presencePenalty = 0f)
    reasoning -> copy(temperature = 1f, topP = 0.95f, presencePenalty = 1.5f)
    vision || tier == ModelTier.BALANCED -> copy(temperature = 0.7f, topP = 0.8f, presencePenalty = 1.5f)
    else -> copy(temperature = 1f, topP = 1f, presencePenalty = 2f)
}
