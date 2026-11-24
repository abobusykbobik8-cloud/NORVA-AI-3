package ai.norva.chat

import ai.norva.inference.*
import ai.norva.policy.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WorkbenchTest {
    private val models = DefaultCatalog.models.filter { !it.experimental }
    private val engine = FakeEngine()
    private val bench = Workbench(engine,{ it.id },{DeviceMemory(12L shl 30, 8L shl 30)}, {PowerState(0,85,false,false)}, {4096})
    @Test fun twoIndependentDraftsThenCrossReviewAndSynthesis() = runTest {
        listOf("draftAlpha","draftBeta","critiqueB","critiqueA","finalAnswer").forEach { engine.script(text(it),finished()) }
        val stages = mutableListOf<String>()
        val out = bench.panel("question",models[0],models[1],stages::add)
        assertEquals(5,stages.size)
        assertEquals(listOf(models[0].id,models[1].id,models[0].id), engine.loadCalls.map { it.modelPath })
        assertFalse("draftAlpha" in engine.requests[1].prompt)
        assertTrue("draftAlpha" in engine.requests[2].prompt && "draftBeta" in engine.requests[3].prompt)
        assertTrue("critiqueA" in engine.requests[4].prompt && "critiqueB" in engine.requests[4].prompt)
        assertTrue(out.startsWith("finalAnswer"))
    }
    @Test fun duplicateModelRefused() = runTest {
        assertFailsWith<IllegalArgumentException> { bench.panel("q",models[0],models[0]) {} }
        assertTrue(engine.loadCalls.isEmpty())
    }
    @Test fun errorInCritiqueDoesNotProduceConsensus() = runTest {
        repeat(2) { engine.script(text("draft"),finished()) }
        engine.script(GenerationEvent.Failed(EngineError.DECODE_FAILED,"decode"))
        assertFailsWith<IllegalStateException> { bench.panel("q",models[0],models[1]) {} }
    }
}
