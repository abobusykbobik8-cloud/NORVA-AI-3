package ai.norva.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class DownloadError { NO_SPACE, HTTP, CHECKSUM, IO }

sealed interface DownloadEvent {
    data class Progress(val downloadedBytes: Long, val totalBytes: Long, val bytesPerSecond: Long) : DownloadEvent
    data object Verifying : DownloadEvent
    data class Done(val file: File) : DownloadEvent
    data class Failed(val error: DownloadError, val message: String) : DownloadEvent
}

/**
 * Докачка через Range, проверка SHA-256, атомарная замена. Отмена корутины оставляет .part для продолжения.
 * Файл появляется под своим именем только после успешной проверки.
 */
class ModelDownloader(
    private val http: HttpFetcher = JdkHttpFetcher(),
    private val bufferSize: Int = 256 * 1024,
    private val progressIntervalMs: Long = 250,
    private val spaceMarginBytes: Long = 256L * 1024 * 1024,
) {
    fun download(file: RemoteFile, dest: File): Flow<DownloadEvent> = flow {
        val part = File(dest.path + ".part")
        val dir = dest.absoluteFile.parentFile
        dir?.mkdirs()
        var have = if (part.exists()) part.length() else 0L
        if (have > file.sizeBytes) {
            part.delete()
            have = 0L
        }
        val free = dir?.usableSpace ?: Long.MAX_VALUE
        if (file.sizeBytes - have + spaceMarginBytes > free) {
            val needMb = (file.sizeBytes - have) / 1_000_000
            emit(DownloadEvent.Failed(DownloadError.NO_SPACE, "Недостаточно места: нужно ещё $needMb МБ"))
            return@flow
        }
        if (have < file.sizeBytes) fetch(file, part, have, allowRestart = true)

        emit(DownloadEvent.Verifying)
        val actual = sha256Hex(part)
        if (actual != file.sha256.lowercase()) {
            part.delete()
            emit(DownloadEvent.Failed(DownloadError.CHECKSUM, "Контрольная сумма не совпала, файл удалён"))
            return@flow
        }
        Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        File(dest.path + ".sha256").writeText(actual)
        emit(DownloadEvent.Done(dest))
    }.catch { e ->
        when (e) {
            is HttpStatusException -> emit(DownloadEvent.Failed(DownloadError.HTTP, "Сервер ответил ${e.code}"))
            is IOException -> emit(DownloadEvent.Failed(DownloadError.IO, e.message ?: "Ошибка ввода-вывода"))
            else -> throw e
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<DownloadEvent>.fetch(
        file: RemoteFile,
        part: File,
        startAt: Long,
        allowRestart: Boolean,
    ) {
        http.open(file.url, startAt).use { resp ->
            val append = when (resp.code) {
                206 -> true
                200 -> false // сервер проигнорировал Range: начинаем файл заново
                416 -> {
                    if (!allowRestart) throw HttpStatusException(416)
                    part.delete()
                    fetch(file, part, 0L, allowRestart = false)
                    return
                }
                else -> throw HttpStatusException(resp.code)
            }
            var done = if (append) startAt else 0L
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(bufferSize)
                var lastTick = System.nanoTime()
                var lastDone = done
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = resp.body.read(buf)
                    if (n < 0) break
                    if (n > file.sizeBytes - done) throw IOException("Сервер превысил заявленный размер файла")
                    out.write(buf, 0, n)
                    done += n
                    val now = System.nanoTime()
                    if ((now - lastTick) / 1_000_000 >= progressIntervalMs) {
                        val speed = (done - lastDone) * 1_000_000_000L / (now - lastTick)
                        emit(DownloadEvent.Progress(done, file.sizeBytes, speed))
                        lastTick = now
                        lastDone = done
                    }
                }
            }
            if (done < file.sizeBytes) {
                throw IOException("Соединение оборвано: получено $done из ${file.sizeBytes} байт")
            }
            emit(DownloadEvent.Progress(done, file.sizeBytes, 0))
        }
    }

    private suspend fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
