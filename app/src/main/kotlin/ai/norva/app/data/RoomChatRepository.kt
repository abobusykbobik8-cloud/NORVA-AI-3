package ai.norva.app.data

import ai.norva.chat.ChatRepository
import ai.norva.chat.Conversation
import ai.norva.chat.MessageStatus
import ai.norva.chat.StoredMessage
import ai.norva.inference.Role
import ai.norva.policy.ThinkingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomChatRepository(
    private val dao: ChatDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ChatRepository {

    override fun observeConversations(): Flow<List<Conversation>> =
        dao.observeConversations().map { list -> list.map { it.toModel() } }

    override fun observeMessages(conversationId: Long): Flow<List<StoredMessage>> =
        dao.observeMessages(conversationId).map { list -> list.map { it.toModel() } }

    override suspend fun createConversation(title: String): Long {
        val now = clock()
        return dao.insertConversation(ConversationEntity(title = title, createdAt = now, updatedAt = now))
    }

    override suspend fun renameConversation(id: Long, title: String) = dao.rename(id, title, clock())

    override suspend fun deleteConversation(id: Long) = dao.deleteConversation(id)

    override suspend fun getConversation(id: Long): Conversation? = dao.getConversation(id)?.toModel()

    override suspend fun getMessages(conversationId: Long): List<StoredMessage> =
        dao.getMessages(conversationId).map { it.toModel() }

    override suspend fun addMessage(message: StoredMessage): Long {
        val id = dao.insertMessage(message.toEntity().copy(id = 0))
        dao.touch(message.conversationId, clock())
        return id
    }

    override suspend fun updateMessage(message: StoredMessage) = dao.updateMessage(message.toEntity())

    override suspend fun deleteMessage(id: Long) = dao.deleteMessage(id)

    override suspend fun failInterruptedMessages() =
        dao.failInterrupted("Прервано: приложение было закрыто во время ответа")
}

private fun ConversationEntity.toModel() = Conversation(id, title, createdAt, updatedAt)

private fun MessageEntity.toModel() = StoredMessage(
    id = id,
    conversationId = conversationId,
    role = Role.valueOf(role),
    content = content,
    thinking = thinking,
    status = MessageStatus.valueOf(status),
    mode = mode?.let { ThinkingMode.valueOf(it) },
    modelId = modelId,
    tokensPerSecond = tokensPerSecond,
    errorMessage = errorMessage,
    createdAt = createdAt,
)

private fun StoredMessage.toEntity() = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role.name,
    content = content,
    thinking = thinking,
    status = status.name,
    mode = mode?.name,
    modelId = modelId,
    tokensPerSecond = tokensPerSecond,
    errorMessage = errorMessage,
    createdAt = createdAt,
)
