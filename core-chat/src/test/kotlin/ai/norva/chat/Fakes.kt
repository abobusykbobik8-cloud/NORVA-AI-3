package ai.norva.chat

import ai.norva.inference.GenStats
import ai.norva.inference.GenerationEvent
import ai.norva.inference.GenerationRequest
import ai.norva.inference.InferenceEngine
import ai.norva.inference.LoadParams
import ai.norva.inference.LoadedModel
import ai.norva.inference.StopReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Тестовый двойник движка: отдаёт заранее заданные потоки событий. Используется только в тестах. */
class FakeEngine : InferenceEngine {
    private var current: LoadedModel? = null
    val loadCalls = mutableListOf<LoadParams>()
    val requests = mutableListOf<GenerationRequest>()
    private val scripts = ArrayDeque<() -> Flow<GenerationEvent>>()

    override val loaded: LoadedModel? get() = current

    fun script(vararg events: GenerationEvent) {
        val list = events.toList()
        scripts.addLast { flow { list.forEach { emit(it) } } }
    }

    fun scriptFlow(make: () -> Flow<GenerationEvent>) {
        scripts.addLast(make)
    }

    override suspend fun load(params: LoadParams) {
        loadCalls.add(params)
        current = LoadedModel(params.modelPath, params.contextTokens)
    }

    override fun setThreads(decode: Int, prefill: Int) {}

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> {
        requests.add(request)
        return scripts.removeFirst().invoke()
    }

    override fun cancel() {}

    override suspend fun unload() {
        current = null
    }
}

fun text(s: String) = GenerationEvent.Text(s)

fun finished(tokens: Int = 5) =
    GenerationEvent.Finished(GenStats(10, tokens, 100, 100, StopReason.END_OF_TEXT))

class FakeRepo : ChatRepository {
    private val convs = MutableStateFlow<List<Conversation>>(emptyList())
    private val msgs = MutableStateFlow<List<StoredMessage>>(emptyList())
    private var nextId = 1L

    override fun observeConversations(): Flow<List<Conversation>> = convs
    override fun observeMessages(conversationId: Long): Flow<List<StoredMessage>> =
        msgs.map { list -> list.filter { it.conversationId == conversationId } }

    override suspend fun createConversation(title: String): Long {
        val id = nextId++
        convs.value = convs.value + Conversation(id, title, 0, 0)
        return id
    }

    override suspend fun renameConversation(id: Long, title: String) {
        convs.value = convs.value.map { if (it.id == id) it.copy(title = title) else it }
    }

    override suspend fun deleteConversation(id: Long) {
        convs.value = convs.value.filter { it.id != id }
        msgs.value = msgs.value.filter { it.conversationId != id }
    }

    override suspend fun getConversation(id: Long): Conversation? = convs.value.firstOrNull { it.id == id }

    override suspend fun getMessages(conversationId: Long): List<StoredMessage> =
        msgs.value.filter { it.conversationId == conversationId }.sortedBy { it.id }

    override suspend fun addMessage(message: StoredMessage): Long {
        val id = nextId++
        msgs.value = msgs.value + message.copy(id = id)
        return id
    }

    override suspend fun updateMessage(message: StoredMessage) {
        msgs.value = msgs.value.map { if (it.id == message.id) message else it }
    }

    override suspend fun deleteMessage(id: Long) {
        msgs.value = msgs.value.filter { it.id != id }
    }

    override suspend fun failInterruptedMessages() {
        msgs.value = msgs.value.map {
            if (it.status == MessageStatus.STREAMING) it.copy(status = MessageStatus.FAILED) else it
        }
    }
}
