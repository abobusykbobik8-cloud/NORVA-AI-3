package ai.norva.inference

/**
 * Потоковый разбор <think>...</think>. Теги могут прийти разорванными по чанкам,
 * поэтому возможный префикс тега удерживается до следующего чанка.
 */
class ThinkingFilter(startInThinking: Boolean = false) {
    data class Out(val thinking: String, val answer: String)

    private var inThinking = startInThinking

    /** true, пока поток находится внутри блока рассуждения. */
    val isThinking: Boolean get() = inThinking
    private val buf = StringBuilder()
    private var skipLeadingWhitespace = false

    fun feed(chunk: String): Out {
        buf.append(chunk)
        return drain(final = false)
    }

    fun finish(): Out = drain(final = true)

    private fun drain(final: Boolean): Out {
        val think = StringBuilder()
        val answer = StringBuilder()
        while (true) {
            val tag = if (inThinking) CLOSE else OPEN
            val s = buf.toString()
            val idx = s.indexOf(tag)
            if (idx >= 0) {
                put(s.substring(0, idx), think, answer)
                buf.setLength(0)
                buf.append(s, idx + tag.length, s.length)
                if (inThinking) skipLeadingWhitespace = true
                inThinking = !inThinking
                continue
            }
            val hold = if (final) 0 else partialSuffix(s, tag)
            put(s.substring(0, s.length - hold), think, answer)
            buf.setLength(0)
            buf.append(s, s.length - hold, s.length)
            break
        }
        return Out(think.toString(), answer.toString())
    }

    private fun put(text: String, think: StringBuilder, answer: StringBuilder) {
        if (text.isEmpty()) return
        if (inThinking) {
            think.append(text)
            return
        }
        var t = text
        if (skipLeadingWhitespace) {
            t = t.trimStart()
            if (t.isEmpty()) return
            skipLeadingWhitespace = false
        }
        answer.append(t)
    }

    private fun partialSuffix(s: String, tag: String): Int {
        val max = minOf(s.length, tag.length - 1)
        for (k in max downTo 1) {
            if (s.endsWith(tag.substring(0, k))) return k
        }
        return 0
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}
