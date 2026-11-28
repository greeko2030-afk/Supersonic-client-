package net.kdt.pojavlaunch;

import android.app.ActivityManager;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automatic updater that uses GitHub Releases.
 *
 * How it works:
 *  1. checkInBackground() is called when the intro screen opens. It runs on a background thread.
 *  2. It reads the newest GitHub release. If that release was published AFTER the currently
 *     installed version was installed, its APK is downloaded with the system DownloadManager
 *     (the download keeps going even if the intro is already finished).
 *  3. When the download is finished, the APK is checked (same package name, not a downgrade)
 *     and installed with PackageInstaller.
 *
 * Android rules you cannot get around:
 *  - The user must allow "Install unknown apps" for this app once.
 *  - Android may show an "Update" confirmation button. On Android 12+ later updates can be
 *    silent when this app installed the previous version, but Android does not guarantee that.
 *  - The new APK must be signed with the same key and have the same package name.
 */
public final class AppUpdater {
    private static final String TAG = "AppUpdater";

    // ------------------------------------------------------------------ settings
    private static final String GITHUB_OWNER = "greeko2030-afk";
    private static final String GITHUB_REPO = "PojavLauncher";

    /** If a release has more than one APK, this file name is chosen first. */
    private static final String PREFERRED_APK_NAME = "Supersonic-Client-beta.apk";

    /** true: also use releases that are marked as "pre-release" on GitHub. */
    private static final boolean INCLUDE_PRERELEASES = true;

    /** true: download only on Wi-Fi (the APK is big, so this can save mobile data). */
    private static final boolean WIFI_ONLY = false;

    private static final int HTTP_TIMEOUT_MS = 10000;
    private static final long CHECK_THROTTLE_MS = 60 * 1000L; // do not call GitHub more than once a minute

    // ------------------------------------------------------------------ internals
    private static final String PREFS = "app_updater";
    private static final String K_LAST_CHECK = "last_check";
    private static final String K_INSTALLED_PUB = "installed_published_at"; // release we installed ourselves
    private static final String K_FAILED_PUB = "failed_published_at";       // release that can never be installed
    private static final String K_DL_ID = "download_id";
    private static final String K_DL_PUB = "download_published_at";
    private static final String K_DL_TAG = "download_tag";
    private static final String K_DL_DONE = "download_done";
    private static final String K_PENDING_PUB = "pending_published_at";
    private static final String K_PENDING_START = "pending_started_at";

    private static final String CHANNEL_ID = "app_update";
    private static final int NOTIFICATION_ID = 7421;

    private static final AtomicBoolean sRunning = new AtomicBoolean(false);
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private AppUpdater() { }

    private static final class ReleaseInfo {
        String tag;
        String apkUrl;
        long publishedAtMs;
    }

    // ================================================================== public API

    /** Starts the update check on a background thread. Safe to call many times. */
    public static void checkInBackground(Context context) {
        final Context app = context.getApplicationContext();
        if (!sRunning.compareAndSet(false, true)) return;
        Thread thread = new Thread(() -> {
            try {
                runCheck(app);
            } catch (Throwable t) {
                Log.e(TAG, "Update check failed", t);
            } finally {
                sRunning.set(false);
            }
        }, "AppUpdater");
        thread.setDaemon(true);
        thread.start();
    }

