package ai.norva.inference

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CpuGuardTest {
    private val info = "CPU : NEON = 1 | ARM_FMA = 1 | FP16_VA = 1 | DOTPROD = 1 | MATMUL_INT8 = 1 | "
    private val full = "processor\t: 0\nFeatures\t: fp asimd asimddp i8mm bf16\n"
    private val noI8mm = "processor\t: 0\nFeatures\t: fp asimd asimddp\n"

    @Test fun allInstructionsPresentIsFine() = assertNull(CpuGuard.check(info, full))

    @Test fun missingI8mmIsReported() {
        val msg = CpuGuard.check(info, noI8mm)
        assertNotNull(msg)
        assertTrue("i8mm" in msg && "norva.cpuArch" in msg)
    }

    @Test fun libraryBuiltWithoutI8mmNeedsNoCpuSupport() =
        assertNull(CpuGuard.check("DOTPROD = 1 | MATMUL_INT8 = 0 | ", noI8mm))

    @Test fun unknownCpuInfoDoesNotBlock() = assertNull(CpuGuard.check(info, ""))
}
