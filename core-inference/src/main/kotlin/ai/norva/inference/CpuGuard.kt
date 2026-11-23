package ai.norva.inference

/**
 * Защита от SIGILL: нативная библиотека собрана под набор инструкций ARM (по умолчанию с i8mm и dotprod).
 * Если у процессора их нет, приложение упало бы без сообщения, поэтому сверяем сборку с /proc/cpuinfo.
 * Если данных нет (пустой cpuinfo или незнакомый формат строки system info), ничего не блокируем.
 */
object CpuGuard {
    fun check(systemInfo: String, cpuInfo: String): String? {
        val features = cpuInfo.lineSequence()
            .filter { it.startsWith("Features", ignoreCase = true) }
            .flatMap { it.substringAfter(':', "").trim().split(Regex("\\s+")).asSequence() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (features.isEmpty()) return null

        val missing = ArrayList<String>()
        if (enabled(systemInfo, "MATMUL_INT8") && "i8mm" !in features) missing.add("i8mm")
        if (enabled(systemInfo, "DOTPROD") && "asimddp" !in features) missing.add("dotprod")
        if (missing.isEmpty()) return null
        return "Библиотека движка собрана под инструкции (${missing.joinToString(", ")}), которых нет у этого процессора. " +
            "Пересоберите приложение с -Pnorva.cpuArch=armv8.2-a+dotprod+fp16 (или armv8-a)."
    }

    private fun enabled(info: String, key: String): Boolean =
        Regex("\\b" + key + "\\s*=\\s*1\\b").containsMatchIn(info)
}
