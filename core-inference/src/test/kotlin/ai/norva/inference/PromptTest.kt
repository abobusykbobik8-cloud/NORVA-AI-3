package ai.norva.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptTest {
    @Test fun chatMlWithoutThinkingAddsEmptyThinkBlock() {
        val s = ChatMl.build(listOf(ChatTurn(Role.SYSTEM, "S"), ChatTurn(Role.USER, "Привет")), thinking = false)
        assertEquals(
            "<|im_start|>system\nS<|im_end|>\n<|im_start|>user\nПривет<|im_end|>\n" +
                "<|im_start|>assistant\n<think>\n\n</think>\n\n",
            s,
        )
    }

    @Test fun chatMlWithThinkingLeavesAssistantOpen() {
        val s = ChatMl.build(listOf(ChatTurn(Role.USER, "Q")), thinking = true)
        assertTrue(s.endsWith("<|im_start|>assistant\n<think>\n"))
    }

    @Test fun reasoningIsStrippedFromAssistantHistory() {
        val s = ChatMl.build(
            listOf(
                ChatTurn(Role.USER, "Q1"),
                ChatTurn(Role.ASSISTANT, "<think>секретные рассуждения</think>\n\nОтвет"),
                ChatTurn(Role.USER, "Q2"),
            ),
            thinking = false,
        )
        assertTrue("секретные" !in s)
        assertTrue("<|im_start|>assistant\nОтвет<|im_end|>" in s)
    }

    private val sys = ChatTurn(Role.SYSTEM, "s")
    private fun turn(role: Role, c: Char) = ChatTurn(role, c.toString().repeat(100))

    @Test fun trimmerKeepsSystemAndLastUserAndDropsOldest() {
        val last = turn(Role.USER, 'c')
        val turns = listOf(sys, turn(Role.USER, 'a'), turn(Role.ASSISTANT, 'b'), last)
        assertEquals(listOf(sys, last), HistoryTrimmer.fit(turns, promptBudgetTokens = 100))
    }

    @Test fun trimmerLeavesShortHistoryUntouched() {
        val turns = listOf(sys, turn(Role.USER, 'a'), turn(Role.ASSISTANT, 'b'), turn(Role.USER, 'c'))
        assertEquals(turns, HistoryTrimmer.fit(turns, promptBudgetTokens = 10_000))
    }

    @Test fun trimmerNeverDropsOversizedLastMessage() {
        val big = ChatTurn(Role.USER, "x".repeat(10_000))
        assertEquals(listOf(sys, big), HistoryTrimmer.fit(listOf(sys, big), promptBudgetTokens = 50))
    }

    private fun run(chunks: List<String>, startInThinking: Boolean = false): Pair<String, String> {
        val f = ThinkingFilter(startInThinking)
        val th = StringBuilder()
        val an = StringBuilder()
        for (c in chunks) {
            val o = f.feed(c)
            th.append(o.thinking)
            an.append(o.answer)
        }
        val o = f.finish()
        th.append(o.thinking)
        an.append(o.answer)
        return th.toString() to an.toString()
    }

    @Test fun filterSplitsThinkingAndAnswer() {
        val (t, a) = run(listOf("<think>\nreason\n</think>\n\nAnswer"))
        assertEquals("reason", t.trim())
        assertEquals("Answer", a)
    }

    @Test fun filterWorksWhenTagsAreSplitCharByChar() {
        val text = "<think>reason</think>\n\nAnswer"
        val (t, a) = run(text.map { it.toString() })
        assertEquals("reason", t)
        assertEquals("Answer", a)
    }

    @Test fun filterPassesPlainTextThrough() {
        assertEquals("" to "Hello world", run(listOf("Hello ", "world")))
    }

    @Test fun filterSupportsStartInThinking() {
        assertEquals("abc" to "done", run(listOf("abc</think>done"), startInThinking = true))
    }

    @Test fun filterFlushesUnfinishedTagOnFinish() {
        assertEquals("" to "a <thi", run(listOf("a <thi")))
    }
}
