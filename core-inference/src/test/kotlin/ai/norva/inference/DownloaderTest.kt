package ai.norva.inference

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DownloaderTest {
    private val payload = ByteArray(1_000_000) { (it * 31 + 7).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val rangeHeaders = mutableListOf<String?>()
    @Volatile private var honorRange = true
    private lateinit var server: HttpServer
    private lateinit var tmp: File

    @BeforeTest fun setUp() {
        tmp = Files.createTempDirectory("norva").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            synchronized(rangeHeaders) { rangeHeaders.add(range) }
            val start = if (honorRange && range != null) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
            val body = payload.copyOfRange(start, payload.size)
            if (start > 0) {
                ex.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                ex.sendResponseHeaders(206, body.size.toLong())
            } else {
                ex.sendResponseHeaders(200, body.size.toLong())
            }
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/redirect") { ex ->
            ex.responseHeaders.add("Location", "/file")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.start()
    }

    @AfterTest fun tearDown() {
        server.stop(0)
        tmp.deleteRecursively()
    }

    private fun remote(path: String = "/file", sha256: String = sha) =
        RemoteFile("t/t", "m.gguf", payload.size.toLong(), sha256, "http://127.0.0.1:${server.address.port}$path")

    @Test fun downloadsThroughRedirectAndVerifies() {
        val dest = File(tmp, "m/m.gguf")
        val events = runBlocking { ModelDownloader().download(remote("/redirect"), dest).toList() }
        assertIs<DownloadEvent.Done>(events.last())
        assertContentEquals(payload, dest.readBytes())
        assertFalse(File(dest.path + ".part").exists())
        assertEquals(sha, File(dest.path + ".sha256").readText())
    }

    @Test fun resumesPartialFileWithRangeRequest() {
        val dest = File(tmp, "m/m.gguf")
        dest.parentFile.mkdirs()
        File(dest.path + ".part").writeBytes(payload.copyOfRange(0, 400_000))
        val events = runBlocking { ModelDownloader().download(remote(), dest).toList() }
        assertIs<DownloadEvent.Done>(events.last())
        assertContentEquals(payload, dest.readBytes())
        assertEquals("bytes=400000-", rangeHeaders.first())
    }

    @Test fun restartsWhenServerIgnoresRange() {
        honorRange = false
        val dest = File(tmp, "m/m.gguf")
        dest.parentFile.mkdirs()
        File(dest.path + ".part").writeBytes(payload.copyOfRange(0, 400_000))
        val events = runBlocking { ModelDownloader().download(remote(), dest).toList() }
        assertIs<DownloadEvent.Done>(events.last())
        assertContentEquals(payload, dest.readBytes())
    }

    @Test fun wrongChecksumDeletesEverything() {
        val dest = File(tmp, "m/m.gguf")
        val events = runBlocking { ModelDownloader().download(remote(sha256 = "0".repeat(64)), dest).toList() }
        val failed = events.last()
        assertIs<DownloadEvent.Failed>(failed)
        assertEquals(DownloadError.CHECKSUM, failed.error)
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun notEnoughSpaceFailsBeforeDownloading() {
        val dest = File(tmp, "m/m.gguf")
        val huge = remote().copy(sizeBytes = Long.MAX_VALUE / 4)
        val events = runBlocking { ModelDownloader().download(huge, dest).toList() }
        val failed = events.last()
        assertIs<DownloadEvent.Failed>(failed)
        assertEquals(DownloadError.NO_SPACE, failed.error)
        assertTrue(rangeHeaders.isEmpty())
    }
}
