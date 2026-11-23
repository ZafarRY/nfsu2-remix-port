package com.zafar.nfsu2remix;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.os.Process;
import java.io.File;
import java.util.List;

/** Only disposable graphics caches in this application's private cache directory. */
final class GraphicsCache {
    // Keep in sync with configurePipelineCache() in main.cpp. Epoch 2 excludes
    // caches from the previous DXVK preparation; those files remain untouched.
    static final String DIRECTORY = "dxvk-v2";

    static void prompt(Activity activity) {
        new AlertDialog.Builder(activity).setTitle("Reset graphics cache")
                .setMessage("The current game session will be closed. Shaders will be recompiled while you play; progress and settings are kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (dialog, which) -> reset(activity)).show();
    }

    private static void reset(Activity activity) {
        try {
            ActivityManager manager = (ActivityManager) activity.getSystemService(Activity.ACTIVITY_SERVICE);
            if (manager == null) throw new IllegalStateException("Failed to check the game process.");
            List<ActivityManager.RunningAppProcessInfo> running = manager.getRunningAppProcesses();
            if (running != null) for (ActivityManager.RunningAppProcessInfo process : running)
                if (process.uid == Process.myUid() && process.processName.equals(activity.getPackageName() + ":game"))
                    Process.killProcess(process.pid);
            File cache = new File(activity.getCacheDir(), DIRECTORY);
            if (cache.exists()) {
                File backup = new File(activity.getCacheDir(), DIRECTORY + "-reset-" + System.currentTimeMillis());
                if (!cache.isDirectory() || !cache.renameTo(backup))
                    throw new IllegalStateException("Failed to reset the graphics cache.");
            }
            new AlertDialog.Builder(activity).setTitle("Graphics cache ready")
                    .setMessage("The next launch will create a new cache. The old cache is backed up in the private app cache.")
                    .setPositiveButton("OK", null).show();
        } catch (RuntimeException error) {
            new AlertDialog.Builder(activity).setTitle("Graphics cache").setMessage(error.getMessage())
                    .setPositiveButton("OK", null).show();
        }
    }
}
