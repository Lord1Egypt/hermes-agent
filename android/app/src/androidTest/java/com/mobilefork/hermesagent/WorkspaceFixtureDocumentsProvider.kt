package com.mobilefork.hermesagent

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** A real, read-only test-APK provider exercised through Android's system tree picker. */
class WorkspaceFixtureDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection?.toList()?.toTypedArray() ?: arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES,
        )
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    Root.COLUMN_ROOT_ID -> "workspace-regression"
                    Root.COLUMN_TITLE -> TITLE
                    Root.COLUMN_DOCUMENT_ID -> "root"
                    Root.COLUMN_FLAGS -> Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD
                    Root.COLUMN_MIME_TYPES -> "*/*"
                    Root.COLUMN_AVAILABLE_BYTES -> 1_000_000L
                    else -> null
                }
            }.toTypedArray<Any?>())
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = documents(listOf(documentId), projection)

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        documents(when (parentDocumentId) {
            "root" -> listOf("notes")
            "notes" -> listOf("note")
            else -> throw FileNotFoundException("Not a fixture folder")
        }, projection)

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        (parentDocumentId == "root" && documentId in listOf("notes", "note")) ||
            (parentDocumentId == "notes" && documentId == "note")

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (documentId != "note" || mode != "r") throw FileNotFoundException("Fixture is read-only")
        signal?.throwIfCanceled()
        val file = File(requireNotNull(context).filesDir, "workspace-provider-original.txt")
        if (!file.exists()) file.writeText(CONTENT, Charsets.UTF_8)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun documents(ids: List<String>, projection: Array<out String>?): Cursor {
        val columns = projection?.toList()?.toTypedArray() ?: arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        return MatrixCursor(columns).apply {
            for (id in ids) {
                if (id !in listOf("root", "notes", "note")) throw FileNotFoundException("Unknown fixture document")
                addRow(columns.map { column ->
                    when (column) {
                        Document.COLUMN_DOCUMENT_ID -> id
                        Document.COLUMN_DISPLAY_NAME -> when (id) { "root" -> TITLE; "notes" -> "Notes"; else -> "hello.txt" }
                        Document.COLUMN_MIME_TYPE -> if (id == "note") "text/plain" else Document.MIME_TYPE_DIR
                        Document.COLUMN_FLAGS -> 0
                        Document.COLUMN_SIZE -> if (id == "note") CONTENT.toByteArray(Charsets.UTF_8).size.toLong() else null
                        Document.COLUMN_LAST_MODIFIED -> 1_700_000_000_000L
                        else -> null
                    }
                }.toTypedArray<Any?>())
            }
        }
    }

    companion object {
        const val AUTHORITY = "com.mobilefork.hermesagent.test.workspacefixture"
        const val TITLE = "Agent Test Vault"
        const val CONTENT = "Workspace regression: original provider document.\n"
    }
}
