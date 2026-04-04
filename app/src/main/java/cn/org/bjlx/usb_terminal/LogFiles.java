package cn.org.bjlx.usb_terminal;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore.MediaColumns;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

final class LogFiles {
    private static final String PUBLIC_LOGS_SUBDIR = "UsbTerminal";
    private static final String PUBLIC_LOGS_DIR = Environment.DIRECTORY_DOWNLOADS + "/UsbTerminal";
    private static final String PUBLIC_LOGS_RELATIVE_PATH = PUBLIC_LOGS_DIR + "/";

    private LogFiles() {
    }

    static boolean usesPublicLogs() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    static File getExternalLogsDir(Context context) {
        File baseDir = context.getExternalFilesDir("logs");
        if (baseDir != null && (baseDir.exists() || baseDir.mkdirs())) {
            return baseDir;
        }
        return null;
    }

    static File getLogsDir(Context context) {
        File baseDir = getExternalLogsDir(context);
        if (baseDir == null) {
            baseDir = new File(context.getFilesDir(), "logs");
        }
        if (baseDir.exists() || baseDir.mkdirs()) {
            return baseDir;
        }
        return null;
    }

    static Uri createLogUri(Context context, String fileName) {
        if (!usesPublicLogs()) {
            return null;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, PUBLIC_LOGS_RELATIVE_PATH);
        return context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    }

    static boolean ensureLogsDirectoryExists(Context context) {
        if (usesPublicLogs()) {
            return ensurePublicLogsDirectoryExists(context);
        }
        return getLogsDir(context) != null;
    }

    static OutputStream openLogStream(Context context, Uri uri, boolean append) throws java.io.IOException {
        ContentResolver resolver = context.getContentResolver();
        OutputStream stream = resolver.openOutputStream(uri, append ? "wa" : "w");
        if (stream == null) {
            throw new java.io.IOException("open output stream failed");
        }
        return stream;
    }

    static void deleteLogUri(Context context, Uri uri) {
        if (uri != null) {
            context.getContentResolver().delete(uri, null, null);
        }
    }

    static String getLogLocation(Context context, String fileName) {
        if (usesPublicLogs()) {
            return Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_LOGS_SUBDIR + "/" + fileName;
        }
        File directory = getLogsDir(context);
        if (directory == null) {
            return fileName;
        }
        return new File(directory, fileName).getAbsolutePath();
    }

    static String getPublicLogsDisplayPath() {
        return Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_LOGS_SUBDIR;
    }

    static Uri getLatestLogUri(Context context) {
        if (usesPublicLogs()) {
            return getLatestPublicLogUri(context);
        }
        return null;
    }

    static Uri getLogsDirectoryUri() {
        String documentId = "primary:" + PUBLIC_LOGS_DIR.replaceFirst("^" + Environment.DIRECTORY_DOWNLOADS, "Download");
        Uri treeUri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", documentId);
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    }

    static Uri getLogsTreeUri() {
        String documentId = "primary:" + PUBLIC_LOGS_DIR.replaceFirst("^" + Environment.DIRECTORY_DOWNLOADS, "Download");
        return DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", documentId);
    }

    private static boolean ensurePublicLogsDirectoryExists(Context context) {
        Uri probeUri = createLogUri(context, ".probe");
        if (probeUri == null) {
            return false;
        }
        try (OutputStream stream = openLogStream(context, probeUri, false)) {
            stream.write(new byte[0]);
            return true;
        } catch (IOException ignored) {
            return false;
        } finally {
            deleteLogUri(context, probeUri);
        }
    }

    private static Uri getLatestPublicLogUri(Context context) {
        String[] projection = {MediaColumns._ID};
        String selection = MediaColumns.RELATIVE_PATH + "=? AND " + MediaColumns.DISPLAY_NAME + " LIKE ?";
        String[] selectionArgs = {PUBLIC_LOGS_RELATIVE_PATH, "%.log"};
        String sortOrder = MediaColumns.DATE_MODIFIED + " DESC, " + MediaColumns.DATE_ADDED + " DESC";
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            long id = cursor.getLong(0);
            return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(id));
        }
    }
}
