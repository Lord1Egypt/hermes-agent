package com.mobilefork.hermesagent.device

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import java.util.UUID

/** A user-requested SAF snapshot for terminal tools, not a mount or two-way sync. */
internal object SharedFolderWorkspaceCopy {
    data class Limits(val entries: Int = 10_000, val bytes: Long = 512L * 1024 * 1024, val depth: Int = 32)
    data class Result(val directory: File, val files: Int, val bytes: Long)

    fun copy(context: Context, treeUri: Uri, limits: Limits = Limits(), checkCurrent: () -> Unit = {}): Result {
        require(treeUri.scheme == "content") { "Choose a folder with Android's document picker" }
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("The selected provider did not supply a document tree")
        return copyTree(
            root = root,
            workspace = DeviceStateWriter.workspaceDir(context),
            stagingParent = File(context.filesDir, "shared-folder-staging"),
            open = { context.contentResolver.openInputStream(it.uri) },
            limits = limits,
            checkCurrent = checkCurrent,
            children = { listChildren(context, it, limits.entries) },
        )
    }

    private fun isSymbolicLink(file: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw IOException("Cannot inspect workspace storage", error)
    }

    private fun listChildren(context: Context, directory: DocumentFile, maxEntries: Int): List<DocumentFile> {
        val id = DocumentsContract.getDocumentId(directory.uri)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(directory.uri, id)
        val cursor = context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?: throw IOException("Provider did not return folder contents")
        return cursor.use {
            val column = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val result = ArrayList<DocumentFile>()
            while (it.moveToNext()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Folder copy cancelled")
                if (result.size >= maxEntries) throw IOException("Folder has too many entries; select a smaller subfolder")
                val childId = it.getString(column) ?: throw IOException("Provider returned an invalid document identifier")
                val childUri = DocumentsContract.buildDocumentUriUsingTree(directory.uri, childId)
                result.add(DocumentFile.fromSingleUri(context, childUri)
                    ?: throw IOException("Cannot access the provider document"))
            }
            result
        }
    }

    internal fun copyTree(
        root: DocumentFile,
        workspace: File,
        stagingParent: File,
        open: (DocumentFile) -> InputStream?,
        limits: Limits = Limits(),
        checkCurrent: () -> Unit = {},
        children: (DocumentFile) -> List<DocumentFile> = { it.listFiles().toList() },
    ): Result {
        require(limits.entries > 0 && limits.bytes > 0 && limits.depth > 0)
        fun active() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Folder copy cancelled")
            checkCurrent()
        }
        active()
        if (!root.isDirectory || !root.canRead()) throw IOException("Folder permission is unavailable; grant access again")
        for (parent in listOf(workspace, stagingParent)) {
            if (isSymbolicLink(parent) || (!parent.isDirectory && !parent.mkdirs())) {
                throw IOException("Workspace storage is unavailable")
            }
        }
        // Each import owns its directory; repeats cannot overwrite terminal edits or originals.
        val staging = File(stagingParent, "copy-${UUID.randomUUID()}")
        if (!staging.mkdir()) throw IOException("Cannot allocate workspace staging directory")
        val target = File(workspace, "shared-${UUID.randomUUID()}")
        var files = 0
        var entries = 0
        var bytes = 0L
        val visited = mutableSetOf<String>()
        fun visit(source: DocumentFile, destination: File, depth: Int) {
            active()
            if (depth > limits.depth || !visited.add(source.uri.toString())) {
                throw IOException("Folder nesting is too deep or the provider returned a cycle")
            }
            // A failed query is not an empty folder: canRead/exists is rechecked around enumeration.
            if (!source.exists() || !source.canRead()) throw IOException("Folder access was lost during copying")
            val documents = children(source)
            if (!source.exists() || !source.canRead()) throw IOException("Folder access was lost during copying")
            val names = mutableSetOf<String>()
            for (child in documents) {
                active()
                if (++entries > limits.entries) throw IOException("Folder copy exceeds ${limits.entries} entries; select a smaller subfolder")
                val name = child.name ?: throw IOException("Provider returned an unnamed document")
                if (name.isEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' } ||
                    !names.add(name)) {
                    throw IOException("Provider returned an unsafe or duplicate document name")
                }
                val output = File(destination, name)
                if (output.exists() || output.canonicalFile.parentFile != destination.canonicalFile) {
                    throw IOException("Refusing to overwrite an existing workspace entry")
                }
                when {
                    child.isDirectory -> {
                        if (!output.mkdir()) throw IOException("Cannot create workspace directory")
                        visit(child, output, depth + 1)
                    }
                    child.isFile -> {
                        if (child.isVirtual) throw IOException("Virtual documents need an explicit export; select another folder")
                        if (child.length() > limits.bytes - bytes) throw IOException("Folder copy exceeds ${limits.bytes} bytes; select a smaller subfolder")
                        val input = open(child) ?: throw IOException("Provider could not open $name")
                        input.use { sourceStream ->
                            output.outputStream().use { sink ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    active()
                                    val count = sourceStream.read(buffer)
                                    active()
                                    if (count < 0) break
                                    if (count.toLong() > limits.bytes - bytes) throw IOException("Folder copy exceeds ${limits.bytes} bytes; select a smaller subfolder")
                                    if (count > 0) {
                                        sink.write(buffer, 0, count)
                                        bytes += count
                                    }
                                }
                                sink.fd.sync()
                            }
                        }
                        files++
                    }
                    else -> throw IOException("Provider returned an unreadable document: $name")
                }
            }
        }
        try {
            visit(root, staging, 0)
            active()
            if (target.exists() || !staging.renameTo(target)) throw IOException("Could not publish the complete workspace copy")
            return Result(target, files, bytes)
        } finally {
            // Never touch the provider, previous snapshots, or the user's other workspace files.
            if (staging.exists()) staging.deleteRecursively()
        }
    }
}
