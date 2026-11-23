package ai.norva.inference

import ai.norva.policy.GenerationProfile
import kotlinx.coroutines.flow.Flow

data class LoadParams(
    val modelPath: String,
    val contextTokens: Int,
    val decodeThreads: Int,
    val prefillThreads: Int,
    val useMmap: Boolean = true,
    val kvQ8: Boolean = true,
)

data class LoadedModel(val path: String, val contextTokens: Int)

data class GenerationRequest(
    val prompt: String,
    val maxNewTokens: Int,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val minP: Float = 0f,
    val repeatPenalty: Float = 1f,
    val presencePenalty: Float = 0f,
    /** -1 - случайный seed. */
    val seed: Int = -1,
)

enum class StopReason { END_OF_TEXT, MAX_TOKENS, CANCELLED }

enum class EngineError { NOT_LOADED, PROMPT_TOO_LONG, DECODE_FAILED, INTERNAL }

data class GenStats(
    val promptTokens: Int,
    val generatedTokens: Int,
    val promptMillis: Long,
    val decodeMillis: Long,
    val stop: StopReason,
) {
    val decodeTokensPerSecond: Double
        get() = if (decodeMillis > 0) generatedTokens * 1000.0 / decodeMillis else 0.0
    val promptTokensPerSecond: Double
        get() = if (promptMillis > 0) promptTokens * 1000.0 / promptMillis else 0.0
}

sealed interface GenerationEvent {
    data class Text(val chunk: String) : GenerationEvent
    data class Finished(val stats: GenStats) : GenerationEvent
    data class Failed(val error: EngineError, val message: String) : GenerationEvent
}

class EngineException(message: String) : Exception(message)

/** Контракт движка: приложение не знает, llama.cpp это или что-то другое. */
interface InferenceEngine {
    val loaded: LoadedModel?
    suspend fun load(params: LoadParams)
    /** Применяется со следующего generate (нагрев и батарея меняют число потоков на лету). */
    fun setThreads(decode: Int, prefill: Int)
    fun generate(request: GenerationRequest): Flow<GenerationEvent>
    fun cancel()
    suspend fun unload()
}

/** Режим (Fast/Normal/Deep) после учёта нагрева превращается в реальные параметры сэмплинга. */
fun GenerationProfile.toRequest(prompt: String, seed: Int = -1) = GenerationRequest(
    prompt = prompt,
    maxNewTokens = maxNewTokens,
    temperature = temperature,
    topP = topP,
    topK = topK,
    repeatPenalty = repeatPenalty,
    presencePenalty = presencePenalty,
    seed = seed,
)
