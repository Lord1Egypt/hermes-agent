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
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
        val size = args.getString("agentKvContext", "4096")!!.toInt()
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
            model.absolutePath, port, availableProcessors = 4, contextSizeOverride = size,
            launchConfig = config, apiKey = key)
        val output = File(context.getExternalFilesDir(null), "gemma-kv-investigation").apply { mkdirs() }
        val result = JSONObject().put("schema", "agent-gemma-kv-comparison-v1")
            .put("sdk", Build.VERSION.SDK_INT).put("model_name", model.name)
            .put("model_sha256", modelHash).put("model_bytes", model.length())
            .put("cache_k", cache).put("cache_v", cache).put("context_requested", size)
            .put("engine_sha256", executable.inputStream().use { stream ->
                val hash = MessageDigest.getInstance("SHA-256"); val block = ByteArray(1024 * 1024)
                while (true) { val n = stream.read(block); if (n < 0) break; hash.update(block, 0, n) }
                hash.digest().joinToString("") { "%02x".format(it) }
            }).put("before", JSONObject(LocalModelRuntimeDiagnostics.exportSupportSnapshot(context)))
            .put("production_context_policy_changed", false).put("inference_device", "cpu")
            .put("passed", false)
        val process = ProcessBuilder("/system/bin/sh", "-c", "echo AGENT_PROBE_PID=$$; $command")
            .directory(context.filesDir).redirectErrorStream(true).start()
        val alive = AtomicBoolean(true)
        val pid = AtomicInteger(0)
        val peakRss = AtomicLong(0)
        val peakPss = AtomicLong(0)
        val lowHeadroom = AtomicBoolean(false)
        val logs = StringBuffer()
        val drain = thread(name = "AgentKvProbeLog", isDaemon = true) {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                if (line.startsWith("AGENT_PROBE_PID=")) pid.set(line.substringAfter('=').toInt())
                synchronized(logs) {
                    logs.append(line.replace(key, "[redacted]")).append('\n')
                    if (logs.length > 250_000) logs.delete(0, logs.length - 250_000)
                }
            } }
        }
        val monitor = thread(name = "AgentKvProbeMemory", isDaemon = true) {
            while (alive.get()) {
                val ownedPid = pid.get()
                if (ownedPid > 0) {
                    runCatching {
                        val status = File("/proc/$ownedPid/status").readText()
                        Regex("(?m)^VmRSS:\\s+(\\d+)").find(status)?.groupValues?.get(1)?.toLong()?.let {
                            peakRss.updateAndGet { old -> maxOf(old, it) }
                        }
                        val rollup = File("/proc/$ownedPid/smaps_rollup").readText()
                        Regex("(?m)^Pss:\\s+(\\d+)").find(rollup)?.groupValues?.get(1)?.toLong()?.let {
                            peakPss.updateAndGet { old -> maxOf(old, it) }
                        }
                    }
                }
                val current = LocalModelRuntimeDiagnostics.captureMemory(context)
                if (current.lowMemory || current.usableAvailableBytes < 512_000_000L) {
                    lowHeadroom.set(true)
                    process.destroy()
                    break
                }
                Thread.sleep(500)
            }
        }
        val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS).callTimeout(300, TimeUnit.SECONDS).build()
        fun request(path: String, payload: JSONObject? = null): JSONObject {
            val builder = Request.Builder().url("http://127.0.0.1:$port$path").header("Authorization", "Bearer $key")
            if (payload != null) builder.post(payload.toString().toRequestBody("application/json".toMediaType()))
            return client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) { "HTTP ${response.code}: ${body.take(400)}" }
                JSONObject(body)
            }
        }
        val samples = JSONArray()
        result.put("samples", samples)
        val destination = File(output, "${cache}-${size}.json")
        try {
            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150)
            var ready = false
            while (System.nanoTime() < readyDeadline && !lowHeadroom.get()) {
                try { if (request("/v1/models").getJSONArray("data").length() > 0) { ready = true; break } }
                catch (_: Exception) { Thread.sleep(250) }
                if (runCatching { process.exitValue() }.isSuccess) break
            }
            assertTrue("Engine did not become ready: ${logs.takeLast(1400)}", ready)
            val actualName = request("/v1/models").getJSONArray("data").getJSONObject(0).getString("id")
            val short = LlamaCppServerController.releaseMatrixCompletionPayload(actualName, LlamaCppRuntimeLane.TURBOQUANT)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "What is the capital of France? Answer only the city.")))
            val shortResponse = request("/v1/chat/completions", short)
            val shortAnswer = shortResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
            result.put("short_answer", shortAnswer)
            assertTrue("Short factual answer failed: $shortAnswer", shortAnswer.contains("Paris", true))
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
            val started = System.nanoTime()
            val response = request("/v1/chat/completions", query)
            val answer = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
            val used = response.getJSONObject("usage").getInt("prompt_tokens")
            samples.put(JSONObject().put("prompt_tokens_tokenizer", tokens).put("prompt_tokens_used", used)
                .put("answer", answer).put("milliseconds", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                .put("checks", JSONArray(listOf("CEDAR-731", "MAPLE-492", "BIRCH-286").map { answer.contains(it) })))
            assertTrue("Prompt was silently truncated", used >= tokens)
            assertTrue("Long-context retrieval failed: $answer", listOf("CEDAR-731", "MAPLE-492", "BIRCH-286").all { answer.contains(it) })
            assertFalse("Memory safety monitor stopped the owned process", lowHeadroom.get())
            result.put("passed", true)
        } finally {
            alive.set(false)
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            val stopped = process.waitFor(5, TimeUnit.SECONDS)
            monitor.join(2000)
            drain.join(2000)
            result.put("stopped", stopped).put("low_headroom_abort", lowHeadroom.get())
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
