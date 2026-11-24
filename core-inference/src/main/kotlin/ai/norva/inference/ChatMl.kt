package ai.norva.inference

enum class Role { SYSTEM, USER, ASSISTANT }

data class ChatTurn(val role: Role, val content: String, val photoPath: String? = null)

/**
 * Формат ChatML семейства Qwen. Без рассуждений в промпт добавляется пустой блок think, с рассуждениями промпт
 * заканчивается открытым `<think>\n` (модель продолжает мысль, поток разбирается ThinkingFilter(startInThinking = true)).
 * Qwen3.5 по умолчанию рассуждает; формат обычного диалога проверен по tokenizer.chat_template (см. docs/ENGINE.md).
 */
object ChatMl {
    const val MEDIA_MARKER = "<__norva_photo__>"
    private const val START = "<|im_start|>"
    private const val END = "<|im_end|>"
    private val thinkBlock = Regex("(?s)<think>.*?</think>\\s*")

    // Reserved strings in the Qwen tokenizer must remain ordinary text inside a message.
    private val contentDelimiter = Regex("<(?=\\||__norva_photo__>|/?(?:think|tool_call|tool_response)>|tts_(?:pad|text_bos|text_eod|text_bos_single)>)")

    fun literal(text: String): String = contentDelimiter.replace(text, "<\u200B")

    fun stripThinking(text: String): String = text.replace(thinkBlock, "").trim()

    fun build(turns: List<ChatTurn>, thinking: Boolean): String = buildString {
        for (t in turns) {
            val name = when (t.role) {
                Role.SYSTEM -> "system"
                Role.USER -> "user"
                Role.ASSISTANT -> "assistant"
            }
            val content = if (t.role == Role.ASSISTANT) stripThinking(t.content) else t.content.trim()
            append(START).append(name).append('\n')
            if (t.photoPath != null) append(MEDIA_MARKER).append('\n')
            append(literal(content)).append(END).append('\n')
        }
        append(START).append("assistant\n")
        append(if (thinking) "<think>\n" else "<think>\n\n</think>\n\n")
    }
}
