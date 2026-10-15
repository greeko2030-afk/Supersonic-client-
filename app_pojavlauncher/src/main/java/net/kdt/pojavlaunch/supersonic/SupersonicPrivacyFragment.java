package net.kdt.pojavlaunch.supersonic;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.screens.LauncherPreferenceFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * "Privacy" tab of the Supersonic settings.
 * It can clear the launcher cache and delete game logs and crash reports.
 * Worlds, mods, accounts and settings are never touched.
 */
public class SupersonicPrivacyFragment extends LauncherPreferenceFragment {
    public static final String TAG = "SupersonicPrivacyFragment";

    private Preference mCachePreference;
    private Preference mLogsPreference;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.pref_supersonic_privacy);

        mCachePreference = requirePreference("privacy_clear_cache");
        mLogsPreference = requirePreference("privacy_delete_logs");

        mCachePreference.setOnPreferenceClickListener(preference -> {
            confirm(mCachePreference.getTitle(), "Delete the launcher cache? Downloaded icons and temporary files will be removed.",
                    this::clearCache);
            return true;
        });
        mLogsPreference.setOnPreferenceClickListener(preference -> {
            confirm(mLogsPreference.getTitle(), "Delete all logs and crash reports? This cannot be undone.",
                    this::deleteLogs);
            return true;
        });

        refreshSizes();
    }

    private void confirm(CharSequence title, String message, Runnable action) {
        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> action.run())
                .show();
    }

    // ------------------------------------------------------------------ actions

    private void clearCache() {
        PojavApplication.sExecutorService.execute(() -> {
            long before = cacheSize();
            File cache = Tools.DIR_CACHE;
            if (cache != null) {
                File[] children = cache.listFiles();
                if (children != null) {
                    for (File child : children) FolderManagerFragment.deleteRecursively(child);
                }
            }
            long freed = Math.max(0, before - cacheSize());
            finish("Freed " + SupersonicUi.formatBytes(freed));
        });
    }

    private void deleteLogs() {
        PojavApplication.sExecutorService.execute(() -> {
            List<File> files = logFiles();
            long before = sizeOf(files);
            for (File file : files) FolderManagerFragment.deleteRecursively(file);
            long freed = Math.max(0, before - sizeOf(files));
            finish("Freed " + SupersonicUi.formatBytes(freed));
        });
    }

    /** Shows the result and updates the sizes, on the UI thread and only while this tab is still shown. */
    private void finish(String message) {
        Tools.runOnUiThread(() -> {
            if (!isAdded()) return;
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            refreshSizes();
        });
    }

    /** Puts the real size of the cache and of the logs into the summary of each button. */
    private void refreshSizes() {
        PojavApplication.sExecutorService.execute(() -> {
            final long cache = cacheSize();
            final long logs = sizeOf(logFiles());
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                mCachePreference.setSummary(SupersonicUi.formatBytes(cache) + " used");
                mLogsPreference.setSummary(SupersonicUi.formatBytes(logs) + " used");
            });
        });
    }

    // ------------------------------------------------------------------ files

    private static long cacheSize() {
        File cache = Tools.DIR_CACHE;
        return cache == null ? 0 : FolderManagerFragment.sizeOf(cache);
    }

    private static long sizeOf(List<File> files) {
        long total = 0;
        for (File file : files) total += FolderManagerFragment.sizeOf(file);
        return total;
    }

    /** Game logs and crash reports of the launcher folder and of the selected instance. */
    private static List<File> logFiles() {
        List<File> files = new ArrayList<>();
        files.add(new File(Tools.DIR_GAME_HOME, "latestlog.txt"));
        files.add(new File(Tools.DIR_GAME_HOME, "latestlog.old.txt"));
        addLogFolders(files, Tools.DIR_GAME_NEW == null ? null : new File(Tools.DIR_GAME_NEW));
        SupersonicProfile profile = SupersonicProfile.current();
        if (profile != null) addLogFolders(files, profile.gameDir);
        return files;
    }

    private static void addLogFolders(List<File> files, @Nullable File gameDir) {
        if (gameDir == null) return;
        File crashReports = new File(gameDir, "crash-reports");
        File logs = new File(gameDir, "logs");
        if (!files.contains(crashReports)) files.add(crashReports);
        if (!files.contains(logs)) files.add(logs);
    }
}
