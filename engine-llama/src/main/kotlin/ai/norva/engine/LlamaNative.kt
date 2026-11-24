package ai.norva.engine

/** Колбэк токенов. Байты - полные UTF-8 последовательности. Вернуть false, чтобы остановить генерацию. */
fun interface TokenSink {
    fun onToken(utf8: ByteArray): Boolean
}

/** Сигнатуры должны совпадать с norva_llama.cpp. */
object LlamaNative {
    init {
        System.loadLibrary("norva_llama")
    }

    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, nThreadsBatch: Int, useMmap: Boolean, kvQ8: Boolean): Long
    external fun nativeSetThreads(handle: Long, nThreads: Int, nThreadsBatch: Int)
    external fun nativeGenerate(
        handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, topP: Float, topK: Int,
        minP: Float, repeatPenalty: Float, presencePenalty: Float, seed: Int, sink: TokenSink, stats: LongArray, photo: String?, projector: String?,
    ): Int
    external fun nativeCancel(handle: Long)
    external fun nativeUnload(handle: Long)
    external fun nativeSystemInfo(): String
}
