package ai.norva.inference

import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URLEncoder
import java.net.URI

data class Evidence(val title: String, val url: String, val text: String)

/** Search provider APIs only; external links in their responses are displayed, never executed. */
class ResearchIndex(private val http: HttpFetcher = JdkHttpFetcher("NORVA-Research/1.4 (github.com/abobusykbobik8-cloud/NORVA-AI-3)")) {
    private fun json(url: String) = http.open(url).use { res ->
        if (res.code !in 200..299) throw IOException("HTTP ${res.code}")
        val reader = res.body.bufferedReader()
        val chars = CharArray(8192)
        val data = StringBuilder()
        while (true) {
            val n = reader.read(chars)
            if (n < 0) break
            require(data.length + n <= 2_000_000) { "Слишком большой ответ поиска" }
            data.append(chars, 0, n)
        }
        Json.parseToJsonElement(data.toString()).jsonObject
    }

    fun search(query: String): List<Evidence> {
        val q = URLEncoder.encode(query.trim().take(300), "UTF-8")
        val language = if (query.any { it in 'А'..'я' }) "ru" else "en"
        val wiki = runCatching {
            val data = json("https://$language.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&generator=search&gsrlimit=4&gsrsearch=$q&prop=extracts&exintro=1&explaintext=1&exchars=1400")
            data["query"]?.jsonObject?.get("pages")?.jsonArray.orEmpty().map {
                val page = it.jsonObject
                Evidence(page.getValue("title").jsonPrimitive.content,
                    "https://$language.wikipedia.org/?curid=" + page.getValue("pageid").jsonPrimitive.long,
                    page["extract"]?.jsonPrimitive?.content.orEmpty())
            }
        }.getOrDefault(emptyList())
        val papers = runCatching {
            val data = json("https://api.crossref.org/works?query.bibliographic=$q&filter=has-abstract:true&rows=5&select=title,DOI,abstract")
            data.getValue("message").jsonObject.getValue("items").jsonArray.mapNotNull {
                val doc = it.jsonObject
                val doi = doc["DOI"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val url = URI("https", "doi.org", "/$doi", null).toASCIIString()
                Evidence(doc["title"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content ?: return@mapNotNull null,
                    url, doc["abstract"]?.jsonPrimitive?.content.orEmpty().replace(Regex("<[^>]+>"), " "))
            }
        }.getOrDefault(emptyList())
        return (papers.take(2) + wiki + papers.drop(2)).filter { it.text.length >= 80 }
            .distinctBy { it.url }.take(4).also { require(it.size >= 2) { "Для отчёта нужны хотя бы два доступных источника. Уточните запрос или проверьте сеть." } }
    }
}
