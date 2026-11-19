package net.kdt.pojavlaunch.supersonic;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;
import android.widget.Toast;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.FabricVersion;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.tasks.MinecraftDownloader;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gets a playable "Fabric 1.21" instance ready without the user doing anything:
 * <ol>
 *   <li>creates the instance (Fabric loader for Minecraft 1.21) and selects it if the user has not picked another;</li>
 *   <li>downloads its game files once, in the background, on an unmetered network (Wi-Fi);</li>
 *   <li>after that, Play only has to start the game: the files stay in the launcher's game folder, also across updates.</li>
 * </ol>
 * The game files themselves (Minecraft's client, libraries and assets) are not packed into the app: Minecraft's
 * licence does not allow passing them on, so they always come straight from Mojang's servers, once.
 *
 * Each step is remembered, so nothing is repeated and a deleted instance is not created again.
 */
public final class DefaultInstance {
    private DefaultInstance() {}

    static final String GAME_VERSION = "1.21";

    private static final String PREF_CREATED = "ss_default_instance_created";
    private static final String PREF_KEY = "ss_default_instance_key";
    private static final String PREF_READY = "ss_default_instance_ready";
    private static final String PREF_METERED_TOLD = "ss_default_instance_metered_told";
    private static final String TAG = "SSDefaultInstance";

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    /** True while the setup runs in the background. */
    static boolean isRunning() {
        return RUNNING.get();
    }

    /** Call on every launcher start. It returns at once when there is nothing left to do. */
    public static void ensure(final Activity activity) {
        final SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null || prefs.getBoolean(PREF_READY, false)) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        PojavApplication.sExecutorService.execute(() -> {
            try {
                setUp(activity, prefs);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Default instance setup failed, it will be tried again next time", e);
            } finally {
                RUNNING.set(false);
            }
        });
    }

    private static void setUp(Activity activity, SharedPreferences prefs) throws IOException {
        if (!isOnline(activity)) return; // tried again at the next start

        LauncherProfiles.load();
        Map<String, MinecraftProfile> profiles = LauncherProfiles.mainProfileJson.profiles;

        String key = prefs.getString(PREF_KEY, "");
        MinecraftProfile profile = key.isEmpty() ? null : profiles.get(key);
        if (profile == null && prefs.getBoolean(PREF_CREATED, false)) {
            // The user deleted the instance on purpose: leave it that way.
            prefs.edit().putBoolean(PREF_READY, true).apply();
            return;
        }
        if (profile == null) {
            key = findExistingKey(profiles);
            profile = key == null ? null : profiles.get(key);
        }
        if (profile == null) {
            key = createInstance(prefs);
            if (key == null) return;
            profile = LauncherProfiles.mainProfileJson.profiles.get(key);
            if (profile == null) return;
        }
        prefs.edit().putString(PREF_KEY, key).putBoolean(PREF_CREATED, true).apply();

        downloadGameFiles(activity, prefs, profile.lastVersionId);
    }

    /** Fabric loader for 1.21: version file + profile. @return the profile key, or null if Fabric's list is not reachable */
    private static String createInstance(SharedPreferences prefs) throws IOException {
        FabricVersion version = pickLoaderVersion(FabriclikeUtils.FABRIC_UTILS.downloadLoaderVersions(GAME_VERSION));
        if (version == null) return null;

        String json = DownloadUtils.downloadString(
                FabriclikeUtils.FABRIC_UTILS.createJsonDownloadUrl(GAME_VERSION, version.version));
        String versionId = versionIdOf(json);
        if (versionId == null) return null;

        File versionDir = new File(Tools.DIR_HOME_VERSION, versionId);
        FileUtils.ensureDirectory(versionDir);
        Tools.write(new File(versionDir, versionId + ".json").getAbsolutePath(), json);

        LauncherProfiles.load();
        MinecraftProfile created = new MinecraftProfile();
        created.lastVersionId = versionId;
        created.name = "Fabric " + GAME_VERSION;
        created.icon = FabriclikeUtils.FABRIC_UTILS.getIconName();
        LauncherProfiles.insertMinecraftProfile(created);
        LauncherProfiles.write();

        String key = keyOf(LauncherProfiles.mainProfileJson.profiles, created);
        if (key != null) {
            // Only take over the selection if the user has not chosen an instance of their own.
            String selectedKey = prefs.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
            if (shouldSelect(LauncherProfiles.mainProfileJson.profiles.get(selectedKey))) {
                prefs.edit().putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key).apply();
            }
        }
        return key;
    }

    private static void downloadGameFiles(Activity activity, SharedPreferences prefs, String versionId) {
        if (versionId == null) return;
        if (isMetered(activity)) {
            if (!prefs.getBoolean(PREF_METERED_TOLD, false)) {
                prefs.edit().putBoolean(PREF_METERED_TOLD, true).apply();
                toast(activity, "Fabric " + GAME_VERSION + " will download its game files once you are on Wi-Fi.");
            }
            return;
        }
        toast(activity, "Setting up Fabric " + GAME_VERSION + " in the background. Play is ready when it finishes.");
        new MinecraftDownloader().start(activity, null, versionId, new AsyncMinecraftDownloader.DoneListener() {
            @Override
            public void onDownloadDone() {
                prefs.edit().putBoolean(PREF_READY, true).apply();
                toast(activity, "Fabric " + GAME_VERSION + " is ready. Tap Play!");
            }

            @Override
            public void onDownloadFailed(Throwable throwable) {
                Log.w(TAG, "Game file download failed, it will be tried again next time", throwable);
                toast(activity, "Fabric " + GAME_VERSION + " download stopped. It continues the next time the launcher starts.");
            }
        });
    }

    // ------------------------------------------------------------------ small decisions (unit tested)

    /** The newest stable loader; if none is marked stable, the newest. Fabric lists newest first. */
    static FabricVersion pickLoaderVersion(FabricVersion[] versions) {
        if (versions == null || versions.length == 0) return null;
        for (FabricVersion version : versions) {
            if (version != null && version.stable && version.version != null) return version;
        }
        return versions[0] != null && versions[0].version != null ? versions[0] : null;
    }

    /** The "id" of a Fabric version file, or null if the text is not one. */
    static String versionIdOf(String json) {
        try {
            String id = new JsonParser().parse(json).getAsJsonObject().get("id").getAsString();
            return id.isEmpty() || id.contains("/") || id.contains("\\") || id.contains("..") ? null : id;
        } catch (JsonParseException | IllegalStateException | NullPointerException | ClassCastException e) {
            return null;
        }
    }

    /** An existing Fabric 1.21 instance, so that none is created twice. */
    static String findExistingKey(Map<String, MinecraftProfile> profiles) {
        for (Map.Entry<String, MinecraftProfile> entry : profiles.entrySet()) {
            String id = entry.getValue() == null ? null : entry.getValue().lastVersionId;
            if (id != null && id.startsWith("fabric-loader-") && id.endsWith("-" + GAME_VERSION)) return entry.getKey();
        }
        return null;
    }

    static String keyOf(Map<String, MinecraftProfile> profiles, MinecraftProfile profile) {
        for (Map.Entry<String, MinecraftProfile> entry : profiles.entrySet()) {
            if (entry.getValue() == profile) return entry.getKey();
        }
        return null;
    }

    /** True if nothing was chosen yet, or the choice is one of the built-in "latest" entries. */
    static boolean shouldSelect(MinecraftProfile current) {
        return current == null || current.lastVersionId == null
                || MinecraftProfile.LATEST_RELEASE.equals(current.lastVersionId)
                || MinecraftProfile.LATEST_SNAPSHOT.equals(current.lastVersionId);
    }

    // ------------------------------------------------------------------ network

    private static boolean isOnline(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = manager == null ? null : manager.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    /** Mobile data (or a hotspot marked as metered): the big download waits for Wi-Fi. */
    private static boolean isMetered(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return manager == null || manager.isActiveNetworkMetered();
    }

    private static void toast(Activity activity, String text) {
        Tools.runOnUiThread(() -> Toast.makeText(activity, text, Toast.LENGTH_LONG).show());
    }
}
