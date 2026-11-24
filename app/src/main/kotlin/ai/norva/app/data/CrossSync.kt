package ai.norva.app.data

import ai.norva.app.platform.SettingsStore
import ai.norva.inference.SyncCipher
import ai.norva.policy.ThinkingMode
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Append-only exchange of completed text turns. Local deletions keep their mapping as a tombstone. */
class CrossSync(private val db: NorvaDatabase, private val settings: SettingsStore) {
    private val dao get() = db.chatDao()
    private suspend fun origin(kind: String, id: Long): String {
        dao.linkForId(kind,id)?.let { return it.origin }
        val uuid = UUID.randomUUID().toString()
        dao.insertLink(SyncLink(kind,id,uuid))
        return uuid
    }

    suspend fun export(password: CharArray): ByteArray {
        val chats = db.withTransaction {
            val records = JSONArray()
            var chars = 0L
            for (chat in dao.snapshotConversations()) {
                val turns = JSONArray()
                for (m in dao.getMessages(chat.id)) {
                    if (m.status != "COMPLETE" || m.content.isBlank()) continue
                    chars += m.content.length
                    require(chars <= SyncCipher.LIMIT) { "История превышает 8 МиБ" }
                    turns.put(JSONObject().put("id", origin("message",m.id)).put("role",m.role)
                        .put("text",m.content).put("time",m.createdAt))
                }
                records.put(JSONObject().put("id",origin("chat",chat.id)).put("title",chat.title)
                    .put("time",chat.createdAt).put("messages",turns))
            }
            records
        }
        val prefs = JSONObject().put("prompt",settings.systemPrompt).put("mode",settings.mode.name).put("turbo",settings.turbo)
        return SyncCipher.seal(password, JSONObject().put("version",1).put("chats",chats).put("settings",prefs).toString().toByteArray())
    }

    suspend fun merge(password: CharArray, bytes: ByteArray, applySettings: Boolean): Int {
        val root = JSONObject(String(SyncCipher.open(password,bytes),Charsets.UTF_8))
        require(root.getInt("version")==1)
        val prefs = root.getJSONObject("settings")
        val mode = ThinkingMode.valueOf(prefs.getString("mode"))
        val prompt = prefs.getString("prompt")
        val turbo = prefs.getBoolean("turbo")
        val chats = root.getJSONArray("chats")
        fun id(value: JSONObject) = UUID.fromString(value.getString("id")).toString()
        val imported = db.withTransaction {
            var added = 0
            for (i in 0 until chats.length()) {
                val c = chats.getJSONObject(i)
                val cid = id(c)
                val existing = dao.linkForOrigin("chat",cid)
                val time = c.getLong("time")
                val local = existing?.localId ?: dao.insertConversation(ConversationEntity(title=c.getString("title").take(300),createdAt=time,updatedAt=time))
                    .also { dao.insertLink(SyncLink("chat",it,cid)) }
                val chat = dao.getConversation(local) ?: continue
                var newest = chat.updatedAt
                val messages = c.getJSONArray("messages")
                for (j in 0 until messages.length()) {
                    val m = messages.getJSONObject(j)
                    val mid = id(m)
                    if (dao.linkForOrigin("message",mid)!=null) continue
                    val role = m.getString("role")
                    require(role == "USER" || role == "ASSISTANT") { "Недопустимая роль" }
                    val timestamp = m.getLong("time")
                    val key = dao.insertMessage(MessageEntity(conversationId=local,role=role,content=m.getString("text"),thinking="",
                        status="COMPLETE",mode=null,modelId=null,tokensPerSecond=null,errorMessage=null,createdAt=timestamp))
                    dao.insertLink(SyncLink("message",key,mid))
                    newest = maxOf(newest,timestamp)
                    added++
                }
                dao.touch(local,newest)
            }
            added
        }
        if (applySettings) { settings.systemPrompt=prompt; settings.mode=mode; settings.turbo=turbo }
        return imported
    }
}
