package ai.norva.inference

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class HttpStatusException(val code: Int) : IOException("HTTP $code")

class HttpResult(
    val code: Int,
    val contentLength: Long,
    val body: InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        try { body.close() } finally { onClose() }
    }
}

interface HttpFetcher {
    fun open(url: String, rangeStart: Long = 0): HttpResult

    fun getText(url: String): String = open(url).use {
        if (it.code !in 200..299) throw HttpStatusException(it.code)
        it.body.readBytes().toString(Charsets.UTF_8)
    }
}

/** Редиректы идут вручную, чтобы заголовок Range сохранялся (Hugging Face отдаёт файлы через CDN). */
class JdkHttpFetcher(
    private val userAgent: String = "NorvaAI/0.1",
    private val maxRedirects: Int = 5,
) : HttpFetcher {
    override fun open(url: String, rangeStart: Long): HttpResult {
        var current = url
        repeat(maxRedirects + 1) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", userAgent)
            if (rangeStart > 0) c.setRequestProperty("Range", "bytes=$rangeStart-")
            val code = c.responseCode
            if (code in 301..303 || code == 307 || code == 308) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Редирект без Location")
                c.disconnect()
                current = URL(URL(current), loc).toString()
                return@repeat
            }
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: InputStream.nullInputStream())
            return HttpResult(code, c.contentLengthLong, stream) { c.disconnect() }
        }
        throw IOException("Слишком много перенаправлений")
    }
}
