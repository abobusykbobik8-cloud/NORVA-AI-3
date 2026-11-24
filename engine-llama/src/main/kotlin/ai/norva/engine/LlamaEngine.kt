package ai.norva.engine

import ai.norva.inference.CpuGuard
import ai.norva.inference.EngineError
import ai.norva.inference.EngineException
import ai.norva.inference.GenStats
import ai.norva.inference.GenerationEvent
import ai.norva.inference.GenerationRequest
import ai.norva.inference.InferenceEngine
import ai.norva.inference.LoadParams
import ai.norva.inference.LoadedModel
import ai.norva.inference.StopReason
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

private fun readCpuInfo(): String = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")

/** Один поток инференса, одна модель, одна генерация за раз. */
class LlamaEngine : InferenceEngine {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "norva-llama") }.asCoroutineDispatcher()
    private val mutex = Mutex()

    @Volatile private var handle = 0L
    @Volatile private var loadedModel: LoadedModel? = null
    @Volatile private var threads: Pair<Int, Int> = 4 to 6

    override val loaded: LoadedModel? get() = loadedModel

    override suspend fun load(params: LoadParams) {
        mutex.withLock {
            withContext(dispatcher) {
                freeLocked()
                CpuGuard.check(LlamaNative.nativeSystemInfo(), readCpuInfo())?.let { throw EngineException(it) }
                val h = LlamaNative.nativeLoad(
                    params.modelPath, params.contextTokens, params.decodeThreads, params.prefillThreads,
                    params.useMmap, params.kvQ8,
                )
                if (h == 0L) throw EngineException("Не удалось загрузить модель: не хватило памяти, неверный файл или версия llama.cpp")
                handle = h
                loadedModel = LoadedModel(params.modelPath, params.contextTokens)
                threads = params.decodeThreads to params.prefillThreads
            }
        }
    }

    override fun setThreads(decode: Int, prefill: Int) {
        threads = decode to prefill
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = channelFlow {
        mutex.withLock {
            val h = handle
            if (h == 0L) {
                send(GenerationEvent.Failed(EngineError.NOT_LOADED, "Модель не загружена"))
                return@withLock
            }
            val stats = LongArray(4)
            // Если коллектор отменил поток, прерываем и prefill (между чанками), а не только декод.
            val watcher = launch {
                try { awaitCancellation() } finally { LlamaNative.nativeCancel(h) }
            }
            val rc = try {
                withContext(dispatcher) {
                    val (decode, prefill) = threads
                    LlamaNative.nativeSetThreads(h, decode, prefill)
                    val sink = TokenSink { bytes ->
                        trySendBlocking(GenerationEvent.Text(String(bytes, Charsets.UTF_8))).isSuccess && this@channelFlow.isActive
                    }
                    LlamaNative.nativeGenerate(
                        h, request.prompt.toByteArray(Charsets.UTF_8), request.maxNewTokens, request.temperature,
                        request.topP, request.topK, request.minP, request.repeatPenalty, request.presencePenalty, request.seed, sink, stats, request.photoPath, request.projectorPath,
                    )
                }
            } finally {
                withContext(NonCancellable) { watcher.cancelAndJoin() }
            }
            val s = GenStats(
                promptTokens = stats[0].toInt(), generatedTokens = stats[1].toInt(),
                promptMillis = stats[2], decodeMillis = stats[3],
                stop = when (rc) {
                    0 -> StopReason.END_OF_TEXT
                    1 -> StopReason.MAX_TOKENS
                    else -> StopReason.CANCELLED
                },
            )
            when (rc) {
                0, 1, 2 -> send(GenerationEvent.Finished(s))
                -2 -> send(GenerationEvent.Failed(EngineError.PROMPT_TOO_LONG, "Запрос не помещается в контекст (${s.promptTokens} токенов)"))
                -4 -> send(GenerationEvent.Failed(EngineError.INTERNAL, "Ошибка обработки снимка или проектора mtmd. Проверьте файлы модели."))
                -3 -> send(GenerationEvent.Failed(EngineError.DECODE_FAILED, "Ошибка вычисления в движке"))
                else -> send(GenerationEvent.Failed(EngineError.INTERNAL, "Внутренняя ошибка движка ($rc)"))
            }
        }
    }.buffer(64)

    override fun cancel() {
        val h = handle
        if (h != 0L) LlamaNative.nativeCancel(h)
    }

    override suspend fun unload() {
        mutex.withLock { withContext(dispatcher) { freeLocked() } }
    }

    /** Строка возможностей CPU из ggml: по ней видно, включились ли i8mm и dotprod на устройстве. */
    fun systemInfo(): String = LlamaNative.nativeSystemInfo()

    private fun freeLocked() {
        val h = handle
        if (h != 0L) {
            LlamaNative.nativeUnload(h)
            handle = 0L
            loadedModel = null
        }
    }
}
