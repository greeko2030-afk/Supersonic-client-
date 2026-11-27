package net.kdt.pojavlaunch.supersonic;

import android.content.SharedPreferences;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;

/**
 * The persistent cache of translated shaders.
 *
 * Mesa (Zink) compiles every GLSL shader of the game, its mods and its shader packs to SPIR-V, and the Vulkan driver
 * turns that into GPU code. Mesa stores the result in this folder under a key made from the shader itself:
 * <ul>
 *   <li>the first launch translates the shaders as the game asks for them, and fills the cache;</li>
 *   <li>later launches load them from the folder instead of translating again;</li>
 *   <li>after a mod update only shaders whose text changed get a new key and are translated again, the rest stay valid.</li>
 * </ul>
 * The folder is inside the app's private data, so it is not visible in a file manager, and neither Android's
 * "Clear cache" nor this launcher's "Clean junk" touches it. Only the user can clear it, from Settings.
 */
public final class ShaderCache {
    private ShaderCache() {}

    /** Key of the size limit list preference. */
    public static final String PREF_LIMIT = "supersonic_shader_cache_limit";
    public static final String DEFAULT_LIMIT = "512M";
    private static final String[] LIMITS = {"512M", "1G", "2G", "4G"};

    /** Same folder that the engine has always used, so an existing cache keeps working. */
    static File dir() {
        return new File(new File(Tools.DIR_DATA, "supersonic_engine"), "shader_cache");
    }

    /** Value for MESA_SHADER_CACHE_MAX_SIZE. Mesa removes the oldest entries above it. */
    public static String limit() {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null) return DEFAULT_LIMIT;
        String value;
        try {
            value = prefs.getString(PREF_LIMIT, DEFAULT_LIMIT);
        } catch (ClassCastException e) {
            return DEFAULT_LIMIT;
        }
        for (String limit : LIMITS) if (limit.equals(value)) return limit;
        return DEFAULT_LIMIT;
    }

    /** What is stored now. */
    public static final class Stats {
        public final long bytes;
        public final int files;

        Stats(long bytes, int files) {
            this.bytes = bytes;
            this.files = files;
        }
    }

    public static Stats stats() {
        long[] bytes = {0};
        int[] files = {0};
        walk(dir(), bytes, files);
        return new Stats(bytes[0], files[0]);
    }

    private static void walk(File file, long[] bytes, int[] files) {
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                walk(child, bytes, files);
            } else {
                bytes[0] += child.length();
                files[0]++;
            }
        }
    }

    /** Removes everything in the cache (the folder stays). @return true if nothing is left */
    public static boolean clear() {
        File[] children = dir().listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        return stats().files == 0;
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** One line for the settings screen and for SS Agent. */
    public static String summary() {
        Stats stats = stats();
        String limit = limit().replace("M", " MB").replace("G", " GB");
        if (stats.files == 0) {
            return "Empty. It fills while you play; later launches reuse it instead of translating again. Limit " + limit + ". Tap to clear.";
        }
        return SupersonicUi.formatBytes(stats.bytes) + " in " + stats.files + " files, kept hidden inside the app. Limit " + limit
                + ". Tap to clear.";
    }
}
