package ai.norva.inference

import kotlin.math.ceil

/** Грубая оценка токенов до токенизации: для русского берём консервативные 2,5 символа на токен. */
object HistoryTrimmer {
    const val CHARS_PER_TOKEN = 2.5

    fun estimateTokens(text: String): Int = ceil(text.length / CHARS_PER_TOKEN).toInt() + 4

    /** Оставляет system и последнюю реплику, выбрасывая самые старые, пока оценка не влезет в бюджет. */
    fun fit(turns: List<ChatTurn>, promptBudgetTokens: Int): List<ChatTurn> {
        val system = turns.filter { it.role == Role.SYSTEM }
        val kept = turns.filter { it.role != Role.SYSTEM }.toMutableList()
        if (kept.isEmpty()) return system
        fun total() = (system + kept).sumOf { estimateTokens(it.content) }
        while (kept.size > 1 && total() > promptBudgetTokens) kept.removeAt(0)
        while (kept.size > 1 && kept.first().role == Role.ASSISTANT) kept.removeAt(0)
        return system + kept
    }
}