    /** Called by UpdateDownloadReceiver when the DownloadManager finishes a download. */
    static void onDownloadFinished(Context context, long downloadId) {
        SharedPreferences p = prefs(context);
        if (downloadId < 0 || downloadId != p.getLong(K_DL_ID, -1)) return; // not our download

        int status = getDownloadStatus(context, downloadId);
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            p.edit().putBoolean(K_DL_DONE, true).apply();
            long pub = p.getLong(K_DL_PUB, 0);
            Log.i(TAG, "Download finished, installing " + p.getString(K_DL_TAG, "?"));
            installDownloadedApk(context, p, pub);
        } else {
            Log.w(TAG, "Download failed, status=" + status);
            clearDownload(context, p);
        }
    }

    /** Called by UpdateInstallReceiver with the result from PackageInstaller. */
    static void onInstallStatus(Context context, Intent intent) {
        SharedPreferences p = prefs(context);
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(TAG, "Install status=" + status + " message=" + message);

        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = getConfirmIntent(intent);
                if (confirm != null) {
                    launchOrNotify(context, confirm, "Update ready",
                            "Tap to install the new version of Supersonic Client");
                }
                return; // keep the pending marker, the result comes later
            }
            case PackageInstaller.STATUS_SUCCESS:
                return; // the app is replaced, the new process resolves the marker
            case PackageInstaller.STATUS_FAILURE_ABORTED:
            case PackageInstaller.STATUS_FAILURE_STORAGE:
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                // The user cancelled or the phone is full. Try again on the next app start.
                p.edit().remove(K_PENDING_PUB).remove(K_PENDING_START).apply();
                showToast(context, "Update was not installed. It will be tried again next time.");
                return;
            default:
                // Signature mismatch, invalid APK, conflict... retrying will never work for this release.
                long pub = p.getLong(K_PENDING_PUB, p.getLong(K_DL_PUB, 0));
                p.edit().remove(K_PENDING_PUB).remove(K_PENDING_START).apply();
                markFailed(context, p, pub);
                showToast(context, "Update failed: " + message);
        }
    }

    // ================================================================== main flow

    private static void runCheck(Context ctx) {
        SharedPreferences p = prefs(ctx);

        resolvePendingInstall(ctx, p);
        long threshold = getInstalledThreshold(ctx, p);
        long failed = p.getLong(K_FAILED_PUB, 0);

        // 1) A newer APK is already downloaded: install it now (works without internet).
        long dlPub = p.getLong(K_DL_PUB, 0);
        File apk = getApkFile(ctx);
        if (p.getBoolean(K_DL_DONE, false) && dlPub > threshold && dlPub != failed
                && apk != null && apk.exists()) {
            installDownloadedApk(ctx, p, dlPub);
            return;
        }

        // 2) Ask GitHub, but not too often.
        long now = System.currentTimeMillis();
        if (now - p.getLong(K_LAST_CHECK, 0) < CHECK_THROTTLE_MS) return;
        if (!isOnline(ctx)) return;
        p.edit().putLong(K_LAST_CHECK, now).apply();

        ReleaseInfo release = fetchLatestRelease();
        if (release == null) return;
        Log.i(TAG, "Latest release: " + release.tag + " published=" + release.publishedAtMs
                + " installedThreshold=" + threshold);

        if (release.publishedAtMs <= threshold) return;   // already up to date
        if (release.publishedAtMs == failed) return;      // this release cannot be installed

        // 3) Do nothing if this release is already downloading or downloaded.
        if (dlPub == release.publishedAtMs
                && (p.getBoolean(K_DL_DONE, false) || isDownloadActive(ctx, p.getLong(K_DL_ID, -1)))) {
            return;
        }
        startDownload(ctx, p, release);
    }

    /**
     * Version check without version numbers: a release is "new" when it was published after the
     * installed app was installed/updated. After an update done by this class the GitHub publish
     * time of that release is stored, so a wrong phone clock cannot cause an update loop.
     */
    private static long getInstalledThreshold(Context ctx, SharedPreferences p) {
        long installedPub = p.getLong(K_INSTALLED_PUB, 0);
        long lastUpdate = 0;
        try {
            lastUpdate = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
        } catch (Exception ignored) { }
        return Math.max(installedPub, lastUpdate);
    }

    /** If an update was started earlier, check whether it really got installed. */
    private static void resolvePendingInstall(Context ctx, SharedPreferences p) {
        long pendingPub = p.getLong(K_PENDING_PUB, 0);
        if (pendingPub <= 0) return;
        long started = p.getLong(K_PENDING_START, 0);
        long lastUpdate = 0;
        try {
            lastUpdate = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
        } catch (Exception ignored) { }

        SharedPreferences.Editor e = p.edit();
        if (lastUpdate >= started) {
            Log.i(TAG, "Update installed successfully");
            e.putLong(K_INSTALLED_PUB, pendingPub);
            deleteApk(ctx);
            e.remove(K_DL_ID).remove(K_DL_PUB).remove(K_DL_TAG).remove(K_DL_DONE);
        }
        e.remove(K_PENDING_PUB).remove(K_PENDING_START).apply();
    }

    // ================================================================== GitHub

    private static ReleaseInfo fetchLatestRelease() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO
                    + "/releases?per_page=10");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "Supersonic-Client-Updater");
            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "GitHub answered with HTTP " + conn.getResponseCode());
                return null;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > 4 * 1024 * 1024) return null; // unexpectedly big answer
                }
            }

            JSONArray releases = new JSONArray(out.toString("UTF-8"));
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));

            // GitHub returns the newest release first.
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.getJSONObject(i);
                if (release.optBoolean("draft", false)) continue;
                if (release.optBoolean("prerelease", false) && !INCLUDE_PRERELEASES) continue;

                String apkUrl = pickApkUrl(release.optJSONArray("assets"));
                String published = release.optString("published_at", "");
                if (apkUrl == null || published.isEmpty()) continue;

                ReleaseInfo info = new ReleaseInfo();
                info.tag = release.optString("tag_name", "?");
                info.apkUrl = apkUrl;
                info.publishedAtMs = format.parse(published).getTime();
                return info;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not read GitHub releases", t);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static String pickApkUrl(JSONArray assets) {
        if (assets == null) return null;
        String fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String url = asset.optString("browser_download_url", "");
            if (!name.toLowerCase(Locale.ROOT).endsWith(".apk") || url.isEmpty()) continue;
            if (name.equals(PREFERRED_APK_NAME)) return url;
            if (fallback == null && !name.toLowerCase(Locale.ROOT).contains("noruntime")) fallback = url;
        }
        return fallback;
    }

    // ================================================================== download

    private static void startDownload(Context ctx, SharedPreferences p, ReleaseInfo release) {
        File apk = getApkFile(ctx);
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        if (apk == null || dm == null) return;

        clearDownload(ctx, p); // remove an older download first

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.apkUrl));
        request.setTitle("Supersonic Client update");
        request.setDescription(release.tag);
        request.setMimeType("application/vnd.android.package-archive");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        request.setAllowedOverRoaming(false);
        if (WIFI_ONLY) request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI);
        request.setDestinationUri(Uri.fromFile(apk));

        long id = dm.enqueue(request);
        p.edit()
                .putLong(K_DL_ID, id)
                .putLong(K_DL_PUB, release.publishedAtMs)
                .putString(K_DL_TAG, release.tag)
                .putBoolean(K_DL_DONE, false)
                .apply();
        Log.i(TAG, "Started download of " + release.tag + " (id " + id + ")");
    }

    private static int getDownloadStatus(Context ctx, long id) {
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return DownloadManager.STATUS_FAILED;
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (c != null && c.moveToFirst()) {
                return c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            }
        } catch (Throwable ignored) { }
        return DownloadManager.STATUS_FAILED;
    }

    private static boolean isDownloadActive(Context ctx, long id) {
        if (id < 0) return false;
        int status = getDownloadStatus(ctx, id);
        return status == DownloadManager.STATUS_PENDING
                || status == DownloadManager.STATUS_RUNNING
                || status == DownloadManager.STATUS_PAUSED;
    }

    private static void clearDownload(Context ctx, SharedPreferences p) {
        long oldId = p.getLong(K_DL_ID, -1);
        if (oldId >= 0) {
            try {
                DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) dm.remove(oldId);
            } catch (Throwable ignored) { }
        }
        deleteApk(ctx);
        p.edit().remove(K_DL_ID).remove(K_DL_PUB).remove(K_DL_TAG).remove(K_DL_DONE).apply();
    }

    private static File getApkFile(Context ctx) {
        File dir = ctx.getExternalFilesDir("updates");
        if (dir == null) return null;
        if (!dir.exists() && !dir.mkdirs()) return null;
        return new File(dir, "update.apk");
    }

    private static void deleteApk(Context ctx) {
        File apk = getApkFile(ctx);
        if (apk != null && apk.exists()) //noinspection ResultOfMethodCallIgnored
            apk.delete();
    }

    private static void markFailed(Context ctx, SharedPreferences p, long publishedAt) {
        p.edit().putLong(K_FAILED_PUB, publishedAt).apply();
        clearDownload(ctx, p);
    }

    // ================================================================== install

    private static synchronized void installDownloadedApk(Context ctx, SharedPreferences p, long publishedAt) {
        File apk = getApkFile(ctx);
        if (apk == null || !apk.exists()) {
            clearDownload(ctx, p);
            return;
        }
        PackageManager pm = ctx.getPackageManager();

        // 1) Check the APK before installing it.
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (archive == null) {
            Log.w(TAG, "Downloaded file is not a valid APK");
            clearDownload(ctx, p);
            return;
        }
        if (!ctx.getPackageName().equals(archive.packageName)) {
            Log.w(TAG, "APK package " + archive.packageName + " does not match " + ctx.getPackageName());
            markFailed(ctx, p, publishedAt);
            return;
        }
        try {
            PackageInfo current = pm.getPackageInfo(ctx.getPackageName(), 0);
            if (getVersionCode(archive) < getVersionCode(current)) {
                Log.w(TAG, "Release APK is older than the installed app (downgrade), skipping");
                markFailed(ctx, p, publishedAt);
                return;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }

        // 2) The user must allow "Install unknown apps" for this app.
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
            showToast(ctx, "Allow \"Install unknown apps\" to update Supersonic Client");
            launchOrNotify(ctx, settings, "Allow app updates",
                    "Tap to allow Supersonic Client to install its own updates");
            return;
        }

        // 3) Install with PackageInstaller.
        PackageInstaller installer = pm.getPackageInstaller();
        abandonOldSessions(installer);

        int sessionId = -1;
        try {
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(ctx.getPackageName());
            params.setSize(apk.length());
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            sessionId = installer.createSession(params);

            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite("update.apk", 0, apk.length())) {
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    session.fsync(out);
                }

                Intent resultIntent = new Intent(ctx, UpdateInstallReceiver.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent pending = PendingIntent.getBroadcast(ctx, sessionId, resultIntent, flags);

                p.edit()
                        .putLong(K_PENDING_PUB, publishedAt)
                        .putLong(K_PENDING_START, System.currentTimeMillis())
                        .apply();
                session.commit(pending.getIntentSender());
                Log.i(TAG, "Install session committed");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Could not install the update", t);
            p.edit().remove(K_PENDING_PUB).remove(K_PENDING_START).apply();
            if (sessionId != -1) {
                try { installer.abandonSession(sessionId); } catch (Throwable ignored) { }
            }
        }
    }

    private static void abandonOldSessions(PackageInstaller installer) {
        if (Build.VERSION.SDK_INT < 24) return;
        try {
            for (PackageInstaller.SessionInfo info : installer.getMySessions()) {
                installer.abandonSession(info.getSessionId());
            }
        } catch (Throwable ignored) { }
    }

    private static long getVersionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static Intent getConfirmIntent(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        return intent.getParcelableExtra(Intent.EXTRA_INTENT);
    }

    // ================================================================== helpers

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @SuppressWarnings("deprecation")
    private static boolean isOnline(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable t) {
            return true; // let the real request decide
        }
    }

    /** True when one of this app's screens is in front of the user. */
    private static boolean isAppInForeground() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    /**
     * Opens the screen right away when the app is in front.
     * Android blocks screens that start from the background, so then a notification is shown.
     */
    private static void launchOrNotify(Context ctx, Intent intent, String title, String text) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (isAppInForeground()) {
            try {
                ctx.startActivity(intent);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "Could not open the screen directly", t);
            }
        }
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT));
                builder = new Notification.Builder(ctx, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(ctx);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent content = PendingIntent.getActivity(ctx, NOTIFICATION_ID, intent, flags);
            builder.setSmallIcon(ctx.getApplicationInfo().icon)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(content)
                    .setAutoCancel(true);
            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (Throwable t) {
            Log.w(TAG, "Could not show the update notification", t);
        }
    }

    private static void showToast(final Context ctx, final String text) {
        sMainHandler.post(() -> Toast.makeText(ctx, text, Toast.LENGTH_LONG).show());
    }
}
