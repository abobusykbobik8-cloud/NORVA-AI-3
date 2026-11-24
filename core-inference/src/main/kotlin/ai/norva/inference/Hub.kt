package ai.norva.inference

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

class HubException(message: String) : IOException(message)

data class RemoteFile(
    val repo: String,
    val path: String,
    val sizeBytes: Long,
    /** SHA-256 в нижнем регистре, берётся из lfs.oid Hugging Face. */
    val sha256: String,
    val url: String,
)

@Serializable
private data class TreeEntry(
    val type: String = "",
    val path: String = "",
    val size: Long = 0,
    val lfs: LfsInfo? = null,
)

@Serializable
private data class LfsInfo(val oid: String = "", val size: Long = 0)

/** Реальные размер и SHA-256 берём у Hugging Face, а не зашиваем в приложение. */
class HfHub(
    private val http: HttpFetcher = JdkHttpFetcher(),
    private val baseUrl: String = "https://huggingface.co",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val sha256Regex = Regex("^[0-9a-fA-F]{64}$")

    fun resolve(repo: String, fileName: String): RemoteFile {
        val body = http.getText("$baseUrl/api/models/$repo/tree/main")
        val entries = try {
            json.decodeFromString(ListSerializer(TreeEntry.serializer()), body)
        } catch (e: SerializationException) {
            throw HubException("Неожиданный ответ Hugging Face: ${e.message}")
        }
        val entry = entries.firstOrNull { it.type == "file" && it.path == fileName }
            ?: throw HubException("Файл $fileName не найден в $repo")
        val lfs = entry.lfs ?: throw HubException("У файла нет LFS-хеша SHA-256")
        if (!sha256Regex.matches(lfs.oid)) throw HubException("Некорректный SHA-256 у $fileName")
        val size = if (lfs.size > 0) lfs.size else entry.size
        if (size <= 0) throw HubException("Некорректный размер файла")
        return RemoteFile(repo, entry.path, size, lfs.oid.lowercase(), "$baseUrl/$repo/resolve/main/${entry.path}")
    }
}
