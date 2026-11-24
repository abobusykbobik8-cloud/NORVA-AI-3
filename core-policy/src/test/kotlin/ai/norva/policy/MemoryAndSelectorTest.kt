package ai.norva.policy

import ai.norva.policy.MemoryBudget.GIB
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryAndSelectorTest {
    // Android показывает у "12 ГБ" телефона около 11.2 ГиБ totalMem.
    private val total = (11.2 * GIB).toLong()
    private fun mem(availGib: Long) = DeviceMemory(total, availGib * GIB)

    private val m2 = DefaultCatalog.byId("qwen3.5-2b-q4km")!!
    private val m4 = DefaultCatalog.byId("qwen3.5-4b-q4km")!!
    private val m9 = DefaultCatalog.byId("qwen3.5-9b-q4km")!!

    @Test fun hardCapAppliesWhenMuchMemoryIsFree() {
        assertEquals((total * 0.40).toLong(), MemoryBudget.budgetBytes(mem(10)))
    }

    @Test fun balancedModelFitsDeepContextOnHealthyPhone() {
        val b = MemoryBudget.budgetBytes(mem(5))
        assertEquals(8192, MemoryBudget.maxContext(m4, b, 8192))
    }

    @Test fun strongModelNeverFitsOn12Gb() {
        val b = MemoryBudget.budgetBytes(mem(10))
        assertEquals(0, MemoryBudget.maxContext(m9, b, 4096))
    }

    @Test fun lowFreeMemoryDropsBalancedButKeepsSmall() {
        val b = MemoryBudget.budgetBytes(mem(3))
        assertEquals(0, MemoryBudget.maxContext(m4, b, 4096))
        assertEquals(4096, MemoryBudget.maxContext(m2, b, 4096))
    }

    @Test fun lowRamUsesLiteAndRespectsAndroidThreshold() {
        val lite = DefaultCatalog.byId("qwen3.5-0.8b-q4km")!!
        for (totalGb in 3..4) {
            val device = DeviceMemory(totalGb * GIB, 3 * GIB / 2, GIB / 3)
            for (mode in ThinkingMode.entries) {
                val s = LocalModelSelector.select(ModeProfiles.of(mode), MemoryBudget.budgetBytes(device), setOf(lite.id, m2.id))!!
                assertEquals(lite.id, s.model.id)
                assertTrue(s.contextTokens in 1024..2048)
                assertEquals(false, s.thinking)
            }
            assertEquals(0L, MemoryBudget.budgetBytes(device.copy(lowMemory = true)))
            assertEquals(0L, MemoryBudget.budgetBytes(device.copy(availBytes = GIB / 4)))
        }
    }

    @Test fun samplingDependsOnActualReasoningAndPhoto() {
        val deep = ModeProfiles.of(ThinkingMode.DEEP)
        assertEquals(0f, deep.qwenSampling(true, true, ModelTier.LITE).presencePenalty)
        assertEquals(1.5f, deep.qwenSampling(true, false, ModelTier.LITE).presencePenalty)
        assertEquals(0.7f, deep.qwenSampling(false, true, ModelTier.LITE).temperature)
        assertEquals(2f, deep.qwenSampling(false, false, ModelTier.LITE).presencePenalty)
        assertEquals(0.7f, deep.qwenSampling(false, false, ModelTier.BALANCED).temperature)
    }

    @Test fun estimateGrowsWithContext() {
        assertTrue(MemoryBudget.estimateBytes(m4, 8192) > MemoryBudget.estimateBytes(m4, 2048))
    }

    @Test fun normalPicksBalancedFastPicksSmall() {
        val b = MemoryBudget.budgetBytes(mem(5))
        val both = setOf(m2.id, m4.id)
        assertEquals(m4.id, LocalModelSelector.select(ModeProfiles.of(ThinkingMode.NORMAL), b, both)?.model?.id)
        assertEquals(m2.id, LocalModelSelector.select(ModeProfiles.of(ThinkingMode.FAST), b, both)?.model?.id)
    }

    @Test fun normalFallsBackToSmallWhenMemoryIsTight() {
        val b = MemoryBudget.budgetBytes(mem(3))
        val sel = LocalModelSelector.select(ModeProfiles.of(ThinkingMode.NORMAL), b, setOf(m2.id, m4.id))
        assertNotNull(sel)
        assertEquals(m2.id, sel.model.id)
    }

    @Test fun deepUsesThinkingOnlyWhenModelSupportsIt() {
        val b = MemoryBudget.budgetBytes(mem(5))
        val deep = ModeProfiles.of(ThinkingMode.DEEP)
        assertEquals(true, LocalModelSelector.select(deep, b, setOf(m4.id))?.thinking)
        assertEquals(true, LocalModelSelector.select(deep, b, setOf(m2.id))?.thinking)
    }

    @Test fun nothingInstalledMeansNoSelection() {
        val b = MemoryBudget.budgetBytes(mem(5))
        assertNull(LocalModelSelector.select(ModeProfiles.of(ThinkingMode.NORMAL), b, emptySet()))
    }

    @Test fun experimentalModelIsSkippedByDefault() {
        val b = MemoryBudget.budgetBytes(mem(10))
        assertNull(LocalModelSelector.select(ModeProfiles.of(ThinkingMode.DEEP), b, setOf(m9.id)))
    }

    @Test fun modesChangeRealParameters() {
        val f = ModeProfiles.of(ThinkingMode.FAST)
        val n = ModeProfiles.of(ThinkingMode.NORMAL)
        val d = ModeProfiles.of(ThinkingMode.DEEP)
        assertTrue(f.contextTokens < n.contextTokens && n.contextTokens < d.contextTokens)
        assertTrue(f.maxNewTokens < n.maxNewTokens && n.maxNewTokens < d.maxNewTokens)
        assertTrue(f.memoryTopK < n.memoryTopK && n.memoryTopK < d.memoryTopK)
        assertEquals(listOf(false, false, true), listOf(f.thinking, n.thinking, d.thinking))
        assertEquals(listOf(false, false, true), listOf(f.selfCheckPass, n.selfCheckPass, d.selfCheckPass))
    }
}
