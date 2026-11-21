package com.najdev.snapvault;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Test-APK process has no target Kotlin runtime; keep this provider self-contained Java. */
public final class PipeZipTestProvider extends ContentProvider {
    /** Documents served by the failure/cancellation fixtures; all are small and fully deterministic. */
    public static final String LARGE = "large.zip";
    public static final String TRUNCATED = "truncated.zip";
    public static final String FAULTY = "faulty.zip";
    public static final String STALLED = "stalled.zip";
    public static final int FIRST_SIZE = 4096;
    public static final int SECOND_SIZE = 1024 * 1024;
    public static final int THIRD_SIZE = 2048;
    /** Bytes of the second entry delivered before the truncation, the fault or the stall. */
    private static final int DELIVERED_OF_SECOND = 100_000;
    private static final long STALL_MILLIS = 3_000;

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
    /** Deterministic incompressible bytes, so the test can regenerate the expected content. */
    public static byte[] payload(int seed, int size) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static byte[] archive(int[] boundary) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("memories/first.jpg"));
            zip.write(payload(1, FIRST_SIZE));
            zip.closeEntry();
            zip.flush();
            boundary[0] = bytes.size();
            zip.putNextEntry(new ZipEntry("memories/second.jpg"));
            zip.write(payload(2, SECOND_SIZE));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("memories/third.jpg"));
            zip.write(payload(3, THIRD_SIZE));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (LARGE.equals(name) || TRUNCATED.equals(name) || STALLED.equals(name)) {
            return openPipeHelper(uri, "application/zip", null, null, (output, ignoredUri, type, opts, ignored) -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                    int[] boundary = new int[1];
                    byte[] zip = archive(boundary);
                    int cut = boundary[0] + DELIVERED_OF_SECOND;
                    if (TRUNCATED.equals(name)) {
                        // Provider process closes the pipe cleanly midway through an entry.
                        out.write(zip, 0, cut);
                        return;
                    }
                    if (STALLED.equals(name)) {
                        out.write(zip, 0, cut);
                        out.flush();
                        Thread.sleep(STALL_MILLIS);
                        out.write(zip, cut, zip.length - cut);
                        return;
                    }
                    out.write(zip);
                } catch (IOException | InterruptedException expectedEarlyClose) {
                    // The reader closing its end (cancellation, probing) surfaces here.
                }
            });
        }
        if (FAULTY.equals(name)) return faultyPipe();
        if (!"export.zip".equals(name)) throw new FileNotFoundException(name);
        return openPipeHelper(uri, "application/zip", null, null, (output, ignoredUri, type, opts, ignored) -> {
            try (ZipOutputStream zip = new ZipOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(output))) {
                zip.putNextEntry(new ZipEntry("memories/payload.bin"));
                byte[] buffer = new byte[8192];
                for (int i = 0; i < buffer.length; i++) buffer[i] = (byte) (i % 251);
                // Generate 16 MiB with a fixed buffer, without storing the archive anywhere.
                for (int i = 0; i < 2048; i++) zip.write(buffer);
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("memories_history.json"));
                zip.write("{\"Saved Media\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            } catch (IOException expectedEarlyClose) {
                // Seekability probing closes its reader before consuming the pipe.
            }
        });
    }

    /** A reliable pipe whose writer reports an error midway instead of closing cleanly. */
    private static ParcelFileDescriptor faultyPipe() throws FileNotFoundException {
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            new Thread(() -> {
                // Not closed here: the descriptor belongs to the ParcelFileDescriptor, closed below.
                FileOutputStream out = new FileOutputStream(pipe[1].getFileDescriptor());
                try {
                    int[] boundary = new int[1];
                    byte[] zip = archive(boundary);
                    out.write(zip, 0, boundary[0] + DELIVERED_OF_SECOND);
                    out.flush();
                    pipe[1].closeWithError("simulated provider fault");
                } catch (IOException expectedEarlyClose) {
                    // Reader already gone.
                    try { pipe[1].close(); } catch (IOException ignored) { }
                }
            }, "faulty-zip-writer").start();
            return pipe[0];
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
