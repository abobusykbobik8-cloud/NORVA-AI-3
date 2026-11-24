package ai.norva.chat

import ai.norva.inference.Role
import ai.norva.policy.DeviceMemory
import ai.norva.policy.PowerState
import ai.norva.policy.ThinkingMode
import kotlinx.coroutines.flow.Flow

enum class MessageStatus { COMPLETE, STREAMING, CANCELLED, FAILED }

data class Conversation(val id: Long, val title: String, val createdAt: Long, val updatedAt: Long)

data class StoredMessage(
    val id: Long = 0,
    val conversationId: Long,
    val role: Role,
    val content: String,
    val thinking: String = "",
    val status: MessageStatus = MessageStatus.COMPLETE,
    val mode: ThinkingMode? = null,
    val modelId: String? = null,
    val tokensPerSecond: Double? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
    /** PHOTO (снимок пользователя), IMAGE или VIDEO (созданы встроенным генератором). */
    val attachmentKind: String? = null,
    val attachmentPath: String? = null,
    /** Текстовый анализ снимка модулем NØRVA Vision: не показывается в чате, но передаётся чат-модели. */
    val attachmentNote: String? = null,
)

data class Attachment(val kind: String, val path: String, val note: String)

/** История - единственный источник правды. Реализация на Room лежит в :app, тестовая - в тестах. */
interface ChatRepository {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(conversationId: Long): Flow<List<StoredMessage>>
    suspend fun createConversation(title: String): Long
    suspend fun renameConversation(id: Long, title: String)
    suspend fun deleteConversation(id: Long)
    suspend fun getConversation(id: Long): Conversation?
    suspend fun getMessages(conversationId: Long): List<StoredMessage>
    /** Поле id игнорируется, возвращается присвоенный id. */
    suspend fun addMessage(message: StoredMessage): Long
    suspend fun updateMessage(message: StoredMessage)
    suspend fun deleteMessage(id: Long)
    /** Ответы, оставшиеся в статусе STREAMING после закрытия приложения посреди генерации. */
    suspend fun failInterruptedMessages()
}

interface PowerStateProvider {
    fun current(): PowerState
}

interface MemoryInfoProvider {
    fun current(): DeviceMemory
}
