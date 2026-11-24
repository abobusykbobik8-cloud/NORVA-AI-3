package ai.norva.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val thinking: String,
    val status: String,
    val mode: String?,
    val modelId: String?,
    val tokensPerSecond: Double?,
    val errorMessage: String?,
    val createdAt: Long,
    val attachmentKind: String? = null,
    val attachmentPath: String? = null,
    val attachmentNote: String? = null,
)

@Entity(tableName = "sync_links", primaryKeys = ["kind", "localId"], indices = [Index(value = ["kind", "origin"], unique = true)])
data class SyncLink(val kind: String, val localId: Long, val origin: String)

@Dao
interface ChatDao {
    @Query("SELECT * FROM sync_links WHERE kind=:kind AND localId=:id")
    suspend fun linkForId(kind: String, id: Long): SyncLink?
    @Query("SELECT * FROM sync_links WHERE kind=:kind AND origin=:origin")
    suspend fun linkForOrigin(kind: String, origin: String): SyncLink?
    @Insert suspend fun insertLink(link: SyncLink)
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT DISTINCT conversationId FROM messages WHERE attachmentKind IN ('PHOTO','IMAGE','VIDEO') AND attachmentPath IS NOT NULL")
    fun conversationsWithMedia(): Flow<List<Long>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: Long): ConversationEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun getMessages(conversationId: Long): List<MessageEntity>

    @Insert
    suspend fun insertConversation(entity: ConversationEntity): Long

    @Insert
    suspend fun insertMessage(entity: MessageEntity): Long

    @Update
    suspend fun updateMessage(entity: MessageEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long)

    @Query("UPDATE conversations SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("UPDATE messages SET status = 'FAILED', errorMessage = :error WHERE status = 'STREAMING'")
    suspend fun failInterrupted(error: String)
}

@Database(entities = [ConversationEntity::class, MessageEntity::class, SyncLink::class], version = 3, exportSchema = false)
abstract class NorvaDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}

/** Версия 2: вложения сообщений (фото, созданные картинки и видео). История чатов сохраняется. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentKind TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentPath TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentNote TEXT")
    }
}


val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_links (kind TEXT NOT NULL, localId INTEGER NOT NULL, origin TEXT NOT NULL, PRIMARY KEY(kind, localId))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_links_kind_origin ON sync_links(kind, origin)")
    }
}
