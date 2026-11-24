package ai.norva.inference

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

/** A strong ETag or If-None-Match protects the shared file against concurrent writers. */
class WebDavVault(address: String, private val user: String, private val secret: String) {
    private val url = URI(address).also {
        require(it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null && it.rawQuery == null) {
            "Введите HTTPS-адрес файла без логина и параметров в URL"
        }
    }.toURL()
    data class Snapshot(val bytes: ByteArray, val etag: String)

    private fun connect(method: String) = (url.openConnection() as HttpsURLConnection).apply {
        requestMethod = method
        instanceFollowRedirects = false
        connectTimeout = 12000
        readTimeout = 25000
        useCaches = false
        if (user.isNotBlank()) setRequestProperty("Authorization","Basic " + Base64.getEncoder().encodeToString("$user:$secret".toByteArray()))
    }
    fun read(): Snapshot? {
        val c = connect("GET")
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) throw IOException("WebDAV GET: HTTP ${c.responseCode}")
            val etag = c.getHeaderField("ETag").orEmpty()
            require(etag.matches(Regex("\"[^\"\\r\\n]+\""))) { "Сервер должен поддерживать строгие ETag" }
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    require(out.size()+n <= SyncCipher.LIMIT+52) { "Архив слишком велик" }
                    out.write(buf,0,n)
                }
            }
            return Snapshot(out.toByteArray(),etag)
        } finally { c.disconnect() }
    }
    fun write(bytes: ByteArray, prior: Snapshot?) {
        require(bytes.size <= SyncCipher.LIMIT+52)
        val c = connect("PUT")
        try {
            if (prior==null) c.setRequestProperty("If-None-Match","*") else c.setRequestProperty("If-Match",prior.etag)
            c.doOutput = true
            c.setRequestProperty("Content-Type","application/octet-stream")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            if (c.responseCode == 412) throw IOException("Архив изменился на другом устройстве. Повторите синхронизацию.")
            if (c.responseCode !in 200..299) throw IOException("WebDAV PUT: HTTP ${c.responseCode}")
        } finally { c.disconnect() }
    }
}
