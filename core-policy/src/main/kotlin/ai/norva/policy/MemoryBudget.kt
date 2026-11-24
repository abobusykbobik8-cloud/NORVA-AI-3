package ai.norva.policy

data class DeviceMemory(val totalBytes: Long, val availBytes: Long, val lowThreshold: Long = 0, val lowMemory: Boolean = false)

/**
 * Бюджет памяти для нативного движка. Коэффициенты - стартовые значения для 12 ГБ,
 * их нужно откалибровать на устройстве (см. docs/DEVICE_GT8PRO.md).
 */
object MemoryBudget {
    const val GIB: Long = 1024L * 1024L * 1024L
    const val HARD_CAP_FRACTION = 0.40
    const val DYNAMIC_FRACTION = 0.75
    const val RUNTIME_OVERHEAD_BYTES: Long = 400L * 1024L * 1024L
    const val KV_Q8_FACTOR = 0.532
    const val CONTEXT_STEP = 256
    const val MIN_CONTEXT = 1024

    fun budgetBytes(mem: DeviceMemory): Long {
        if (mem.lowMemory || mem.totalBytes <= 0) return 0
        val available = mem.availBytes.coerceIn(0, mem.totalBytes)
        val hard = (mem.totalBytes * HARD_CAP_FRACTION).toLong()
        val dynamic = (available * DYNAMIC_FRACTION).toLong()
        return minOf(hard, dynamic, (available - mem.lowThreshold.coerceAtLeast(0)).coerceAtLeast(0))
    }

    fun kvBytesPerToken(spec: ModelSpec, kvQ8: Boolean = true): Long =
        if (kvQ8) (spec.kvBytesPerToken * KV_Q8_FACTOR).toLong() else spec.kvBytesPerToken

    fun estimateBytes(spec: ModelSpec, contextTokens: Int, kvQ8: Boolean = true): Long =
        spec.fileSizeBytes + RUNTIME_OVERHEAD_BYTES + kvBytesPerToken(spec, kvQ8) * contextTokens

    /** Наибольший контекст (кратный 256) не выше wantedTokens, который помещается в бюджет; 0 - не помещается. */
    fun maxContext(spec: ModelSpec, budgetBytes: Long, wantedTokens: Int, kvQ8: Boolean = true): Int {
        val room = budgetBytes - spec.fileSizeBytes - RUNTIME_OVERHEAD_BYTES
        if (room <= 0L) return 0
        val perToken = kvBytesPerToken(spec, kvQ8)
        if (perToken <= 0L) return 0
        val fitting = (room / perToken).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val ctx = minOf(wantedTokens, fitting) / CONTEXT_STEP * CONTEXT_STEP
        return if (ctx >= MIN_CONTEXT) ctx else 0
    }
}
