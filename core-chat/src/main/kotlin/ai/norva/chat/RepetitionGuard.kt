package ai.norva.chat

/** Detect long verbatim loops without suppressing short refrains, indentation or punctuation. */
internal object RepetitionGuard {
    fun isLoop(text: CharSequence): Boolean {
        // A partial final phrase is fine: compare a suffix with the same suffix shifted by one period.
        for (period in 3..minOf(128, text.length / 6)) {
            val length = maxOf(160, 6 * period)
            if (length > text.length) continue
            val from = text.length - length
            if ((from + period until text.length).all { text[it] == text[it - period] } &&
                (from until from + period).count { text[it].isLetter() } >= 3) return true
        }
        return false
    }
}
