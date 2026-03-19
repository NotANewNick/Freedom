package freedom.app.helper;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogUtility {

    private final static String TAG = "KorgeniTAG";
    private final static boolean SHOULD_LOG_TO_FILE = true;

    public static void debugLog(String msg) {
        Log.d(TAG, msg);
        fileLog(msg);
    }

    public static void errorLog(String msg) {
        Log.e(TAG, msg);
        fileLog(msg);
    }

    public static void fileLog(String msg) {
        if (SHOULD_LOG_TO_FILE) {
            SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy_HH:mm:ss", Locale.getDefault());
            appendLogToFile("[" + sdf.format(new Date()) + "] = " + msg);
        }
    }

    private static void appendLogToFile(String text) {
        String sdcard = "/data/data/freedom.app/files";
        File logFile = new File(sdcard + "/Logs.txt");

        if (!logFile.exists()) {
            try {
                logFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
                return; // Return early if file creation fails
            }
        }

        // Use try-with-resources to automatically close BufferedWriter
        try (BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true))) {
            buf.append(text);
            buf.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public static long getFabricLogFilesSize(Context context) {
        long totalSize = 0;
        File logDir = new File(context.getFilesDir(), "fabric"); // Adjust the path based on your app's configuration
        if (logDir.exists() && logDir.isDirectory()) {
            for (File file : logDir.listFiles()) {
                totalSize += file.length();
            }
        }
        return totalSize;
    }

    public static String formatSize(long size) {
        String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        double sizeInUnit = (double) size;
        while (sizeInUnit >= 1024 && unitIndex < units.length - 1) {
            sizeInUnit /= 1024;
            unitIndex++;
        }
        return String.format("%.1f %s", sizeInUnit, units[unitIndex]);
    }
}
