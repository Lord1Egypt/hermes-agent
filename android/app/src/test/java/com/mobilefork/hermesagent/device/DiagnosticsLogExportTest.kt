package com.mobilefork.hermesagent.device

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
class DiagnosticsLogExportTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before fun clearOnlyTestDiagnostics() {
        HermesCrashLogStore.clearAllForTest(context)
        LocalModelRuntimeDiagnostics.clearForTest(context)
    }

    @Test fun reportIsUsefulWithoutACrashOrModelAndKeepsTypedMemoryWhenRedactingFailures() {
        val empty = HermesCrashLogStore.exportLogsText(context)
        assertTrue(empty.contains("agent-diagnostic-report-v1"))
        val before = JSONObject(LocalModelRuntimeDiagnostics.exportSupportSnapshot(context))
        assertEquals(Build.MODEL, before.getJSONObject("device").getString("model"))
        assertTrue(before.getJSONObject("current_memory").has("available_bytes"))
        assertTrue(before.isNull("last_start_attempt"))
        assertNull(LocalModelRuntimeDiagnostics.readSnapshot(context))
        assertTrue(empty.endsWith("End of Agent diagnostics\n"))

        // Ten-digit RAM counters used to be misidentified as telephone numbers by a
        // regex over serialized JSON. String values and secret-keyed fields still redact.
        val memory = LocalModelRuntimeDiagnostics.MemorySnapshot(
            8_000_000_000L, 2_672_004_000L, 500_000_000L, false,
            201_326_592L, 536_870_912L, 10_000_000L,
        )
        val model = File(context.cacheDir, "gemma-4-E2B-it-UD-IQ3_XXS.gguf").apply { writeBytes(ByteArray(128)) }
        val decision = LocalModelRuntimeDiagnostics.PreflightDecision(false, 512, 3_000_000_000L, "blocked", "Only 0.2 GB usable RAM")
        val id = LocalModelRuntimeDiagnostics.beginAttempt(context, "llama.cpp", model, "cpu", 2048, 512, memory, decision,
            ramBypassRequested = true)
        LocalModelRuntimeDiagnostics.finishAttempt(context, id, "failed", "memory_preflight",
            "Email user@example.test Authorization: Bearer export-private-token phone +1 415 555 1212 /home/privateuser/file")
        val diagnostic = JSONObject(LocalModelRuntimeDiagnostics.exportSupportSnapshot(context))
        val attempt = diagnostic.getJSONObject("last_start_attempt")
        assertEquals(2_672_004_000L, attempt.getJSONObject("memory").getLong("available_bytes"))
        assertEquals(3_000_000_000L, attempt.getLong("estimated_additional_bytes"))
        assertEquals(model.name, attempt.getString("model_file"))
        assertTrue(attempt.getBoolean("ram_check_bypass_requested"))
        val report = HermesCrashLogStore.exportLogsText(context)
        for (secret in listOf("user@example.test", "export-private-token", "+1 415 555 1212", "/home/privateuser")) {
            assertFalse(secret, report.contains(secret))
        }
        assertTrue(report.contains("memory_preflight"))
        val nested = JSONObject().put("memory_bytes", 2_672_004_000L)
            .put("extra", JSONArray().put(JSONObject().put("api_key", "opaque-value-without-known-prefix")))
        val redacted = HermesCrashLogStore.redactJsonForDiagnostics(nested)
        assertEquals(2_672_004_000L, redacted.getLong("memory_bytes"))
        assertEquals("[REDACTED_SECRET]", redacted.getJSONArray("extra").getJSONObject(0).getString("api_key"))
        assertEquals("opaque-value-without-known-prefix", nested.getJSONArray("extra").getJSONObject(0).getString("api_key"))
    }

    @Test fun writerPreservesUtf8AndNeverReportsSuccessForNullWriteFlushOrCloseFailures() {
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        val text = "Agent diagnostics: Pixel 8 — 诊断 café\nEnd of Agent diagnostics\n"
        assertEquals(text.toByteArray(Charsets.UTF_8).size, DiagnosticsLogExport.writeUtf8(text) { output })
        assertEquals(text, output.toString("UTF-8"))
        assertTrue(closed)
        for (failure in listOf("null", "open", "write", "flush", "close")) {
            assertThrows("Provider failure at $failure must be surfaced", IOException::class.java) {
                DiagnosticsLogExport.writeUtf8(text) {
                    when (failure) {
                        "null" -> null
                        "open" -> throw IOException("provider unavailable")
                        else -> object : OutputStream() {
                            override fun write(value: Int) { if (failure == "write") throw IOException("no space") }
                            override fun flush() { if (failure == "flush") throw IOException("flush failed") }
                            override fun close() { if (failure == "close") throw IOException("commit failed") }
                        }
                    }
                }
            }
        }
        var opened = false
        for (invalid in listOf("", "x".repeat(DiagnosticsLogExport.MAX_EXPORT_BYTES + 1))) {
            assertThrows(IOException::class.java) { DiagnosticsLogExport.writeUtf8(invalid) { opened = true; output } }
        }
        assertFalse("Invalid reports must not create a destination", opened)
    }

    @Test fun oversizedSavedRecordsAreBoundedWithoutHidingThatEvidenceWasOmitted() {
        val dir = File(context.filesDir, "hermes-diagnostics").apply { mkdirs() }
        File(dir, "local-model-runtime.json").writeText("x".repeat(300_000))
        File(dir, "last-crash.json").writeText("x".repeat(300_000))
        File(dir, "diagnostics-log.jsonl").writeText(
            "{\"message\":\"earlier-omitted\"}\n".repeat(10_000) + "{\"message\":\"last-retained-event\"}\n")
        val report = HermesCrashLogStore.exportLogsText(context)
        assertTrue(report.contains("exceeds 128 KiB"))
        assertTrue(report.contains("Earlier diagnostic events omitted"))
        assertTrue(report.contains("last-retained-event"))
        assertTrue(report.toByteArray().size < DiagnosticsLogExport.MAX_EXPORT_BYTES)
        assertTrue(report.endsWith("End of Agent diagnostics\n"))
    }
}
