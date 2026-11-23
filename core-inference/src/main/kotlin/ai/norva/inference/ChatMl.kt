package ai.norva.inference

enum class Role { SYSTEM, USER, ASSISTANT }

data class ChatTurn(val role: Role, val content: String)

/**
 * Формат ChatML семейства Qwen. Без рассуждений в промпт добавляется пустой блок think, с рассуждениями промпт
 * заканчивается открытым `<think>\n` (модель продолжает мысль, поток разбирается ThinkingFilter(startInThinking = true)).
 * Qwen3.5 по умолчанию рассуждает; точный шаблон сверить с tokenizer.chat_template в GGUF (см. docs/ENGINE.md).
 */
object ChatMl {
    private const val START = "<|im_start|>"
    private const val END = "<|im_end|>"
    private val thinkBlock = Regex("(?s)<think>.*?</think>\\s*")

    fun stripThinking(text: String): String = text.replace(thinkBlock, "").trim()

    fun build(turns: List<ChatTurn>, thinking: Boolean): String = buildString {
        for (t in turns) {
            val name = when (t.role) {
                Role.SYSTEM -> "system"
                Role.USER -> "user"
                Role.ASSISTANT -> "assistant"
            }
            val content = if (t.role == Role.ASSISTANT) stripThinking(t.content) else t.content.trim()
            append(START).append(name).append('\n').append(content).append(END).append('\n')
        }
        append(START).append("assistant\n")
        append(if (thinking) "<think>\n" else "<think>\n\n</think>\n\n")
    }
}
