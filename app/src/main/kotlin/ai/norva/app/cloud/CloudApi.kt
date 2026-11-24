package ai.norva.app.cloud

import ai.norva.inference.ChatTurn
import ai.norva.policy.ThinkingMode
import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class CloudConfig(
    val chatEnabled: Boolean = false,
    val base: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val chatKey: String = "",
    val mediaKey: String = "",
)

private fun secureUrl(value: String): URL {
    val uri = URI(value)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
        "Нужен HTTPS-адрес API"
    }
    return uri.toURL()
}

class CloudApi(context: Context) {
    private val prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)
    private val initial = CloudConfig()
    private val state = MutableStateFlow(initial.copy(base = prefs.getString("base", initial.base)!!,
        model = prefs.getString("model", initial.model)!!))
    val config = state.asStateFlow()

    fun configure(c: CloudConfig) {
        secureUrl(c.base)
        require(!c.chatEnabled || (c.chatKey.isNotBlank() && c.model.isNotBlank())) { "Введите модель и ключ чата" }
        prefs.edit().putString("base", c.base).putString("model", c.model).apply()
        state.value = c
    }

    private fun connection(url: URL, auth: String?): HttpsURLConnection =
        (url.openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15000
            readTimeout = 120000
            if (auth != null) setRequestProperty("Authorization", auth)
        }

    // Called from Dispatchers.IO. Interrupt a blocked HTTPS read immediately when "Stop" cancels the chat.
    private suspend fun <T> request(url: URL, auth: String?, read: (HttpsURLConnection) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val connection = connection(url, auth)
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                if (continuation.isActive) continuation.resumeWith(runCatching { read(connection) })
            } finally {
                connection.disconnect()
            }
        }

    private suspend fun json(url: URL, auth: String, body: JSONObject? = null): JSONObject =
        request(url, auth) { c ->
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            if (c.responseCode !in 200..299) throw IOException("Облачный API: HTTP ${c.responseCode}")
            val jsonText = c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length + count > 2 * 1024 * 1024) throw IOException("Слишком большой ответ API")
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            JSONObject(jsonText)
        }

    private fun photoUrl(path: String): String {
        val file = File(path)
        require(file.length() in 1..8_000_000) { "Фото недоступно или слишком велико" }
        return "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    suspend fun chat(turns: List<ChatTurn>, mode: ThinkingMode): String = withContext(Dispatchers.IO) {
        val c = state.value
        require(c.chatEnabled && c.chatKey.isNotBlank())
        val messages = JSONArray()
        for (turn in turns) {
            val message = JSONObject().put("role", turn.role.name.lowercase())
            val path = turn.photoPath
            if (path == null) message.put("content", turn.content)
            else message.put("content", JSONArray().put(JSONObject().put("type","text").put("text",turn.content))
                .put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url",photoUrl(path)))))
            messages.put(message)
        }
        val body = JSONObject().put("model",c.model).put("messages",messages)
            .put("max_tokens", ai.norva.policy.ModeProfiles.of(mode).maxNewTokens)
        val result = json(secureUrl(c.base.trimEnd('/') + "/chat/completions"), "Bearer " + c.chatKey, body)
        val reply = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        require(reply.isNotBlank()) { "Провайдер вернул пустой ответ" }
        reply
    }

    suspend fun generate(video: Boolean, prompt: String, photo: String?, dest: File, progress: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val token = state.value.mediaKey
            require(token.isNotBlank()) { "Введите ключ fal.ai в настройках" }
            val model = when {
                !video -> "fal-ai/flux/schnell"
                photo == null -> "fal-ai/wan/v2.2-a14b/text-to-video"
                else -> "fal-ai/wan/v2.2-a14b/image-to-video"
            }
            val auth = "Key " + token
            val body = JSONObject().put("prompt",prompt)
            if (!video) body.put("output_format","png")
            if (photo != null && video) body.put("image_url",photoUrl(photo))
            val task = json(secureUrl("https://queue.fal.run/" + model), auth, body)
            fun queueUrl(key: String) = secureUrl(task.getString(key)).also {
                require(it.host == "queue.fal.run" && it.port in listOf(-1,443)) { "Неизвестный адрес очереди" }
            }
            val status = queueUrl("status_url")
            val result = queueUrl("response_url")
            val started = android.os.SystemClock.elapsedRealtime()
            while (true) {
                currentCoroutineContext().ensureActive()
                if (android.os.SystemClock.elapsedRealtime() - started > 30 * 60_000) throw IOException("Истекло время ожидания задачи " + task.optString("request_id"))
                val info = json(status,auth)
                when (info.getString("status")) {
                    "IN_QUEUE" -> progress("В очереди · позиция " + info.optString("queue_position","—"))
                    "IN_PROGRESS" -> progress("Модель создаёт " + if (video) "видео…" else "изображение…")
                    "COMPLETED" -> break
                    else -> throw IOException("Статус задачи: " + info.optString("status"))
                }
                delay(3000)
            }
            val data = json(result,auth)
            val media = if (video) data.getJSONObject("video") else data.getJSONArray("images").getJSONObject(0)
            val job = currentCoroutineContext()
            request(secureUrl(media.getString("url")), null) { c ->
                if (c.responseCode !in 200..299) throw IOException("Скачивание: HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                var received = 0L
                val buffer = ByteArray(64 * 1024)
                c.inputStream.use { input -> dest.outputStream().use { output ->
                    while (true) {
                        job.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        received += n
                        require(received <= 512L * 1024 * 1024) { "Файл больше 512 МиБ" }
                        output.write(buffer,0,n)
                        progress(if (total > 0) "Скачивание ${100 * received / total}%" else "Скачано ${received / 1024} КиБ")
                    }
                } }
                require(received > 0 && (total < 0 || total == received)) { "Загрузка прервалась" }
            }
        }
}
