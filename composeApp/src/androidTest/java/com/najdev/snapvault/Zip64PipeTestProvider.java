package com.najdev.snapvault;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Serves {@link Zip64Fixture} (an archive with a &gt;4 GiB entry) through a real OS pipe, so a reader
 * can neither seek it nor cache it. Test-APK process: self-contained Java, no target Kotlin runtime.
 */
public final class Zip64PipeTestProvider extends ContentProvider {
    public static final String HUGE = "huge-zip64.zip";

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/zip"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection == null
                ? new String[] {Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (Document.COLUMN_DISPLAY_NAME.equals(columns[i])) row[i] = uri.getLastPathSegment();
            else if (Document.COLUMN_MIME_TYPE.equals(columns[i])) row[i] = "application/zip";
            else if (Document.COLUMN_FLAGS.equals(columns[i])) row[i] = 0;
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!HUGE.equals(uri.getLastPathSegment())) throw new FileNotFoundException(uri.toString());
        return openPipeHelper(uri, "application/zip", null, null, (output, ignoredUri, type, opts, ignored) -> {
            try (BufferedOutputStream out = new BufferedOutputStream(
                    new ParcelFileDescriptor.AutoCloseOutputStream(output), 64 * 1024)) {
                Zip64Fixture.write(out);
            } catch (IOException expectedEarlyClose) {
                // The reader closing its end (cancellation, probing) surfaces here.
            }
        });
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
