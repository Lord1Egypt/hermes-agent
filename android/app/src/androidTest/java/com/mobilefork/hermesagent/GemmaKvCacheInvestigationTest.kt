package com.mobilefork.hermesagent

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilefork.hermesagent.backend.LlamaCppLaunchConfig
import com.mobilefork.hermesagent.backend.LlamaCppRuntimeLane
import com.mobilefork.hermesagent.backend.LlamaCppServerController
import com.mobilefork.hermesagent.device.HermesLinuxSubsystemBridge
import com.mobilefork.hermesagent.device.LocalModelRuntimeDiagnostics
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Explicit research gate using the APK's real engine, not a production preflight bypass.
 * The normal controller's default context remains unchanged until these results qualify it.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37)
class GemmaKvCacheInvestigationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun pinnedGemmaSupportsRequestedCacheContextAndRetrieval() {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("agentModelPath").orEmpty()
        assumeTrue("Explicit hash-pinned GGUF fixture required", modelPath.isNotEmpty())
        val modelHash = args.getString("agentModelSha256").orEmpty()
        require(Regex("[a-f0-9]{64}").matches(modelHash))
        val cache = args.getString("agentKvCache", "f16")!!
        require(cache in setOf("f16", "turbo3"))
        // Opt-in diagnostic only: never change the application's native quality guard.
        val strictSymmetric = args.getString("agentKvRequireSymmetric") == "true"
        require(!strictSymmetric || cache == "turbo3")
        val probeId = args.getString("agentProbeId") ?: UUID.randomUUID().toString()
        require(Regex("[a-z0-9-]{1,64}").matches(probeId))
        val workers = args.getString("agentKvWorkers", "4")!!.toInt()
        val niceness = args.getString("agentKvNiceness", "0")!!.toInt()
        require(workers in 1..4 && niceness in 0..19)
        val size = args.getString("agentKvContext", "4096")!!.toInt()
        val caseName = "${cache}-${size}" + if (strictSymmetric) "-symmetric" else ""
        require(size in setOf(2048, 4096, 8192, 16384, 32768))
        val model = File(modelPath)
        require(model.isFile && model.length() in 1..6_000_000_000L)
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        assertEquals(modelHash, digest.digest().joinToString("") { "%02x".format(it) })
        assertNull(LlamaCppServerController.stop())
        val memory = LocalModelRuntimeDiagnostics.captureMemory(context)
        require(!memory.lowMemory && memory.usableAvailableBytes > 2_500_000_000L) {
            "Controlled comparison needs measured initial headroom; no OS or app limiter will be disabled"
        }
        HermesLinuxSubsystemBridge.ensureInstalled(context)
        val executable = File(HermesLinuxSubsystemBridge.experimentalLlamaServerPath(context))
        require(executable.isFile && executable.canExecute())
        val key = UUID.randomUUID().toString()
        val port = 18793
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { }
        val config = LlamaCppLaunchConfig(lane = LlamaCppRuntimeLane.TURBOQUANT,
            cacheTypeK = cache, cacheTypeV = cache, flashAttention = "on")
        val command = LlamaCppServerController.shellCommandForLaunch(executable.absolutePath,
            model.absolutePath, port, availableProcessors = workers, contextSizeOverride = size,
            launchConfig = config, apiKey = key).let { command ->
                // Lower only this owned diagnostic process's scheduling priority.
                // Watchdog, Android admission and production launch policy are unchanged.
                if (niceness == 0) command else command.replaceFirst("exec ", "exec /system/bin/nice -n $niceness ")
            } + " -lv 5"
        val output = File(context.getExternalFilesDir(null), "gemma-kv-investigation/$probeId")
        check(output.mkdirs()) { "Each probe requires a fresh evidence directory" }
        val result = JSONObject().put("schema", "agent-gemma-kv-comparison-v2")
            .put("probe_id", probeId).put("workers_requested", workers).put("niceness_requested", niceness)
            .put("watchdog_policy_changed", false)
            .put("sdk", Build.VERSION.SDK_INT).put("model_name", model.name)
            .put("model_sha256", modelHash).put("model_bytes", model.length())
            .put("cache_k", cache).put("cache_v", cache).put("context_requested", size)
            .put("requested_cache_k", cache).put("requested_cache_v", cache)
            .put("explicit_symmetric_probe", strictSymmetric)
            .put("native_auto_asymmetric_policy", if (strictSymmetric) "disabled_for_this_test_process_only" else "native_default")
            .put("engine_sha256", executable.inputStream().use { stream ->
                val hash = MessageDigest.getInstance("SHA-256"); val block = ByteArray(1024 * 1024)
                while (true) { val n = stream.read(block); if (n < 0) break; hash.update(block, 0, n) }
                hash.digest().joinToString("") { "%02x".format(it) }
            }).put("before", JSONObject(LocalModelRuntimeDiagnostics.exportSupportSnapshot(context)))
            .put("production_context_policy_changed", false).put("inference_device", "cpu")
            .put("passed", false)
        val processBuilder = ProcessBuilder("/system/bin/sh", "-c", "echo AGENT_PROBE_PID=$$; $command")
            .directory(context.filesDir).redirectErrorStream(true)
        if (strictSymmetric) processBuilder.environment()["TURBO_AUTO_ASYMMETRIC"] = "0"
        val process = processBuilder.start()
        val alive = AtomicBoolean(true)
        val pid = AtomicInteger(0)
        val peakRss = AtomicLong(0)
        val peakPss = AtomicLong(0)
        val lowHeadroom = AtomicBoolean(false)
        val logs = StringBuffer()
        val logReadError = AtomicReference<String?>(null)
        val monitorError = AtomicReference<String?>(null)
        val progress = AtomicReference("")
        val currentStage = AtomicReference("launching")
        val memorySamples = AtomicInteger(0)
        val progressFile = File(output, "progress.json")
        val startedElapsed = android.os.SystemClock.elapsedRealtime()
        fun persistProgress() {
            val payload = JSONObject().put("probe_id", probeId).put("stage", currentStage.get())
                .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - startedElapsed)
                .put("native_pid", pid.get()).put("memory_samples", memorySamples.get())
                .put("peak_native_rss_kib", peakRss.get()).put("peak_native_pss_kib", peakPss.get())
                .put("last_native_progress", progress.get()).put("low_headroom_abort", lowHeadroom.get())
            val temporary = File(output, "progress.partial")
            temporary.writeText(payload.toString(2))
            check(temporary.renameTo(progressFile)) { "Cannot publish diagnostic progress" }
        }
        val logPath = File(output, "${caseName}-engine.log")
        val drain = thread(name = "AgentKvProbeLog", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    if (line.startsWith("AGENT_PROBE_PID=")) pid.set(line.substringAfter('=').toInt())
                    val safe = line.replace(key, "[redacted]")
                    if (safe.contains("prompt processing") || safe.contains("prompt eval time") || safe.contains("eval time")) {
                        progress.set(safe.takeLast(500))
                    }
                    synchronized(logs) {
                        logs.append(safe).append('\n')
                        if (logs.length > 250_000) logs.delete(0, logs.length - 250_000)
                    }
                    if (logPath.length() < 250_000) logPath.appendText(safe + "\n")
                } }
            } catch (error: IOException) {
                // Process.destroy closes the pipe on another thread. Preserve unexpected
                // reads, but do not let expected teardown kill JUnit before evidence saves.
                if (alive.get()) logReadError.set(error.toString())
            }
        }
        val monitor = thread(name = "AgentKvProbeMemory", isDaemon = true) {
            var lastCheckpoint = 0L
            try {
                while (alive.get()) {
                    val ownedPid = pid.get()
                    if (ownedPid > 0) {
                        val status = File("/proc/$ownedPid/status")
                        val rollup = File("/proc/$ownedPid/smaps_rollup")
                        if (status.isFile && rollup.isFile) {
                            Regex("(?m)^VmRSS:\\s+(\\d+)").find(status.readText())?.groupValues?.get(1)?.toLong()?.let {
                                peakRss.updateAndGet { old -> maxOf(old, it) }
                            }
                            Regex("(?m)^Pss:\\s+(\\d+)").find(rollup.readText())?.groupValues?.get(1)?.toLong()?.let {
                                peakPss.updateAndGet { old -> maxOf(old, it) }
                            }
                            memorySamples.incrementAndGet()
                        }
                    }
                    val current = LocalModelRuntimeDiagnostics.captureMemory(context)
                    if (current.lowMemory || current.usableAvailableBytes < 512_000_000L) {
                        lowHeadroom.set(true)
                        persistProgress()
                        process.destroy()
                        break
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastCheckpoint >= 5_000L) {
                        persistProgress()
                        lastCheckpoint = now
                    }
                    Thread.sleep(500)
                }
            } catch (error: Exception) {
                if (alive.get()) {
                    monitorError.set(error.toString())
                    process.destroy()
                }
            }
        }
        val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS).callTimeout(300, TimeUnit.SECONDS).build()
        val readinessClient = client.newBuilder().readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS).build()
        fun request(path: String, payload: JSONObject? = null, readiness: Boolean = false, longBudgetSeconds: Long = 0): JSONObject {
            val builder = Request.Builder().url("http://127.0.0.1:$port$path").header("Authorization", "Bearer $key")
            if (payload != null) builder.post(payload.toString().toRequestBody("application/json".toMediaType()))
            val transport = when {
                readiness -> readinessClient
                longBudgetSeconds > 0 -> client.newBuilder()
                    .readTimeout(longBudgetSeconds, TimeUnit.SECONDS)
                    .callTimeout(longBudgetSeconds + 15, TimeUnit.SECONDS).build()
                else -> client
            }
            return transport.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) { "HTTP ${response.code}: ${body.take(400)}" }
                JSONObject(body)
            }
        }
        val samples = JSONArray()
        result.put("samples", samples)
        val destination = File(output, "${caseName}.json")
        fun checkpoint(stage: String) {
            currentStage.set(stage)
            result.put("stage", stage).put("engine_log", logs.toString())
            destination.writeText(result.toString(2))
        }
        try {
            checkpoint("launching")
            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150)
            var ready = false
            while (System.nanoTime() < readyDeadline && !lowHeadroom.get()) {
                try { if (request("/v1/models", readiness = true).getJSONArray("data").length() > 0) { ready = true; break } }
                catch (_: Exception) { Thread.sleep(250) }
                if (runCatching { process.exitValue() }.isSuccess) break
            }
            assertTrue("Engine did not become ready: ${logs.takeLast(1400)}", ready)
            // Capture startup evidence before the rolling log can evict it during prefill.
            val startupLog = synchronized(logs) { logs.toString() }
            val promotedK = Regex("upgrading K from ([a-z0-9_]+) to ([a-z0-9_]+)")
                .findAll(startupLog).map { it.groupValues[2] }.toSet()
            val allocatedK = Regex("K \\(([a-z0-9_]+)\\):")
                .findAll(startupLog).map { it.groupValues[1] }.toSet()
            val allocatedV = Regex("V \\(([a-z0-9_]+)\\):")
                .findAll(startupLog).map { it.groupValues[1] }.toSet()
            result.put("native_cache_promotions", JSONArray(promotedK.toList()))
                .put("allocated_cache_k_types", JSONArray(allocatedK.toList()))
                .put("allocated_cache_v_types", JSONArray(allocatedV.toList()))
                .put("effective_cache_k", allocatedK.singleOrNull() ?: JSONObject.NULL)
                .put("effective_cache_v", allocatedV.singleOrNull() ?: JSONObject.NULL)
                .put("cache_allocation_log", startupLog.lineSequence()
                    .filter { it.contains("llama_kv_cache") || it.contains("K (") || it.contains("V (") }
                    .take(100).joinToString("\n"))
            val contextSlots = Regex("n_ctx_slot = (\\d+)").findAll(startupLog)
                .map { it.groupValues[1].toInt() }.toSet()
            result.put("effective_context_slots", JSONArray(contextSlots.toList()))
            checkpoint("engine_ready")
            assertEquals("Native context must match the requested window", setOf(size), contextSlots)
            assertEquals("Native V allocation was not positively identified", setOf(cache), allocatedV)
            if (!strictSymmetric && cache == "f16") assertEquals(setOf("f16"), allocatedK)
            if (strictSymmetric) {
                assertTrue("K was promoted despite explicit symmetric test: $promotedK", promotedK.isEmpty())
                assertEquals("Native allocation must positively identify real Turbo3 K", setOf("turbo3"), allocatedK)
                assertEquals("Native allocation must positively identify real Turbo3 V", setOf("turbo3"), allocatedV)
            }
            val actualName = request("/v1/models").getJSONArray("data").getJSONObject(0).getString("id")
            val short = LlamaCppServerController.releaseMatrixCompletionPayload(actualName, LlamaCppRuntimeLane.TURBOQUANT)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "What is the capital of France? Answer only the city.")))
            checkpoint("short_completion")
            val shortResponse = request("/v1/chat/completions", short)
            val shortAnswer = shortResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
            result.put("short_answer", shortAnswer)
            assertTrue("Short factual answer failed: $shortAnswer", shortAnswer.contains("Paris", true))
            checkpoint("short_completion_verified")
            fun text(lines: Int): String = buildString {
                append("Read the following reference. Find the exact codes for ALPHA, BETA, and GAMMA. Other lines are background.\n")
                for (i in 0 until lines) {
                    when (i) {
                        lines / 8 -> append("IMPORTANT: ALPHA code is CEDAR-731.\n")
                        lines / 2 -> append("IMPORTANT: BETA code is MAPLE-492.\n")
                        lines * 7 / 8 -> append("IMPORTANT: GAMMA code is BIRCH-286.\n")
                        else -> append("Record $i: A quiet river passes through the northern valley.\n")
                    }
                }
                append("Now return the three exact codes for ALPHA, BETA, GAMMA, in that order. No explanation.")
            }
            var count = size / 20
            var prompt = text(count)
            var tokens = request("/tokenize", JSONObject().put("content", prompt).put("add_special", false)).getJSONArray("tokens").length()
            repeat(3) {
                if (tokens < size * 0.60 || tokens > size * 0.80) {
                    count = (count.toDouble() * size * 0.70 / tokens).toInt().coerceAtLeast(24)
                    prompt = text(count)
                    tokens = request("/tokenize", JSONObject().put("content", prompt).put("add_special", false)).getJSONArray("tokens").length()
                }
            }
            require(tokens in (size * 0.55).toInt()..(size * 0.85).toInt()) { "Actual prompt did not exercise the requested window: $tokens/$size" }
            val query = LlamaCppServerController.releaseMatrixCompletionPayload(actualName, LlamaCppRuntimeLane.TURBOQUANT)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            result.put("tokenized_input", tokens)
            checkpoint("long_completion")
            val started = System.nanoTime()
            // The paired long-context runs reached ~5 tokens/s on this CPU AVD,
            // below the short-canary rate. Reserve measured-workload headroom;
            // no retrieval assertion, memory guard or production timeout is changed.
            val budget = (tokens / 4L * 4L / workers + 180L).coerceIn(900L, 3600L)
            result.put("long_request_budget_seconds", budget)
            checkpoint("long_completion")
            val response = request("/v1/chat/completions", query, longBudgetSeconds = budget)
            val answer = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
            val used = response.getJSONObject("usage").getInt("prompt_tokens")
            samples.put(JSONObject().put("prompt_tokens_tokenizer", tokens).put("prompt_tokens_used", used)
                .put("answer", answer).put("milliseconds", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                .put("checks", JSONArray(listOf("CEDAR-731", "MAPLE-492", "BIRCH-286").map { answer.contains(it) })))
            assertTrue("Prompt was silently truncated", used >= tokens)
            assertTrue("Long-context retrieval failed: $answer", listOf("CEDAR-731", "MAPLE-492", "BIRCH-286").all { answer.contains(it) })
            assertFalse("Memory safety monitor stopped the owned process", lowHeadroom.get())
            assertNull("Native log reader failed unexpectedly", logReadError.get())
            assertNull("Memory monitor failed unexpectedly", monitorError.get())
            assertTrue("No native memory samples were captured", memorySamples.get() > 0 && peakRss.get() > 0 && peakPss.get() > 0)
            result.put("passed", true)
        } finally {
            alive.set(false)
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            val stopped = process.waitFor(5, TimeUnit.SECONDS)
            monitor.join(2000)
            drain.join(2000)
            currentStage.set(if (result.optBoolean("passed") && stopped) "completed" else "failed")
            persistProgress()
            result.put("stage", currentStage.get()).put("monitor_error", monitorError.get())
                .put("native_pid", pid.get()).put("memory_samples", memorySamples.get())
                .put("log_read_error", logReadError.get()).put("stopped", stopped).put("low_headroom_abort", lowHeadroom.get())
                .put("peak_native_rss_kib", peakRss.get()).put("peak_native_pss_kib", peakPss.get())
                .put("after", JSONObject(LocalModelRuntimeDiagnostics.exportSupportSnapshot(context)))
                .put("engine_log", logs.toString())
            destination.writeText(result.toString(2))
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            assertTrue("Owned native engine failed to stop", stopped)
        }
    }
}
