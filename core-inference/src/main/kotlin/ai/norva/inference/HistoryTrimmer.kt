package ai.norva.inference

import kotlin.math.ceil

object HistoryTrimmer {
    const val CHARS_PER_TOKEN = 2.5

    fun estimateTokens(text: String): Int = ceil(text.length / CHARS_PER_TOKEN).toInt() + 4

    /** Keep the system instruction and newest turn, then take the suffix that fits the estimated budget. */
    fun fit(turns: List<ChatTurn>, promptBudgetTokens: Int): List<ChatTurn> {
        val system = turns.filter { it.role == Role.SYSTEM }
        val history = turns.filter { it.role != Role.SYSTEM }
        if (history.isEmpty()) return system
        var first = history.lastIndex
        var remaining = promptBudgetTokens.toLong() - system.sumOf { estimateTokens(it.content).toLong() } -
            estimateTokens(history[first].content)
        while (first > 0) {
            val cost = estimateTokens(history[first - 1].content)
            if (cost > remaining) break
            remaining -= cost
            first--
        }
        while (first < history.lastIndex && history[first].role == Role.ASSISTANT) first++
        val kept = history.subList(first, history.size).toMutableList()
        val latestPhoto = history.lastOrNull { it.role == Role.USER && it.photoPath != null }?.photoPath
        if (latestPhoto != null && kept.none { it.photoPath != null } && kept.last().role == Role.USER) {
            kept[kept.lastIndex] = kept.last().copy(photoPath = latestPhoto)
        }
        return system + kept
    }
}
