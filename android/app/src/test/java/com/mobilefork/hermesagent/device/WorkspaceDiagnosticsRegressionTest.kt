package com.mobilefork.hermesagent.device

import android.app.ActivityManager
import android.content.Context
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WorkspaceDiagnosticsRegressionTest {
    @Test fun diagnosticExportWorksWithoutAModelAndDistinguishesCurrentMemoryFromTheLastAttempt() {
        val context: Context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val old = LocalModelRuntimeDiagnostics.MemorySnapshot(6_000_000_000L, 600_000_000L, 300_000_000L,
            true, 256_000_000L, 512_000_000L, 20_000_000L)
        val model = File(context.cacheDir, "diagnostic-fixture.gguf").apply { writeBytes(ByteArray(64)) }
        val decision = LocalModelRuntimeDiagnostics.evaluatePreflight("llama.cpp", model.length(), 4096, old, true)
        LocalModelRuntimeDiagnostics.beginAttempt(context, "llama.cpp", model, "cpu", 4096,
            decision.effectiveContextTokens, old, decision, ramBypassRequested = true)
        shadowOf(manager).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            totalMem = 6_000_000_000L; availMem = 2_400_000_000L; threshold = 300_000_000L; lowMemory = false
        })
        val backendBefore = OnDeviceBackendManager.currentStatus()
        val export = JSONObject(LocalModelRuntimeDiagnostics.exportSupportSnapshot(context))
        assertEquals("ActivityManager.MemoryInfo", export.getString("memory_source"))
        assertEquals(2_400_000_000L, export.getJSONObject("current_memory").getLong("available_bytes"))
        assertEquals(2_100_000_000L, export.getJSONObject("current_memory").getLong("usable_available_bytes"))
        val attempt = export.getJSONObject("last_start_attempt")
        assertEquals(600_000_000L, attempt.getJSONObject("memory").getLong("available_bytes"))
        assertTrue(attempt.getBoolean("ram_check_bypass_requested"))
        assertEquals("dangerous_bypass", attempt.getString("preflight_level"))
        assertEquals(backendBefore, OnDeviceBackendManager.currentStatus())
        LocalModelRuntimeDiagnostics.clearForTest(context)
        assertTrue(JSONObject(LocalModelRuntimeDiagnostics.exportSupportSnapshot(context)).isNull("last_start_attempt"))
    }

    @Test fun prootWorkspaceBindingAcceptsOnlyAQuotedFilesystemPathAndKeepsLegacyCallsUnchanged() {
        val workspace = "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/workspace"
        val command = HermesLinuxSandboxBridge.runCommandFor("/prefix", "debian", "ls /workspace", workspacePath = workspace)
        assertTrue(command, command.contains("--bind '$workspace:/workspace'"))
        assertTrue(command, command.contains("HERMES_WORKSPACE=/workspace; export HERMES_WORKSPACE;"))
        val ordinary = HermesLinuxSandboxBridge.runCommandFor("/prefix", "debian", "pwd")
        assertFalse(ordinary.contains("--bind"))
        assertFalse(ordinary.contains("HERMES_WORKSPACE"))
        for (invalid in listOf("content://provider/tree/root", "relative/path", "/path:other", "/path\u0000")) {
            assertThrows(IllegalArgumentException::class.java) {
                HermesLinuxSandboxBridge.runCommandFor("/prefix", "debian", "pwd", workspacePath = invalid)
            }
        }
    }
}
