package com.mobilefork.hermesagent.device

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException

/** Real ContentResolver query/openFile calls; the fake provider represents SAF, not a pathname. */
@RunWith(RobolectricTestRunner::class)
class SharedFolderWorkspaceCopyTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var provider: TreeProvider
    private val tree: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")

    @Before fun installProvider() {
        val files = temp.newFolder("app-files")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = files
            // Runtime grant dispatch is tested separately on-device; the unit fixture grants only its own provider.
            override fun checkCallingOrSelfUriPermission(uri: Uri, flags: Int): Int =
                if (uri.authority == AUTHORITY) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        provider = TreeProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }

    @Test fun nestedProviderDocumentsBecomeIndependentTerminalFilesWithoutOverwritingAnything() {
        provider.nodes += Node("notes", "笔记", "root", directory = true)
        val original = temp.newFile("provider-original.bin").apply { writeBytes(byteArrayOf(0, 1, -1, 10, 9)) }
        provider.nodes += Node("note", "Résumé.md", "notes", original = original)
        provider.nodes += Node("empty", ".obsidian", "root", directory = true)
        val workspace = DeviceStateWriter.workspaceDir(context)
        File(workspace, "keep.txt").writeText("existing terminal work")

        val first = SharedFolderWorkspaceCopy.copy(context, tree)
        assertEquals(1, first.files)
        assertEquals(original.length(), first.bytes)
        val copied = File(first.directory, "笔记/Résumé.md")
        assertArrayEquals(original.readBytes(), copied.readBytes())
        assertTrue(File(first.directory, ".obsidian").isDirectory)
        copied.writeText("edits in the terminal")
        val second = SharedFolderWorkspaceCopy.copy(context, tree)
        assertNotEquals(first.directory, second.directory)
        assertEquals("edits in the terminal", copied.readText())
        assertArrayEquals(byteArrayOf(0, 1, -1, 10, 9), original.readBytes())
        assertArrayEquals(original.readBytes(), File(second.directory, "笔记/Résumé.md").readBytes())
        assertEquals("existing terminal work", File(workspace, "keep.txt").readText())
        assertTrue(File(context.filesDir, "shared-folder-staging").listFiles().orEmpty().isEmpty())
        assertEquals(2, provider.opened)
    }

    @Test fun failedOrCancelledProviderCopiesNeverPublishPartialTreesOrModifyOriginals() {
        val original = temp.newFile("original.bin").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val workspace = DeviceStateWriter.workspaceDir(context)
        val keep = File(workspace, "keep.txt").apply { writeText("keep") }
        for (failure in listOf("query", "open", "traversal", "duplicate", "bytes", "entries", "depth", "cycle", "cancel")) {
            provider.reset()
            provider.nodes += Node("note", "note.bin", "root", original = original)
            var limit = SharedFolderWorkspaceCopy.Limits()
            when (failure) {
                "query" -> provider.failQuery = true
                "open" -> provider.failOpen = true
                "traversal" -> provider.nodes[1] = provider.nodes[1].copy(name = "../escape")
                "duplicate" -> provider.nodes += Node("second", "note.bin", "root", original = original)
                "bytes" -> limit = limit.copy(bytes = 8) // Provider deliberately omits its size; actual byte count must enforce the cap.
                "entries" -> { limit = limit.copy(entries = 1); provider.nodes += Node("second", "two.bin", "root", original = original) }
                "depth" -> { limit = limit.copy(depth = 1); provider.nodes += Node("dir", "folder", "root", directory = true); provider.nodes += Node("deep", "deep", "dir", directory = true) }
                "cycle" -> { provider.nodes += Node("loop", "loop", "root", directory = true); provider.cycle = true }
            }
            var checks = 0
            val error = runCatching {
                SharedFolderWorkspaceCopy.copy(context, tree, limit) {
                    if (failure == "cancel" && ++checks >= 5) throw IOException("Cancelled by the owner")
                }
            }.exceptionOrNull()
            assertNotNull("Expected failure: $failure", error)
            assertEquals("No partial workspace tree for $failure", listOf(keep.name), workspace.list()!!.toList())
            assertTrue("Staging cleanup for $failure", File(context.filesDir, "shared-folder-staging").listFiles().orEmpty().isEmpty())
            assertArrayEquals(ByteArray(64) { it.toByte() }, original.readBytes())
            assertEquals("keep", keep.readText())
        }
    }

    private data class Node(val id: String, val name: String, val parent: String = "", val directory: Boolean = false, val original: File? = null)

    private class TreeProvider : ContentProvider() {
        val nodes = mutableListOf(Node("root", "Vault", directory = true))
        var failQuery = false
        var failOpen = false
        var cycle = false
        var opened = 0
        fun reset() { nodes.clear(); nodes += Node("root", "Vault", directory = true); failQuery = false; failOpen = false; cycle = false }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
            val id = DocumentsContract.getDocumentId(uri)
            val children = uri.lastPathSegment == "children"
            if (children && failQuery) return null
            val selected = if (children) {
                if (cycle && id == "loop") listOf(nodes.first().copy(name = "back-to-root")) else nodes.filter { it.parent == id }
            } else nodes.filter { it.id == id }
            val columns = projection?.toList()?.toTypedArray() ?: arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE,
            )
            return MatrixCursor(columns).apply {
                selected.forEach { node ->
                    addRow(columns.map { column ->
                        when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> if (node.directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                            DocumentsContract.Document.COLUMN_FLAGS -> 0
                            DocumentsContract.Document.COLUMN_SIZE -> null
                            else -> null
                        }
                    }.toTypedArray<Any?>())
                }
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failOpen) throw java.io.FileNotFoundException("Provider is offline")
            check(mode == "r")
            opened++
            return ParcelFileDescriptor.open(nodes.single { it.id == DocumentsContract.getDocumentId(uri) }.original!!, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    }

    companion object { private const val AUTHORITY = "agent.shared-folder.test" }
}
