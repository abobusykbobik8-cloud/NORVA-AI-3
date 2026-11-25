package ai.norva.policy

/**
 * Запись каталога. Реальные размер и SHA-256 при скачивании берутся из Hugging Face API
 * (поле lfs.oid), поэтому значения здесь используются только для предварительной оценки памяти.
 * verifiedOnHub = false: репозиторий и имя файла выведены по шаблону и должны быть проверены.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val tier: ModelTier,
    val hubRepo: String,
    val fileName: String,
    val fileSizeBytes: Long,
    /**
     * Консервативная оценка KV-кэша в f16, байт на токен контекста. Qwen3.5 гибридная (Gated DeltaNet + полное внимание
     * в каждом 4-м слое), реальный KV заметно меньше этой оценки; она оставлена с запасом до замеров на устройстве.
     */
    val kvBytesPerToken: Long,
    val license: String,
    val supportsThinking: Boolean,
    val supportsVision: Boolean,
    val experimental: Boolean,
    val verifiedOnHub: Boolean,
)

object DefaultCatalog {
    val models: List<ModelSpec> = listOf(
        ModelSpec(
            id = "qwen3.5-2b-q4km", displayName = "Qwen3.5 2B (быстрая)", tier = ModelTier.SMALL,
            hubRepo = "unsloth/Qwen3.5-2B-GGUF", fileName = "Qwen3.5-2B-Q4_K_M.gguf",
            fileSizeBytes = 1_280_835_840L, kvBytesPerToken = 98_304L,
            license = "Apache-2.0", supportsThinking = true, supportsVision = true,
            experimental = false, verifiedOnHub = true,
        ),
        ModelSpec(
            id = "qwen3.5-4b-q4km", displayName = "Qwen3.5 4B (основная)", tier = ModelTier.BALANCED,
            hubRepo = "unsloth/Qwen3.5-4B-GGUF", fileName = "Qwen3.5-4B-Q4_K_M.gguf",
            fileSizeBytes = 2_740_937_888L, kvBytesPerToken = 147_456L,
            license = "Apache-2.0", supportsThinking = true, supportsVision = true,
            experimental = false, verifiedOnHub = true,
        ),
        ModelSpec(
            id = "qwen3.5-0.8b-q4km", displayName = "Qwen3.5 0.8B · для 3–4 ГБ", tier = ModelTier.LITE,
            hubRepo = "unsloth/Qwen3.5-0.8B-GGUF", fileName = "Qwen3.5-0.8B-Q4_K_M.gguf",
            fileSizeBytes = 532_517_120L, kvBytesPerToken = 49_152L,
            license = "Apache-2.0", supportsThinking = false, supportsVision = true,
            experimental = false, verifiedOnHub = true,
        ),
        ModelSpec(
            id = "qwen3.5-9b-q4km", displayName = "Qwen3.5 9B (экспериментально)", tier = ModelTier.STRONG,
            hubRepo = "unsloth/Qwen3.5-9B-GGUF", fileName = "Qwen3.5-9B-Q4_K_M.gguf",
            fileSizeBytes = 5_600_000_000L, kvBytesPerToken = 196_608L,
            license = "Apache-2.0", supportsThinking = true, supportsVision = true,
            experimental = true, verifiedOnHub = false,
        ),
    )

    val projectors = models.filter { !it.experimental }.associate { model ->
        model.id to model.copy(id = model.id + "-vision", displayName = "Проектор фото · " + model.displayName,
            fileName = "mmproj-F16.gguf", fileSizeBytes = when (model.tier) {
                ModelTier.LITE -> 204_987_232L
                ModelTier.SMALL -> 668_227_264L
                else -> 672_423_616L
            },
            supportsThinking = false)
    }
    val downloads = models.filter { !it.experimental } + projectors.values + MediaCatalog.all

    fun byId(id: String): ModelSpec? = models.firstOrNull { it.id == id }
}

/** Media assets share the verified downloader but are never offered to llama.cpp. */
object MediaCatalog {
    private fun asset(id: String, title: String, repo: String, file: String, bytes: Long, license: String = "Apache-2.0") =
        ModelSpec(id, title, ModelTier.SMALL, repo, file, bytes, 0L, license, false, false, false, true)

    val picture = asset("sd15-q4", "Картинки · Stable Diffusion 1.5", "second-state/stable-diffusion-v1-5-GGUF",
        "stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf", 1_566_768_416L, "CreativeML Open RAIL-M")
    val video = listOf(
        asset("wan21-vace-q4", "Видео · Wan 2.1 VACE 1.3B", "calcuis/wan-1.3b-gguf",
            "wan2.1-vace-1.3b-q4_0.gguf", 1_345_134_272L),
        asset("umt5-q3", "Видео · текстовый энкодер UMT5", "city96/umt5-xxl-encoder-gguf",
            "umt5-xxl-encoder-Q3_K_S.gguf", 2_858_489_696L),
        asset("wan21-vae", "Видео · декодер Wan VAE", "Comfy-Org/Wan_2.1_ComfyUI_repackaged",
            "split_files/vae/wan_2.1_vae.safetensors", 253_815_318L),
    )
    val all = listOf(picture) + video
    fun required(isVideo: Boolean) = if (isVideo) video else listOf(picture)
}
