package ai.norva.app.media

import ai.norva.chat.ChatRepository
import ai.norva.chat.MessageStatus
import ai.norva.chat.StoredMessage
import ai.norva.inference.Role
import ai.norva.vision.ImageAnalyzer
import ai.norva.vision.ProceduralArt
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Фото и видео NØRVA: анализ снимков (NØRVA Vision) и генерация картинок и роликов (NØRVA Canvas).
 * Вся логика изображений написана с нуля в модуле core-vision; здесь только работа с файлами, кодеком и историей чата.
 */
class MediaStudio(
    private val context: Context,
    private val repo: ChatRepository,
    private val dir: File,
    private val cloud: ai.norva.app.cloud.CloudApi,
    private val diffusion: LocalDiffusion,
    private val orchestrator: ai.norva.chat.ChatOrchestrator,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    class Imported(val path: String, val note: String)

    /** Читает снимок (камера или галерея), сохраняет уменьшенную копию и считает анализ. */
    suspend fun importPhoto(uri: Uri): Imported = withContext(Dispatchers.IO) {
        _busy.value = "Подготовка фото…"
        try {
            val bitmap = ImageIo.decode(context, uri, 1280)
            val note = ImageAnalyzer.describeRu(ImageAnalyzer.analyze(ImageIo.toRgb(bitmap)))
            dir.mkdirs()
            val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            ImageIo.saveJpeg(bitmap, file)
            Imported(file.absolutePath, note)
        } finally {
            _busy.value = null
        }
    }

    suspend fun generateImage(conversationId: Long, prompt: String) =
        produce(conversationId, prompt, "IMAGE", "png", "Картинка", IMAGE_NOTE) { file ->
            val img = ProceduralArt.render(prompt, 1024, 1024, 0f)
            ImageIo.savePng(ImageIo.toBitmap(img), file)
        }

    suspend fun generateVideo(conversationId: Long, prompt: String) =
        produce(conversationId, prompt, "VIDEO", "mp4", "Видео", VIDEO_NOTE) { file ->
            val look = ProceduralArt.look(prompt)
            val frames = FPS * SECONDS
            val writer = Mp4Writer(file, VIDEO_W, VIDEO_H, FPS)
            try {
                writer.start()
                for (i in 0 until frames) {
                    currentCoroutineContext().ensureActive()
                    val frame = ProceduralArt.render(look, VIDEO_W / 2, VIDEO_H / 2, i / frames.toFloat())
                    writer.writeFrame(ImageIo.toBitmap(frame))
                    _busy.value = "Видео: ${(i + 1) * 100 / frames}%"
                }
                writer.finish()
            } catch (e: Throwable) {
                writer.abort()
                throw e
            }
        }

    suspend fun neural(id: Long, prompt: String, video: Boolean, photo: String?) =
        produce(id, prompt, if (video) "VIDEO" else "IMAGE", if (video) "mp4" else "png",
            if (video) "Видео · Wan" else "Изображение · FLUX", if (video) "Видео Wan" else "Изображение FLUX") { file ->
            cloud.generate(video, prompt, photo, file) { _busy.value = it }
        }

    private var localJob: kotlinx.coroutines.Job? = null
    val localRunning = MutableStateFlow(false)

    @Synchronized fun startLocal(id: Long, prompt: String, video: Boolean, photo: String? = null): Boolean {
        if (localJob?.isActive == true || busy.value != null) return false
        localRunning.value = true
        localJob = scope.launch {
            try {
                val label = if (video) "Локальное видео · Wan 2.1" else "Локальное изображение · SD 1.5"
                produce(id, prompt, if (video) "VIDEO" else "IMAGE", if (video) "mp4" else "png", label, label) { file ->
                    orchestrator.withLocalEngine {
                        diffusion.generate(prompt, video, photo, file) { _busy.value = it }
                    }
                }
            } finally { localRunning.value = false }
        }
        return true
    }

    fun stopLocal() { localJob?.cancel() }

    private suspend fun produce(
        conversationId: Long,
        prompt: String,
        kind: String,
        ext: String,
        label: String,
        note: String,
        render: suspend (File) -> Unit,
    ) {
        val clean = prompt.trim()
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        if (repo.getMessages(conversationId).isEmpty()) repo.renameConversation(conversationId, clean.take(48))
        repo.addMessage(StoredMessage(conversationId = conversationId, role = Role.USER, content = "$label: $clean", createdAt = now))
        var msg = StoredMessage(
            conversationId = conversationId, role = Role.ASSISTANT, content = "",
            status = MessageStatus.STREAMING, createdAt = now,
        )
        msg = msg.copy(id = repo.addMessage(msg))
        dir.mkdirs()
        val file = File(dir, "${kind.lowercase()}_${msg.id}.$ext")
        _busy.value = "$label…"
        var result = msg.copy(status = MessageStatus.FAILED, errorMessage = "Не удалось создать")
        try {
            withContext(Dispatchers.Default) { render(file) }
            result = msg.copy(content = note, status = MessageStatus.COMPLETE, attachmentKind = kind, attachmentPath = file.absolutePath)
        } catch (e: CancellationException) {
            result = msg.copy(status = MessageStatus.CANCELLED, errorMessage = "Создание остановлено")
            throw e
        } catch (e: Exception) {
            result = msg.copy(status = MessageStatus.FAILED, errorMessage = e.message ?: "Не удалось создать")
        } finally {
            withContext(NonCancellable) {
                if (result.status != MessageStatus.COMPLETE) file.delete()
                repo.updateMessage(result)
                _busy.value = null
            }
        }
    }

    /** Копирует файл в галерею (Pictures/NORVA или Movies/NORVA). Разрешения не нужны: используется MediaStore. */
    suspend fun saveToGallery(path: String, kind: String): Boolean = withContext(Dispatchers.IO) {
        val src = File(path)
        if (!src.isFile) return@withContext false
        val video = kind == "VIDEO"
        val mime = when {
            video -> "video/mp4"
            src.extension == "png" -> "image/png"
            else -> "image/jpeg"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/NORVA",
            )
        }
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return@withContext false
        var complete = false
        try {
            val out = resolver.openOutputStream(uri) ?: return@withContext false
            out.use { stream -> src.inputStream().use { it.copyTo(stream) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            complete = resolver.update(uri, values, null, null) == 1
            complete
        } catch (e: java.io.IOException) {
            false
        } finally {
            if (!complete) resolver.delete(uri, null, null)
        }
    }

    companion object {
        private const val FPS = 24
        private const val SECONDS = 4
        private const val VIDEO_W = 576
        private const val VIDEO_H = 1024
        private const val IMAGE_NOTE =
            "Создано встроенным генератором NØRVA Canvas. Это не нейросеть: смысл запроса он не понимает, " +
                "а превращает текст в палитру и узор. Один и тот же запрос даёт ту же картинку."
        private const val VIDEO_NOTE =
            "Создано встроенным генератором NØRVA Canvas: зацикленная анимация 4 с. Это не нейросеть: " +
                "текст задаёт палитру и узор, а не сюжет."
    }
}
