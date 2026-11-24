package ai.norva.chat

import ai.norva.inference.GenerationEvent
import ai.norva.inference.EngineError
import ai.norva.inference.ModelStore
import ai.norva.inference.Role
import ai.norva.inference.ThinkingFilter
import ai.norva.policy.DefaultCatalog
import ai.norva.policy.DeviceMemory
import ai.norva.policy.MemoryBudget.GIB
import ai.norva.policy.PowerState
import ai.norva.policy.ThinkingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatOrchestratorTest {
    private val roots = mutableListOf<File>()

    @AfterTest fun cleanup() {
        roots.forEach { it.deleteRecursively() }
    }

    private inner class Env(
        scope: CoroutineScope,
        availGib: Double = 5.0,
        var thermal: Int = 0,
        installed: List<String> = listOf("qwen3.5-4b-q4km"),
    ) {
        val root: File = Files.createTempDirectory("norva-chat").toFile().also { roots.add(it) }
        val store = ModelStore(root)
        val repo = FakeRepo()
        val engine = FakeEngine()
        var avail: Long = (availGib * GIB).toLong()
        val orchestrator: ChatOrchestrator

        init {
            for (id in installed) {
                val spec = DefaultCatalog.byId(id)!!
                store.dirFor(spec).mkdirs()
                store.fileFor(spec).writeText("x")
                File(store.fileFor(spec).path + ".sha256").writeText("h")
            }
            orchestrator = ChatOrchestrator(
                repo = repo,
                engine = engine,
                store = store,
                power = object : PowerStateProvider {
                    override fun current() = PowerState(thermal, 80, false, false)
                },
                memory = object : MemoryInfoProvider {
                    override fun current() = DeviceMemory((11.2 * GIB).toLong(), avail)
                },
                scope = scope,
                systemPrompt = { "SYS" },
                clock = { 0L },
            )
        }

        suspend fun newChat(): Long = repo.createConversation(ChatOrchestrator.DEFAULT_TITLE)
    }

    @Test fun streamsAnswerPersistsItAndTitlesTheChat() = runTest {
        val env = Env(this)
        val c = env.newChat()
        env.engine.script(text("Привет"), text(", мир"), finished())
        env.orchestrator.send(c, "Здравствуй", ThinkingMode.NORMAL)

        val msgs = env.repo.getMessages(c)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), msgs.map { it.role })
        val a = msgs.last()
        assertEquals("Привет, мир", a.content)
        assertEquals(MessageStatus.COMPLETE, a.status)
        assertEquals("qwen3.5-4b-q4km", a.modelId)
        assertEquals("Здравствуй", env.repo.getConversation(c)!!.title)

        val prompt = env.engine.requests.single().prompt
        assertTrue(prompt.contains("<|im_start|>system\nSYS<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        assertEquals(ChatStatus.Idle, env.orchestrator.status.value)
    }

    @Test fun deepModeSeparatesThinkingFromAnswer() = runTest {
        val env = Env(this)
        val c = env.newChat()
        env.engine.script(text("reason"), text("</think>"), text("\n\nAnswer"), finished())
        env.orchestrator.send(c, "q", ThinkingMode.DEEP)

        val a = env.repo.getMessages(c).last()
        assertEquals("Answer", a.content)
        assertEquals("reason", a.thinking)
        assertTrue(env.engine.requests.single().prompt.endsWith("<|im_start|>assistant\n<think>\n"))
    }

    @Test fun thinkingBudgetCutsReasoningAndContinuesWithAnswer() = runTest {
        val env = Env(this)
        val c = env.newChat()
        val budget = ai.norva.policy.ModeProfiles.of(ThinkingMode.DEEP).thinkingBudgetTokens
        val endless = List(budget + 100) { text("t") }
        env.engine.script(*endless.toTypedArray())
        env.engine.script(text("Final"), finished())
        env.orchestrator.send(c, "q", ThinkingMode.DEEP)

        assertEquals(2, env.engine.requests.size)
        val second = env.engine.requests[1]
        assertTrue(second.prompt.endsWith("</think>\n\n"))
        assertTrue(second.prompt.contains("<think>\n" + "t".repeat(budget)))
        val a = env.repo.getMessages(c).last()
        assertEquals("Final", a.content)
        assertEquals(budget, a.thinking.length)
        assertEquals(MessageStatus.COMPLETE, a.status)
    }

    @Test fun cancellationKeepsPartialAnswer() = runTest {
        val env = Env(this)
        val c = env.newChat()
        env.engine.scriptFlow { flow { emit(text("part")); awaitCancellation() } }
        val job = launch { env.orchestrator.send(c, "q", ThinkingMode.NORMAL) }
        advanceUntilIdle()
        job.cancelAndJoin()

        val a = env.repo.getMessages(c).last()
        assertEquals(MessageStatus.CANCELLED, a.status)
        assertEquals("part", a.content)
        assertEquals(ChatStatus.Idle, env.orchestrator.status.value)
    }

    @Test fun noInstalledModelFailsWithoutTouchingEngine() = runTest {
        val env = Env(this, installed = emptyList())
        val c = env.newChat()
        env.orchestrator.send(c, "q", ThinkingMode.NORMAL)

        val a = env.repo.getMessages(c).last()
        assertEquals(MessageStatus.FAILED, a.status)
        assertTrue(a.errorMessage!!.contains("Нет установленных моделей"))
        assertTrue(env.engine.requests.isEmpty() && env.engine.loadCalls.isEmpty())
    }

    @Test fun overheatedPhoneBlocksLocalGeneration() = runTest {
        val env = Env(this, thermal = 3)
        val c = env.newChat()
        env.orchestrator.send(c, "q", ThinkingMode.NORMAL)

        val a = env.repo.getMessages(c).last()
        assertEquals(MessageStatus.FAILED, a.status)
        assertTrue(a.errorMessage!!.contains("перегрет"))
        assertTrue(env.engine.loadCalls.isEmpty())
    }

    @Test fun tightMemoryFallsBackToSmallModel() = runTest {
        val env = Env(this, availGib = 3.0, installed = listOf("qwen3.5-2b-q4km", "qwen3.5-4b-q4km"))
        val c = env.newChat()
        env.engine.script(text("ok"), finished())
        env.orchestrator.send(c, "q", ThinkingMode.NORMAL)

        assertTrue(env.engine.loadCalls.single().modelPath.endsWith("Qwen3.5-2B-Q4_K_M.gguf"))
        assertEquals(MessageStatus.COMPLETE, env.repo.getMessages(c).last().status)
    }

    @Test fun residentModelDoesNotCountAgainstTheBudget() = runTest {
        val env = Env(this)
        val c = env.newChat()
        env.engine.script(text("one"), finished())
        env.orchestrator.send(c, "q1", ThinkingMode.NORMAL)
        env.avail = 2 * GIB  // после загрузки модели системе свободно заметно меньше памяти
        env.engine.script(text("two"), finished())
        env.orchestrator.send(c, "q2", ThinkingMode.NORMAL)

        assertEquals(1, env.engine.loadCalls.size)
        assertEquals("two", env.repo.getMessages(c).last().content)
    }

    @Test fun regenerateReplacesLastAnswer() = runTest {
        val env = Env(this)
        val c = env.newChat()
        env.engine.script(text("Первый"), finished())
        env.orchestrator.send(c, "q", ThinkingMode.NORMAL)
        env.engine.script(text("Второй"), finished())
        env.orchestrator.regenerate(c, ThinkingMode.NORMAL)

        val msgs = env.repo.getMessages(c)
        assertEquals(2, msgs.size)
        assertEquals("Второй", msgs.last().content)
        assertFalse(env.engine.requests[1].prompt.contains("Первый"))
    }

    @Test fun promptTooLongRetriesWithShorterHistory() = runTest {
        val env = Env(this)
        val c = env.newChat()
        repeat(30) { i ->
            env.repo.addMessage(
                StoredMessage(
                    conversationId = c, role = if (i % 2 == 0) Role.USER else Role.ASSISTANT,
                    content = "x".repeat(500), createdAt = 0,
                ),
            )
        }
        env.engine.script(GenerationEvent.Failed(EngineError.PROMPT_TOO_LONG, "long"))
        env.engine.script(text("ok"), finished())
        env.orchestrator.send(c, "новый вопрос", ThinkingMode.NORMAL)

        assertEquals(2, env.engine.requests.size)
        assertTrue(env.engine.requests[1].prompt.length < env.engine.requests[0].prompt.length)
        assertEquals(MessageStatus.COMPLETE, env.repo.getMessages(c).last().status)
    }

    @Test fun failedAndEmptyAnswersAreNotReplayedAsHistory() = runTest {
        val env = Env(this)
        val c = env.newChat()
        env.repo.addMessage(StoredMessage(conversationId = c, role = Role.USER, content = "старый вопрос", createdAt = 0))
        env.repo.addMessage(
            StoredMessage(conversationId = c, role = Role.ASSISTANT, content = "битый ответ", status = MessageStatus.FAILED, createdAt = 0),
        )
        env.engine.script(text("ok"), finished())
        env.orchestrator.send(c, "новый", ThinkingMode.NORMAL)

        assertFalse(env.engine.requests.single().prompt.contains("битый ответ"))
    }

    @Test fun photoPixelsAndMatchingProjectorArePassedToEngine() = runTest {
        val env = Env(this, availGib = 7.0)
        val projector = DefaultCatalog.projectors.getValue("qwen3.5-4b-q4km")
        val file = env.store.fileFor(projector)
        file.parentFile.mkdirs()
        file.writeText("projector")
        File(file.path + ".sha256").writeText("hash")
        val c = env.newChat()
        env.engine.script(text("ok"), finished())
        env.orchestrator.send(c, "", ThinkingMode.NORMAL, Attachment("PHOTO", "/x.jpg", "Кадр 64×64, красный"))

        val user = env.repo.getMessages(c).first()
        assertEquals("PHOTO", user.attachmentKind)
        assertEquals(ChatOrchestrator.DEFAULT_PHOTO_QUESTION, user.content)
        assertFalse(user.content.contains("Кадр"))
        val prompt = env.engine.requests.single().prompt
        assertEquals("/x.jpg", env.engine.requests.single().photoPath)
        assertEquals(file.absolutePath, env.engine.requests.single().projectorPath)
        assertTrue(prompt.contains(ai.norva.inference.ChatMl.MEDIA_MARKER))
    }

    @Test fun repeatedAnswersStopAndAreExcludedFromFuturePrompts() = runTest {
        val env = Env(this)
        val c = env.newChat()
        env.engine.script(*List(128) { text("очередной повтор. ") }.toTypedArray())
        env.orchestrator.send(c, "первый", ThinkingMode.FAST)
        assertEquals(MessageStatus.CANCELLED, env.repo.getMessages(c).last().status)
        env.engine.script(text("другой"), finished())
        env.orchestrator.send(c, "второй", ThinkingMode.NORMAL)
        assertFalse("очередной повтор" in env.engine.requests.last().prompt)
        assertFalse(RepetitionGuard.isLoop("1, 2, 3, " + "—".repeat(200)))
    }

    @Test fun filterExposesThinkingState() {
        val f = ThinkingFilter(false)
        assertFalse(f.isThinking)
        f.feed("<think>abc")
        assertTrue(f.isThinking)
        f.feed("</think>x")
        assertFalse(f.isThinking)
        assertNotNull(f)
    }
}
