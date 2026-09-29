package com.mobilefork.hermesagent.device

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/** Writes only to a document explicitly returned by Android's user-controlled save picker. */
internal object DiagnosticsLogExport {
    suspend fun save(context: Context, destination: Uri): Int = withContext(Dispatchers.IO) {
        require(destination.scheme == "content") { "A document-provider URI is required" }
        ensureActive()
        val report = HermesCrashLogStore.exportLogsText(context.applicationContext)
        ensureActive()
        writeUtf8(report) { context.contentResolver.openOutputStream(destination, "wt") }
    }

    // Keep collection and document-provider I/O off the UI thread, and report success only
    // after flush AND close succeed. Some providers return null instead of throwing.
    internal fun writeUtf8(report: String, openOutput: () -> OutputStream?): Int {
        val bytes = report.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_EXPORT_BYTES) {
            throw IOException("Diagnostic report is empty or exceeds the export size limit")
        }
        val output = openOutput() ?: throw IOException("Document provider returned no output stream")
        output.use {
            it.write(bytes)
            it.flush()
        }
        return bytes.size
    }

    internal const val MAX_EXPORT_BYTES = 1024 * 1024
}
