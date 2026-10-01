package com.mobilefork.hermesagent.device

import org.junit.Assert.*
import org.junit.Test

class ModelMemoryAdmissionRegressionTest {
    @Test fun pixel8ReportBlocksWithoutConsentAndRetainsTheExplicitBypass() {
        val memory = LocalModelRuntimeDiagnostics.MemorySnapshot(
            totalBytes = 7_679_975_424L, availableBytes = 1_259_180_032L,
            thresholdBytes = 226_492_416L, lowMemory = false,
            memoryClassBytes = 268_435_456L, largeMemoryClassBytes = 536_870_912L,
            nativeHeapAllocatedBytes = 17_878_144L,
        )
        val blocked = LocalModelRuntimeDiagnostics.evaluatePreflight("llama.cpp", 2_372_993_120L, 2048, memory)
        assertFalse(blocked.allowed)
        assertEquals(1_032_687_616L, memory.usableAvailableBytes)
        assertTrue(blocked.estimatedAdditionalBytes > memory.usableAvailableBytes)
        val confirmed = LocalModelRuntimeDiagnostics.evaluatePreflight(
            "llama.cpp", 2_372_993_120L, 2048, memory, dangerouslySkipRamChecks = true,
        )
        assertTrue(confirmed.allowed)
        assertEquals("dangerous_bypass", confirmed.level)
        assertEquals(blocked.estimatedAdditionalBytes, confirmed.estimatedAdditionalBytes)
    }

    private fun snapshot(available: Long, low: Boolean = false) = LocalModelRuntimeDiagnostics.MemorySnapshot(
        totalBytes = 6_000_000_000L,
        availableBytes = available,
        thresholdBytes = 300_000_000L,
        lowMemory = low,
        memoryClassBytes = 256L * 1024 * 1024,
        largeMemoryClassBytes = 512L * 1024 * 1024,
        nativeHeapAllocatedBytes = 20_000_000L,
    )

    @Test fun nativeAdmissionReducesContextBeforeRejectingAModelThatFits() {
        val memory = snapshot(4_300_000_000L)
        val normal = LocalModelRuntimeDiagnostics.evaluatePreflight("llama.cpp", 4_200_000_000L, 4096, memory)
        assertTrue(normal.detail, normal.allowed)
        assertTrue("Context must be reduced rather than bypassing RAM checks", normal.effectiveContextTokens < 4096)
        assertTrue(normal.estimatedAdditionalBytes <= memory.usableAvailableBytes)
        assertNotEquals("dangerous_bypass", normal.level)
        // Java heap class is telemetry, never the budget for the native model.
        val differentHeap = LocalModelRuntimeDiagnostics.evaluatePreflight(
            "llama.cpp", 4_200_000_000L, 4096,
            memory.copy(memoryClassBytes = 64L * 1024 * 1024, largeMemoryClassBytes = 128L * 1024 * 1024),
        )
        assertEquals(normal, differentHeap)
    }

    @Test fun lowHeadroomBypassRemainsExplicitAndCannotApproveAnEmptyModel() {
        for (low in listOf(false, true)) {
            val memory = snapshot(600_000_000L, low)
            assertEquals(300_000_000L, memory.usableAvailableBytes)
            val blocked = LocalModelRuntimeDiagnostics.evaluatePreflight("llama.cpp", 2_500_000_000L, 4096, memory)
            assertFalse(blocked.allowed)
            val confirmed = LocalModelRuntimeDiagnostics.evaluatePreflight(
                "llama.cpp", 2_500_000_000L, 4096, memory, dangerouslySkipRamChecks = true,
            )
            assertTrue(confirmed.detail, confirmed.allowed)
            assertEquals("dangerous_bypass", confirmed.level)
            assertEquals(blocked.effectiveContextTokens, confirmed.effectiveContextTokens)
            assertFalse(LocalModelRuntimeDiagnostics.evaluatePreflight(
                "llama.cpp", 0, 4096, memory, dangerouslySkipRamChecks = true,
            ).allowed)
        }
    }
}
