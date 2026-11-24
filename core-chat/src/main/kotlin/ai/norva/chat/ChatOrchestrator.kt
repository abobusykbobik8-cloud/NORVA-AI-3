package ai.norva.chat

import ai.norva.inference.ChatMl
import ai.norva.inference.ChatTurn
import ai.norva.inference.EngineError
import ai.norva.inference.EngineException
import ai.norva.inference.GenStats
import ai.norva.inference.GenerationEvent
import ai.norva.inference.GenerationRequest
import ai.norva.inference.HistoryTrimmer
import ai.norva.inference.InferenceEngine
import ai.norva.inference.LoadParams
import ai.norva.inference.ModelStore
import ai.norva.inference.Role
import ai.norva.inference.StopReason
import ai.norva.inference.ThinkingFilter
import ai.norva.inference.toRequest
import ai.norva.policy.DefaultCatalog
import ai.norva.policy.DeviceMemory
import ai.norva.policy.GenerationProfile
import ai.norva.policy.LocalModelSelector
import ai.norva.policy.LocalSelection
import ai.norva.policy.MemoryBudget
import ai.norva.policy.ModeProfiles
import ai.norva.policy.ModelTier
import ai.norva.policy.ModelSpec
import ai.norva.policy.PowerPolicy
import ai.norva.policy.Throttle
import ai.norva.policy.ThinkingMode
import ai.norva.policy.qwenSampling
import ai.norva.policy.under
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

sealed interface ChatStatus {
    data object Idle : ChatStatus
    data class LoadingModel(val conversationId: Long, val modelName: String) : ChatStatus
    data class Generating(val conversationId: Long, val modelName: String, val mode: ThinkingMode) : ChatStatus
}

private enum class StopCause { THINKING_BUDGET, OVERHEATED, REPEATING }

/** Служебное исключение: прерывает сбор потока движка (это отменяет и нативную генерацию). */
private class StopStreaming(val stopCause: StopCause) : Exception()

private class Accumulator {
    val thinking = StringBuilder()
    val answer = StringBuilder()
}

private sealed interface Prep {
    data class Ready(val selection: LocalSelection, val profile: GenerationProfile, val throttle: Throttle) : Prep
    data class Unavailable(val message: String) : Prep
}

private sealed interface Segment {
    data class Finished(val stats: GenStats) : Segment
    data class Failed(val error: EngineError, val message: String) : Segment
    data class Stopped(val cause: StopCause, val chunks: Int) : Segment
}

private data class Outcome(val status: MessageStatus, val error: String?, val stats: GenStats?)

/** Пишет частичный ответ в историю не чаще раза в interval, чтобы не забивать базу на каждый токен. */
private class Persister(
    private var msg: StoredMessage,
    private val repo: ChatRepository,
    private val clock: () -> Long,
    private val interval: Long,
) {
    private var last = clock()

    suspend fun tick(acc: Accumulator) {
        val now = clock()
        if (now - last < interval) return
        last = now
        msg = msg.copy(content = acc.answer.toString(), thinking = acc.thinking.toString())
        repo.updateMessage(msg)
    }

    suspend fun finish(acc: Accumulator, status: MessageStatus, modelId: String?, tps: Double?, error: String?) {
        msg = msg.copy(
            content = acc.answer.toString().trim(),
            thinking = acc.thinking.toString().trim(),
            status = status,
            modelId = modelId,
            tokensPerSecond = tps,
            errorMessage = error,
        )
        repo.updateMessage(msg)
    }
}

/**
 * Один ответ = выбор модели под память и нагрев, загрузка при необходимости, потоковая генерация,
 * разбор <think>, ограничение бюджета рассуждения и сохранение в историю.
 * Облачный маршрут требует явного выбора провайдера в настройках.
 * Не реализовано: поиск по долговременной памяти, второй проход проверки (selfCheckPass), повторное использование KV-кэша.
 */
