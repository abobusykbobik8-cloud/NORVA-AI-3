package ai.norva.app.media

import ai.norva.chat.MemoryInfoProvider
import ai.norva.chat.PowerStateProvider
import ai.norva.inference.ModelStore
import ai.norva.policy.MediaCatalog
import ai.norva.policy.MemoryBudget.GIB
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.File
import java.io.IOException

class LocalDiffusion(
    private val context: Context,
    private val store: ModelStore,
    private val memory: MemoryInfoProvider,
    private val power: PowerStateProvider,
) {
    private fun checkPressure() {
        check(!memory.current().lowMemory) { "Android сообщает о нехватке памяти" }
        check(power.current().thermalStatus < 3) { "Генерация остановлена из-за нагрева" }
    }

    suspend fun generate(prompt: String, video: Boolean, photo: String?, output: File, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val models = MediaCatalog.required(video)
        val missing = models.filterNot(store::isInstalled)
        require(missing.isEmpty()) { "Скачайте в Model Hub: " + missing.joinToString { it.displayName } }
        val text = prompt.trim().replace(Regex("[\\r\\n\\u0000]+"), " ")
        require(text.isNotBlank() && text.toByteArray().size <= 8192) { "Введите запрос до 8192 байт" }
        val mem = memory.current()
        checkPressure()
        val minimum = if (video) 6 * GIB else 3 * GIB
        check(mem.availBytes - mem.lowThreshold >= minimum) { "Освободите память. Ориентир для запуска: ${minimum / GIB} ГиБ сверх системного резерва." }
        val binary = File(context.applicationInfo.nativeLibraryDir, "libnorva_diffusion.so")
        check(binary.canExecute()) { "Локальный движок отсутствует в установленном APK" }
        val raw = File.createTempFile("diffusion-", ".rgb", context.cacheDir)
        var reference: File? = null
        try {
            if (photo != null && video) {
                reference = File.createTempFile("reference-", ".rgb", context.cacheDir)
                writeReference(photo, reference)
            }
            progress("Загрузка " + if (video) "Wan 2.1…" else "Stable Diffusion…")
            val cmd = listOf(binary.path, if (video) "video" else "image", raw.path) + models.map { store.fileFor(it).path } + listOfNotNull(reference?.path)
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            coroutineScope {
                try {
                    var failure: String? = null
                    val reader = launch(Dispatchers.IO) {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                if (line.startsWith("STEP ")) progress("Локальная генерация · шаг " + line.removePrefix("STEP ").replace(' ', '/'))
                                if (line.startsWith("ENGINE ")) failure = line.removePrefix("ENGINE ").take(200)
                            }
                        }
                    }
                    process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.appendLine(text) }
                    while (process.isAlive) {
                        delay(600)
                        checkPressure()
                    }
                    reader.join()
                    check(process.exitValue() == 0) { failure ?: "Движок завершился с кодом ${process.exitValue()}. Проверьте память и файлы модели." }
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                }
            }
            progress(if (video) "Кодирование MP4…" else "Сохранение PNG…")
            encodeFrames(raw, video, output)
        } finally {
            reference?.delete()
            raw.delete()
        }
    }

    private fun writeReference(path: String, dst: File) {
        val source = android.graphics.BitmapFactory.decodeFile(path) ?: error("Не удалось открыть прикреплённое фото")
        try {
            val square = android.media.ThumbnailUtils.extractThumbnail(source, 256, 256)
            try {
                val pixels = IntArray(256 * 256)
                square.getPixels(pixels, 0, 256, 0, 0, 256, 256)
                dst.outputStream().buffered().use { out ->
                    for (color in pixels) { out.write(color ushr 16); out.write(color ushr 8); out.write(color) }
                }
            } finally { if (square !== source) square.recycle() }
        } finally { source.recycle() }
    }

    internal suspend fun encodeFrames(raw: File, video: Boolean, output: File) {
        val side = if (video) 256 else 384
        val count = if (video) 9 else 1
        val packed = ByteArray(side * side * 3)
        require(raw.length() == packed.size.toLong() * count) { "Движок вернул неполный набор кадров" }
        val pixels = IntArray(side * side)
        val bitmap = if (video) null else Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        try {
            val writer = if (video) Mp4Writer(output, side, side, 6, exactFrames = true) else null
            try {
                writer?.start()
                DataInputStream(raw.inputStream().buffered()).use { input ->
                    repeat(count) {
                        currentCoroutineContext().ensureActive()
                        input.readFully(packed)
                        var p = 0
                        for (i in pixels.indices) {
                            val r = packed[p++].toInt() and 255
                            val g = packed[p++].toInt() and 255
                            val b = packed[p++].toInt() and 255
                            pixels[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                        }
                        if (writer != null) writer.writePixels(pixels)
                        else {
                            val image = checkNotNull(bitmap)
                            image.setPixels(pixels, 0, side, 0, 0, side, side)
                            output.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        }
                    }
                }
                writer?.finish()
                if (output.length() == 0L) throw IOException("Пустой файл результата")
            } catch (e: Throwable) {
                writer?.abort()
                throw e
            }
        } finally {
            bitmap?.recycle()
        }
    }
}
