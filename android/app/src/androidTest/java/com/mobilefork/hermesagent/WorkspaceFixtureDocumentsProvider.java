package com.mobilefork.hermesagent;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Read-only test-APK provider exercised through the actual system picker.
 * Its independent provider process cannot depend on Kotlin classes deduplicated
 * into the target app APK, so this fixture uses only Android and Java APIs.
 */
public final class WorkspaceFixtureDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.mobilefork.hermesagent.test.workspacefixture";
    public static final String TITLE = "Agent Test Vault";
    public static final String CONTENT = "Workspace regression: original provider document.\n";

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES
        };
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case Root.COLUMN_ROOT_ID: row[i] = "workspace-regression"; break;
                case Root.COLUMN_TITLE: row[i] = TITLE; break;
                case Root.COLUMN_DOCUMENT_ID: row[i] = "root"; break;
                case Root.COLUMN_FLAGS: row[i] = Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD; break;
                case Root.COLUMN_MIME_TYPES: row[i] = "*/*"; break;
                case Root.COLUMN_AVAILABLE_BYTES: row[i] = 1_000_000L; break;
                default: row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        return documents(new String[] { documentId }, projection);
    }

    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder) throws FileNotFoundException {
        if ("root".equals(parentId)) return documents(new String[] { "notes" }, projection);
        if ("notes".equals(parentId)) return documents(new String[] { "note" }, projection);
        throw new FileNotFoundException("Not a fixture folder");
    }

    @Override public boolean isChildDocument(String parentId, String id) {
        return ("root".equals(parentId) && ("notes".equals(id) || "note".equals(id))) ||
            ("notes".equals(parentId) && "note".equals(id));
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!"note".equals(id) || !"r".equals(mode)) throw new FileNotFoundException("Fixture is read-only");
        if (signal != null) signal.throwIfCanceled();
        if (getContext() == null) throw new FileNotFoundException("Provider context unavailable");
        File file = new File(getContext().getFilesDir(), "workspace-provider-original.txt");
        if (!file.exists()) {
            try (FileOutputStream stream = new FileOutputStream(file)) {
                stream.write(CONTENT.getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) {
                FileNotFoundException wrapped = new FileNotFoundException(error.getMessage());
                wrapped.initCause(error);
                throw wrapped;
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private Cursor documents(String[] ids, String[] projection) throws FileNotFoundException {
        String[] columns = projection != null ? projection : new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
        };
        MatrixCursor cursor = new MatrixCursor(columns);
        for (String id : ids) {
            if (!"root".equals(id) && !"notes".equals(id) && !"note".equals(id)) throw new FileNotFoundException("Unknown fixture");
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                switch (columns[i]) {
                    case Document.COLUMN_DOCUMENT_ID: row[i] = id; break;
                    case Document.COLUMN_DISPLAY_NAME: row[i] = "root".equals(id) ? TITLE : "notes".equals(id) ? "Notes" : "hello.txt"; break;
                    case Document.COLUMN_MIME_TYPE: row[i] = "note".equals(id) ? "text/plain" : Document.MIME_TYPE_DIR; break;
                    case Document.COLUMN_FLAGS: row[i] = 0; break;
                    case Document.COLUMN_SIZE: row[i] = "note".equals(id) ? (long) CONTENT.getBytes(StandardCharsets.UTF_8).length : null; break;
                    case Document.COLUMN_LAST_MODIFIED: row[i] = 1_700_000_000_000L; break;
                    default: row[i] = null;
                }
            }
            cursor.addRow(row);
        }
        return cursor;
    }
}