class ChatOrchestrator(
    private val repo: ChatRepository,
    private val engine: InferenceEngine,
    private val store: ModelStore,
    private val power: PowerStateProvider,
    private val memory: MemoryInfoProvider,
    private val scope: CoroutineScope,
    private val systemPrompt: () -> String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val catalog: List<ModelSpec> = DefaultCatalog.models,
    private val persistIntervalMs: Long = 300L,
    private val remoteModel: () -> String? = { null },
    private val remoteChat: (suspend (List<ChatTurn>, ThinkingMode) -> String)? = null,
) {
    private val _status = MutableStateFlow<ChatStatus>(ChatStatus.Idle)
    val status: StateFlow<ChatStatus> = _status.asStateFlow()

    private val busy = Mutex()
    @Volatile private var running: Job? = null

    /** Запуск в прикладном scope: генерация продолжается, даже если пользователь ушёл с экрана чата. */
    @Synchronized
    fun startSend(conversationId: Long, text: String, mode: ThinkingMode, attachment: Attachment? = null) {
        if (running?.isActive == true || busy.isLocked) return
        running = scope.launch { send(conversationId, text, mode, attachment) }
    }

    @Synchronized
    fun startRegenerate(conversationId: Long, mode: ThinkingMode) {
        if (running?.isActive == true || busy.isLocked) return
        running = scope.launch { regenerate(conversationId, mode) }
    }

    /** Отмена текущей генерации; частичный ответ сохраняется со статусом CANCELLED. */
    fun stop() {
        running?.cancel()
    }

    suspend fun recoverInterrupted() = repo.failInterruptedMessages()

    /** Выгружает модель, если сейчас ничего не генерируется (вызывается при нехватке памяти в системе). */
    suspend fun releaseIfIdle() {
        if (!busy.tryLock()) return
        try {
            engine.unload()
        } finally {
            busy.unlock()
        }
    }

    suspend fun send(conversationId: Long, text: String, mode: ThinkingMode, attachment: Attachment? = null) {
        val clean = text.trim().ifEmpty { if (attachment != null) DEFAULT_PHOTO_QUESTION else "" }
        if (clean.isEmpty() || !busy.tryLock()) return
        try {
            if (repo.getMessages(conversationId).isEmpty()) {
                val conv = repo.getConversation(conversationId)
                if (conv != null && conv.title == DEFAULT_TITLE) repo.renameConversation(conversationId, titleFrom(clean))
            }
            repo.addMessage(
                StoredMessage(
                    conversationId = conversationId, role = Role.USER, content = clean, createdAt = clock(),
                    attachmentKind = attachment?.kind, attachmentPath = attachment?.path, attachmentNote = attachment?.note,
                ),
            )
            reply(conversationId, mode)
        } finally {
            busy.unlock()
        }
    }

    /** Удаляет последний ответ ассистента и генерирует его заново. */
    suspend fun regenerate(conversationId: Long, mode: ThinkingMode) {
        if (!busy.tryLock()) return
        try {
            val last = repo.getMessages(conversationId).lastOrNull() ?: return
            if (last.role == Role.ASSISTANT) repo.deleteMessage(last.id)
            if (repo.getMessages(conversationId).lastOrNull()?.role != Role.USER) return
            reply(conversationId, mode)
        } finally {
            busy.unlock()
        }
    }

    private suspend fun reply(conversationId: Long, mode: ThinkingMode) {
        val history = repo.getMessages(conversationId)
        var msg = StoredMessage(
            conversationId = conversationId, role = Role.ASSISTANT, content = "",
            status = MessageStatus.STREAMING, mode = mode, createdAt = clock(),
        )
        msg = msg.copy(id = repo.addMessage(msg))

        val acc = Accumulator()
        val persister = Persister(msg, repo, clock, persistIntervalMs)
        var status = MessageStatus.FAILED
        var error: String? = null
        var stats: GenStats? = null
        var modelId: String? = null
        try {
            val remote = remoteModel()
            if (remote != null && remoteChat != null) {
                modelId = remote
                _status.value = ChatStatus.Generating(conversationId, remote, mode)
                engine.unload()
                val turns = HistoryTrimmer.fit(buildTurns(history), ModeProfiles.of(mode).contextTokens)
                acc.answer.append(remoteChat.invoke(turns, mode))
                status = MessageStatus.COMPLETE
                return
            }
            val retained = HistoryTrimmer.fit(buildTurns(history), ModeProfiles.of(mode).contextTokens)
            when (val prep = prepare(mode, retained.any { it.photoPath != null })) {
                is Prep.Unavailable -> error = prep.message
                is Prep.Ready -> {
                    modelId = prep.selection.model.id
                    val outcome = run(conversationId, mode, prep, history, acc, persister)
                    status = outcome.status
                    error = outcome.error
                    stats = outcome.stats
                }
            }
        } catch (e: CancellationException) {
            status = MessageStatus.CANCELLED
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Ошибка генерации"
        } finally {
            withContext(NonCancellable) {
                persister.finish(acc, status, modelId, stats?.decodeTokensPerSecond, error)
                _status.value = ChatStatus.Idle
            }
        }
    }

    private fun prepare(mode: ThinkingMode, vision: Boolean): Prep {
        val mem = memory.current()
        val powerLimit = PowerPolicy.decide(power.current())
        val throttle = if (mem.totalBytes <= 4 * MemoryBudget.GIB)
            powerLimit.copy(decodeThreads = minOf(2, powerLimit.decodeThreads), prefillThreads = minOf(3, powerLimit.prefillThreads))
        else powerLimit
        if (!throttle.allowLocal) {
            return Prep.Unavailable("Телефон перегрет: локальный ИИ приостановлен. Подождите, пока устройство остынет.")
        }
        val installed = store.installedIds(catalog)
        if (installed.isEmpty()) {
            return Prep.Unavailable("Нет установленных моделей. Установите модель на экране «Модели».")
        }
        val candidates = if (vision) catalog.filter { spec ->
            DefaultCatalog.projectors[spec.id]?.let { store.isInstalled(it) } == true
        } else catalog
        if (vision && candidates.none { it.id in installed }) return Prep.Unavailable("Для фото скачайте в разделе «Модели» проектор той же модели (0.8B, 2B или 4B).")
        val profile = ModeProfiles.of(mode).under(throttle)
        val budget = MemoryBudget.budgetBytes(mem)
        if (budget == 0L) return Prep.Unavailable("Android сообщает о нехватке свободной памяти. Закройте фоновые приложения.")
        val loaded = engine.loaded
        val selection = LocalModelSelector.select(profile, budget, installed, candidates) { spec ->
            val projectorBytes = if (vision) DefaultCatalog.projectors.getValue(spec.id).fileSizeBytes + 224L * 1024 * 1024 else 0L
            val newBudget = budget - projectorBytes
            // Reuse exactly the existing context if there is headroom for the transient projector.
            // File-backed pages are already included in Android's available memory. Never credit them to another model.
            if (loaded != null && store.fileFor(spec).absolutePath == loaded.path && newBudget > 0) {
                minOf((mem.totalBytes * MemoryBudget.HARD_CAP_FRACTION).toLong() - projectorBytes,
                    maxOf(newBudget, MemoryBudget.estimateBytes(spec, loaded.contextTokens)))
            } else newBudget
        }
            ?: return Prep.Unavailable(
                "Не хватает свободной памяти для установленных моделей в режиме ${modeTitle(mode)}. " +
                    "Закройте фоновые приложения или выберите режим Fast.",
            )
        return Prep.Ready(selection, profile, throttle)
    }

    private suspend fun run(
        conversationId: Long,
        mode: ThinkingMode,
        prep: Prep.Ready,
        history: List<StoredMessage>,
        acc: Accumulator,
        persister: Persister,
    ): Outcome {
        val spec = prep.selection.model
        val path = store.fileFor(spec).absolutePath
        try {
            val loaded = engine.loaded
            val reuse = loaded != null && loaded.path == path && loaded.contextTokens >= prep.selection.contextTokens
            if (reuse) {
                engine.setThreads(prep.throttle.decodeThreads, prep.throttle.prefillThreads)
            } else {
                _status.value = ChatStatus.LoadingModel(conversationId, spec.displayName)
                engine.load(
                    LoadParams(path, prep.selection.contextTokens, prep.throttle.decodeThreads, prep.throttle.prefillThreads),
                )
            }
        } catch (e: EngineException) {
            return Outcome(MessageStatus.FAILED, e.message ?: "Не удалось загрузить модель", null)
        } catch (e: LinkageError) {
            return Outcome(MessageStatus.FAILED, "Нативная библиотека движка недоступна: ${e.message}", null)
        }

        _status.value = ChatStatus.Generating(conversationId, spec.displayName, mode)
        val ctx = minOf(engine.loaded?.contextTokens ?: prep.selection.contextTokens, prep.selection.contextTokens)
        val turns = buildTurns(history)
        val thinking = prep.selection.thinking
        val modelLimit = if (spec.tier == ModelTier.LITE) when (mode) {
            ThinkingMode.FAST -> 192
            ThinkingMode.NORMAL -> 384
            ThinkingMode.DEEP -> 640
        } else prep.profile.maxNewTokens
        val outputLimit = minOf(modelLimit, prep.profile.maxNewTokens, ctx / 2)
        val thinkingBudget = if (thinking) minOf(prep.profile.thinkingBudgetTokens, outputLimit / 2) else 0

        var promptBudget = ctx - outputLimit - PROMPT_MARGIN
        var attempt = 0
        while (true) {
            val fitted = HistoryTrimmer.fit(turns, promptBudget.coerceAtLeast(64))
            val prompt = ChatMl.build(fitted, thinking)
            val photo = fitted.lastOrNull { it.photoPath != null }?.photoPath
            val sampling = prep.profile.qwenSampling(thinking, photo != null, spec.tier)
            val request = sampling.toRequest(prompt).copy(maxNewTokens = outputLimit, photoPath = photo,
                projectorPath = photo?.let { store.fileFor(DefaultCatalog.projectors.getValue(spec.id)).absolutePath })
            when (val seg = streamSegment(request, ThinkingFilter(thinking), acc, thinkingBudget, persister)) {
                is Segment.Failed -> {
                    val untouched = acc.answer.isEmpty() && acc.thinking.isEmpty()
                    if (seg.error == EngineError.PROMPT_TOO_LONG && attempt == 0 && untouched) {
                        attempt++
                        promptBudget = promptBudget * 6 / 10
                        continue
                    }
                    return Outcome(MessageStatus.FAILED, seg.message, null)
                }
                is Segment.Finished -> return finished(acc, seg.stats)
                is Segment.Stopped -> return afterStop(seg, request, acc, persister)
            }
        }
    }

    private suspend fun streamSegment(
        request: GenerationRequest,
        filter: ThinkingFilter,
        acc: Accumulator,
        thinkingBudget: Int,
        persister: Persister,
    ): Segment {
        var stats: GenStats? = null
        var failure: GenerationEvent.Failed? = null
        var chunks = 0
        var thinkingChunks = 0
        try {
            engine.generate(request).collect { ev ->
                when (ev) {
                    is GenerationEvent.Text -> {
                        chunks++
                        val out = filter.feed(ev.chunk)
                        if (out.thinking.isNotEmpty()) {
                            acc.thinking.append(out.thinking)
                            thinkingChunks++
                        }
                        if (out.answer.isNotEmpty()) acc.answer.append(out.answer)
                        if (chunks % 16 == 0 && RepetitionGuard.isLoop(acc.answer))
                            throw StopStreaming(StopCause.REPEATING)
                        persister.tick(acc)
                        if (thinkingBudget > 0 && filter.isThinking && thinkingChunks >= thinkingBudget) {
                            throw StopStreaming(StopCause.THINKING_BUDGET)
                        }
                        if (chunks % POWER_CHECK_EVERY == 0 && !PowerPolicy.decide(power.current()).allowLocal) {
                            throw StopStreaming(StopCause.OVERHEATED)
                        }
                    }
                    is GenerationEvent.Finished -> stats = ev.stats
                    is GenerationEvent.Failed -> failure = ev
                }
            }
        } catch (e: StopStreaming) {
            return Segment.Stopped(e.stopCause, chunks)
        }
        val tail = filter.finish()
        acc.thinking.append(tail.thinking)
        acc.answer.append(tail.answer)
        failure?.let { return Segment.Failed(it.error, it.message) }
        return stats?.let { Segment.Finished(it) }
            ?: Segment.Failed(EngineError.INTERNAL, "Движок закрыл поток без результата")
    }

    /**
     * Бюджет рассуждения исчерпан: закрываем блок think сами (промпт уже заканчивался на `<think>\n`) и просим продолжить ответом.
     * Это второй вызов generate с повторным prefill (KV-кэш между вызовами не переиспользуется).
     */
    private suspend fun afterStop(
        seg: Segment.Stopped,
        original: GenerationRequest,
        acc: Accumulator,
        persister: Persister,
    ): Outcome {
        if (seg.cause != StopCause.THINKING_BUDGET) return interrupted(seg.cause)
        val continued = original.prompt + ChatMl.literal(acc.thinking.toString()).trim() + "\n</think>\n\n"
        val remaining = maxOf(MIN_ANSWER_TOKENS, original.maxNewTokens - seg.chunks)
        val request = original.copy(prompt = continued, maxNewTokens = remaining)
        return when (val s = streamSegment(request, ThinkingFilter(false), acc, 0, persister)) {
            is Segment.Finished -> finished(acc, s.stats)
            is Segment.Failed -> Outcome(MessageStatus.FAILED, s.message, null)
            is Segment.Stopped -> interrupted(s.cause)
        }
    }

    private fun interrupted(cause: StopCause): Outcome {
        if (cause == StopCause.REPEATING) return Outcome(MessageStatus.CANCELLED, "Модель зациклилась. Частичный ответ сохранён.", null)
        return overheated()
    }

    private fun overheated() =
        Outcome(MessageStatus.CANCELLED, "Остановлено: телефон перегрелся. Частичный ответ сохранён.", null)

    private fun finished(acc: Accumulator, stats: GenStats): Outcome {
        if (stats.stop == StopReason.CANCELLED)
            return Outcome(MessageStatus.CANCELLED, "Генерация остановлена.", stats)
        if (acc.answer.isBlank()) {
            val why = if (acc.thinking.isNotBlank()) {
                "Лимит токенов ушёл на рассуждение, ответа нет. Попробуйте режим Normal."
            } else {
                "Модель вернула пустой ответ."
            }
            return Outcome(MessageStatus.FAILED, why, stats)
        }
        return Outcome(MessageStatus.COMPLETE, null, stats)
    }

    private fun buildTurns(history: List<StoredMessage>): List<ChatTurn> {
        val turns = ArrayList<ChatTurn>()
        val sys = systemPrompt().trim()
        if (sys.isNotEmpty()) turns.add(ChatTurn(Role.SYSTEM, sys))
        val lastPhoto = history.lastOrNull { it.role == Role.USER && it.attachmentKind == "PHOTO" && it.attachmentPath != null }
        for (m in history) {
            val usable = when (m.role) {
                Role.USER -> true
                Role.ASSISTANT -> m.status == MessageStatus.COMPLETE && m.content.isNotBlank()
                Role.SYSTEM -> false
            }
            if (usable) turns.add(ChatTurn(m.role, m.content, if (m === lastPhoto) m.attachmentPath else null))
        }
        return turns
    }

    private fun titleFrom(text: String): String {
        val line = text.lineSequence().first().trim()
        return if (line.isEmpty()) DEFAULT_TITLE else line.take(48)
    }

    private fun modeTitle(mode: ThinkingMode): String = when (mode) {
        ThinkingMode.FAST -> "Fast"
        ThinkingMode.NORMAL -> "Normal"
        ThinkingMode.DEEP -> "Deep"
    }

    companion object {
        const val DEFAULT_TITLE = "Новый чат"
        const val DEFAULT_PHOTO_QUESTION = "Что на этом фото? Опиши и проанализируй."
        private const val POWER_CHECK_EVERY = 32
        private const val PROMPT_MARGIN = 16
        private const val MIN_ANSWER_TOKENS = 256
    }
}
