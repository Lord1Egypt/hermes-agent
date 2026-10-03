package com.mobilefork.hermesagent.device

import android.app.ApplicationExitInfo
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder
import java.io.File

@RunWith(RobolectricTestRunner::class)
class HistoricalModelExitRegressionTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val attemptId = "3b3c9b20-cd7c-4653-8bd5-2dc8c78c52ca"
    private val exitTime = 1_790_477_978_413L

    @Before fun reset() {
        HermesCrashLogStore.clearAllForTest(context)
        LocalModelRuntimeDiagnostics.clearForTest(context)
    }

    private fun saveAttempt(startedAt: Long = exitTime - 1_000): JSONObject {
        val attempt = JSONObject()
            .put("attempt_id", attemptId).put("started_at_ms", startedAt)
            .put("updated_at_ms", startedAt).put("status", "blocked")
            .put("stage", "memory_preflight").put("model_bytes", 2_372_993_120L)
            .put("estimated_additional_bytes", 2_105_645_528L)
            .put("memory", JSONObject().put("total_bytes", 7_679_983_616L))
        File(context.filesDir, "hermes-diagnostics/local-model-runtime.json").apply {
            parentFile!!.mkdirs()
            writeText(attempt.toString())
        }
        return attempt
    }

    private fun recordExit(summary: String?): JSONObject {
        val exit = ApplicationExitInfoBuilder.newBuilder()
            .setPid(1234).setTimestamp(exitTime).setProcessName(context.packageName).setImportance(400)
            .setReason(ApplicationExitInfo.REASON_LOW_MEMORY).setRss(176_560)
            .setProcessStateSummary(summary?.toByteArray(Charsets.UTF_8)).build()
        HermesCrashLogStore.recordHistoricalProcessExit(context, exit)
        return JSONObject(File(context.filesDir, "hermes-diagnostics/last-crash.json").readText())
    }

    @Test fun historicalExitRequiresItsOwnAttemptMarkerAndNeverTreatsBlockedLoadAsNativeFailure() {
        saveAttempt()
        for (summary in listOf(null, "other-summary", "agent-model-v1:other-attempt")) {
            val crash = recordExit(summary)
            assertTrue(crash.toString(), crash.isNull("local_model_runtime"))
            assertEquals(attemptId, crash.getJSONObject("last_saved_runtime_attempt").getString("attempt_id"))
            assertFalse(crash.getJSONObject("runtime_attempt_correlation").getBoolean("matched"))
        }
        val matched = recordExit("agent-model-v1:$attemptId")
        assertEquals(attemptId, matched.getJSONObject("local_model_runtime").getString("attempt_id"))
        val correlation = matched.getJSONObject("runtime_attempt_correlation")
        assertTrue(correlation.getBoolean("matched"))
        assertTrue(correlation.getString("detail").contains("blocked before native allocation"))
        assertEquals(1234, matched.getInt("process_id"))
        assertTrue(matched.getString("process_priority_explanation").contains("cached background process"))

        // The same PID or marker must not promote a snapshot written after this death.
        saveAttempt(exitTime + 1)
        assertTrue(recordExit("agent-model-v1:$attemptId").isNull("local_model_runtime"))

        // Capture keeps its startup snapshot even if another attempt overwrites the file
        // while Android's historical-exit query is running on the background worker.
        val preserved = saveAttempt()
        saveAttempt(exitTime + 1)
        val exit = ApplicationExitInfoBuilder.newBuilder().setTimestamp(exitTime)
            .setReason(ApplicationExitInfo.REASON_LOW_MEMORY)
            .setProcessStateSummary(LocalModelRuntimeDiagnostics.processExitMarker(attemptId)).build()
        HermesCrashLogStore.recordHistoricalProcessExit(context, exit, preserved)
        val captured = JSONObject(File(context.filesDir, "hermes-diagnostics/last-crash.json").readText())
        assertEquals(exitTime - 1_000, captured.getJSONObject("local_model_runtime").getLong("started_at_ms"))
        assertTrue(captured.isNull("last_saved_runtime_attempt"))
    }

    @Test fun persistedAndExportedEventsPreserveTypedMemoryCountersAndRedactSecrets() {
        saveAttempt().also { attempt ->
            attempt.put("detail", "contact jane@example.net phone +1 415 555 1212")
                .put("api_key", "private-key-value")
            File(context.filesDir, "hermes-diagnostics/local-model-runtime.json").writeText(attempt.toString())
        }
        val persistedCrash = recordExit(null).toString()
        assertFalse(persistedCrash.contains("jane@example.net"))
        assertFalse(persistedCrash.contains("415 555 1212"))
        assertFalse(persistedCrash.contains("private-key-value"))
        val eventFile = File(context.filesDir, "hermes-diagnostics/diagnostics-log.jsonl")
        val report = HermesCrashLogStore.exportLogsText(context)
        val exportedEvent = report.substringAfter("Recent diagnostic events\n").lineSequence().first()
        for (text in listOf(eventFile.readLines().last(), exportedEvent)) {
            val payload = JSONObject(text).getJSONObject("payload")
            // On the base implementation this is the blindly attached runtime object.
            val attempt = payload.optJSONObject("last_saved_runtime_attempt")
                ?: payload.getJSONObject("local_model_runtime")
            assertTrue(attempt.get("model_bytes") is Number)
            assertEquals(2_372_993_120L, attempt.getLong("model_bytes"))
            assertEquals(2_105_645_528L, attempt.getLong("estimated_additional_bytes"))
            assertEquals(7_679_983_616L, attempt.getJSONObject("memory").getLong("total_bytes"))
            assertFalse(text.contains("jane@example.net"))
            assertFalse(text.contains("415 555 1212"))
            assertFalse(text.contains("private-key-value"))
        }
    }
}
