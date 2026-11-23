package ai.norva.inference

import ai.norva.policy.DefaultCatalog
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HubAndStoreTest {
    private val hash = "AB".repeat(32)

    private class FakeHttp(private val body: String) : HttpFetcher {
        var lastUrl: String? = null
        override fun open(url: String, rangeStart: Long): HttpResult {
            lastUrl = url
            return HttpResult(200, body.length.toLong(), body.byteInputStream())
        }
    }

    // Формат ответа tree API взят по памяти и должен быть сверён с живым ответом huggingface.co.
    private fun tree() = """
        [{"type":"file","oid":"1111","size":120,"path":"README.md"},
         {"type":"file","oid":"2222","size":2740000000,"path":"Qwen3.5-4B-Q4_K_M.gguf",
          "lfs":{"oid":"$hash","size":2740000000,"pointerSize":135}},
         {"type":"file","oid":"3333","size":10,"path":"no-lfs.gguf"}]
    """.trimIndent()

    @Test fun resolvesSizeLowercaseHashAndUrl() {
        val http = FakeHttp(tree())
        val f = HfHub(http).resolve("unsloth/Qwen3.5-4B-GGUF", "Qwen3.5-4B-Q4_K_M.gguf")
        assertEquals(2_740_000_000L, f.sizeBytes)
        assertEquals(hash.lowercase(), f.sha256)
        assertEquals("https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf", f.url)
        assertEquals("https://huggingface.co/api/models/unsloth/Qwen3.5-4B-GGUF/tree/main", http.lastUrl)
    }

    @Test fun missingFileAndMissingHashAreErrors() {
        val hub = HfHub(FakeHttp(tree()))
        assertFailsWith<HubException> { hub.resolve("r/r", "absent.gguf") }
        assertFailsWith<HubException> { hub.resolve("r/r", "no-lfs.gguf") }
    }

    @Test fun storeRequiresChecksumMarker() {
        val root = Files.createTempDirectory("store").toFile()
        try {
            val store = ModelStore(root)
            val spec = DefaultCatalog.models.first()
            assertFalse(store.isInstalled(spec))
            store.dirFor(spec).mkdirs()
            store.fileFor(spec).writeText("x")
            assertFalse(store.isInstalled(spec))
            java.io.File(store.fileFor(spec).path + ".sha256").writeText("abc")
            assertTrue(store.isInstalled(spec))
            assertEquals(setOf(spec.id), store.installedIds(DefaultCatalog.models))
            assertTrue(store.delete(spec))
            assertFalse(store.isInstalled(spec))
        } finally {
            root.deleteRecursively()
        }
    }
}
