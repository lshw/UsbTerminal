package de.kai_morich.simple_usb_terminal;

import android.content.Context;

import java.io.File;

final class LogFiles {

    private LogFiles() {
    }

    static File getLogsDir(Context context) {
        File baseDir = context.getExternalFilesDir("logs");
        if (baseDir == null) {
            baseDir = new File(context.getFilesDir(), "logs");
        }
        if (baseDir.exists() || baseDir.mkdirs()) {
            return baseDir;
        }
        return null;
    }
}
