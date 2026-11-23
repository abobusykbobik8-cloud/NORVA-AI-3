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
            license = "Apache-2.0", supportsThinking = false, supportsVision = false,
            experimental = false, verifiedOnHub = true,
        ),
        ModelSpec(
            id = "qwen3.5-4b-q4km", displayName = "Qwen3.5 4B (основная)", tier = ModelTier.BALANCED,
            hubRepo = "unsloth/Qwen3.5-4B-GGUF", fileName = "Qwen3.5-4B-Q4_K_M.gguf",
            fileSizeBytes = 2_740_000_000L, kvBytesPerToken = 147_456L,
            license = "Apache-2.0", supportsThinking = true, supportsVision = true,
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

    fun byId(id: String): ModelSpec? = models.firstOrNull { it.id == id }
}
