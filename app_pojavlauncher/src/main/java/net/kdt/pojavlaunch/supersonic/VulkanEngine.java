package net.kdt.pojavlaunch.supersonic;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The Supersonic graphics engine.
 *
 * Minecraft, its mods and its shader packs speak OpenGL. On a device with a modern Vulkan driver the
 * whole OpenGL stream is translated to Vulkan by Mesa Zink (the renderer "vulkan_zink"):
 * every GL call becomes Vulkan calls and every GLSL shader (vanilla, mods, Iris and OptiFine shader packs)
 * is compiled by Mesa to SPIR-V. Zink uses the core features of the Vulkan version that the driver reports,
 * so a Vulkan 1.2 device runs on the 1.2 path, a 1.3 device on the 1.3 path and a 1.4 device on the 1.4 path.
 *
 * The engine mode decides what happens:
 * <ul>
 *   <li>Manual (the default): nothing is detected or changed. The renderer that the user selected in
 *       Settings > Video is used.</li>
 *   <li>Auto (opt-in): no Vulkan, Vulkan 1.0 or Vulkan 1.1 stays on OpenGL, and Vulkan 1.2, 1.3 and 1.4
 *       (or newer) runs on Vulkan through Zink.</li>
 * </ul>
 * It also keeps a persistent shader cache (so a GLSL shader is compiled to SPIR-V only once) and a crash guard:
 * when the game did not start on Vulkan, the next launches go back to OpenGL.
 */
public final class VulkanEngine {
    private VulkanEngine() {}

    /** Key of the "Graphics engine" list preference. */
    public static final String PREF_MODE = "supersonic_graphics_engine";
    /** The default. The engine changes nothing: the renderer that the user selected is used. */
    public static final String MODE_MANUAL = "manual";
    /** Vulkan 1.2 or newer uses Vulkan, everything else keeps the renderer that the user selected. */
    public static final String MODE_AUTO = "auto";

    /** The renderer that translates OpenGL to Vulkan (Mesa Zink). */
    public static final String VULKAN_RENDERER = "vulkan_zink";

    /** Auto uses Vulkan from this minor version on (Vulkan 1.2). */
    private static final int MIN_AUTO_MINOR = 2;
    /** A device that reports a newer Vulkan than 1.4 runs on the 1.4 tier. */
    private static final int MAX_TIER_MINOR = 4;

    /** Maximum size of the persistent shader cache. Mesa removes the oldest entries above it. */
    private static final String SHADER_CACHE_MAX_SIZE = "512M";
    /**
     * The game counts as started when Minecraft logs its OpenGL renderer. Versions that do not log it
     * count as started after this time alive.
     */
    private static final long STABLE_FALLBACK_MS = 180_000L;

    private static final String LOG_STABLE_LINE = "OpenGL Renderer:";

    // ------------------------------------------------------------------ device detection

    /** The Vulkan support of this device. */
    public static final class Device {
        static final Device NONE = new Device(false, 0, 0);

        public final boolean hasVulkan;
        /** The highest Vulkan API version that the device reports. 0.0 when there is no Vulkan. */
        public final int major;
        public final int minor;

        Device(boolean hasVulkan, int major, int minor) {
            this.hasVulkan = hasVulkan;
            this.major = major;
            this.minor = minor;
        }

        public String label() {
            return hasVulkan ? "Vulkan " + major + "." + minor : "no Vulkan";
        }
    }

    private static Device sDevice;
    private static volatile int sLastTier = 0;
    private static volatile boolean sStable = false;

    /** VK_MAKE_API_VERSION(0, major, minor, 0). This is how Android encodes the Vulkan version of a device. */
    private static int apiVersion(int major, int minor) {
        return (major << 22) | (minor << 12);
    }

    /**
     * Reads the highest Vulkan version that the device reports.
     * hasSystemFeature(name, version) is true when the device has at least that version,
     * so the versions are tried from the newest to the oldest.
     */
    public static synchronized Device detect(Context context) {
        if (sDevice != null) return sDevice;
        sDevice = Device.NONE;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return sDevice;

        PackageManager pm = context.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
                || !pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)) {
            return sDevice;
        }
        for (int minor = 9; minor >= 0; minor--) {
            if (pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, apiVersion(1, minor))) {
                sDevice = new Device(true, 1, minor);
                break;
            }
        }
        return sDevice;
    }

    /**
     * @return 12, 13 or 14 for the Vulkan 1.2, 1.3 and 1.4 tiers (a newer Vulkan counts as 1.4),
     * or 0 when the device is below Vulkan 1.2
     */
    public static int tierOf(Device device) {
        if (!device.hasVulkan) return 0;
        if (device.major > 1) return 10 + MAX_TIER_MINOR;
        if (device.minor < MIN_AUTO_MINOR) return 0;
        return 10 + Math.min(device.minor, MAX_TIER_MINOR);
    }

    public static String tierName(int tier) {
        return tier == 0 ? "OpenGL" : "Vulkan 1." + (tier - 10);
    }

    // ------------------------------------------------------------------ decision

    /** What the engine decided for a launch. */
    public static final class Decision {
        /** True when the game must run on Vulkan (Zink). False: the engine changes nothing. */
        public final boolean useVulkan;
        /** 12, 13, 14, or 0 when the device is below Vulkan 1.2. */
        public final int tier;
        /** Why, in plain words. */
        public final String reason;
        /** True when Vulkan is off because it did not start the last time. */
        public final boolean blockedByGuard;
        /** True in the manual mode: the engine does not decide anything. */
        public final boolean manual;

        Decision(boolean useVulkan, int tier, String reason, boolean blockedByGuard, boolean manual) {
            this.useVulkan = useVulkan;
            this.tier = tier;
            this.reason = reason;
            this.blockedByGuard = blockedByGuard;
            this.manual = manual;
        }

        /** Short name for the screen, for example "Vulkan 1.3" or "OpenGL". */
        public String shortLabel() {
            if (manual) return "Manual";
            if (!useVulkan) return "OpenGL";
            return tier == 0 ? "Vulkan" : tierName(tier);
        }

        /** One line for the game log. */
        public String summary() {
            return shortLabel() + (useVulkan ? " (Zink)" : "") + ": " + reason;
        }
    }

    /**
     * @return the engine mode. Only an explicit "auto" turns the automatic engine on: a missing value, and the
     * "opengl" and "vulkan" values of earlier versions, all mean manual.
     */
    public static String mode() {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null) return MODE_MANUAL;
        try {
            return MODE_AUTO.equals(prefs.getString(PREF_MODE, MODE_MANUAL)) ? MODE_AUTO : MODE_MANUAL;
        } catch (ClassCastException e) {
            return MODE_MANUAL;
        }
    }

    /**
     * Decides for a launch. Call it right before the game starts: it also reads the crash guard.
     *
     * @param profileRenderer the renderer that the instance sets for itself, or null
     */
    public static Decision decide(Context context, @Nullable String profileRenderer) {
        return evaluate(context, profileRenderer, true);
    }

    /** The same decision for showing it on a screen. It does not touch the crash guard. */
    public static Decision preview(Context context, @Nullable String profileRenderer) {
        return evaluate(context, profileRenderer, false);
    }

    private static Decision evaluate(Context context, @Nullable String profileRenderer, boolean launching) {
        String mode = mode();
        if (MODE_MANUAL.equals(mode)) {
            // No detection and no change. The renderer that the user selected is used.
            if (launching) {
                // The user chose manual: markers of an earlier Auto launch are not valid any more,
                // so Auto gets a fresh start when the user selects it again
                attemptFile().delete();
                blockedFile().delete();
            }
            return new Decision(false, 0, "the renderer that is selected in the settings is used", false, true);
        }

        Device device = detect(context);
        int tier = tierOf(device);
        if (launching) refreshGuard(mode);

        if (!zinkAvailable(context, launching, device)) {
            return keep(tier, device.hasVulkan
                    ? "the Vulkan renderer is not available on this device"
                    : "this device has no Vulkan");
        }
        // Auto
        if (profileRenderer != null && !profileRenderer.isEmpty()) {
            return keep(tier, "the instance uses its own renderer (" + profileRenderer + ")");
        }
        if (tier == 0) {
            return keep(tier, device.label() + " is older than Vulkan 1.2, so the selected renderer is used");
        }
        if (isBlocked(mode)) {
            return new Decision(false, tier, "the Vulkan engine did not start last time, so the selected renderer is used. "
                    + "Change the graphics engine in Settings > Video to try again", true, false);
        }
        return vulkan(tier, device.label() + " detected, OpenGL is translated to Vulkan");
    }

    private static Decision keep(int tier, String reason) {
        return new Decision(false, tier, reason, false, false);
    }

    private static Decision vulkan(int tier, String reason) {
        sLastTier = tier;
        return new Decision(true, tier, reason, false, false);
    }

    private static boolean zinkAvailable(Context context, boolean launching, Device device) {
        if (!device.hasVulkan) return false;
        // The full check also probes OpenGL ES, which a screen should not wait for
        if (launching) return Tools.checkRendererCompatible(context, VULKAN_RENDERER);
        return !(Architecture.is32BitsDevice() && Architecture.isx86Device());
    }

    /** Text for the settings screen: what this device has and what the selected mode does with it. */
    public static String settingsSummary(Context context, String mode) {
        if (!MODE_AUTO.equals(mode)) {
            return "Manual: nothing is detected or changed. The renderer that you select in Settings > Video is used.";
        }
        Device device = detect(context);
        int tier = tierOf(device);
        String found = device.hasVulkan ? "This device has " + device.label() + ". " : "This device has no Vulkan. ";
        if (tier == 0) {
            return found + "Auto: the selected renderer is used, Vulkan 1.2 or newer is needed.";
        }
        return found + "Auto: the whole game runs on " + tierName(tier)
                + " through Zink. Shaders are compiled to SPIR-V once and cached.";
    }

    // ------------------------------------------------------------------ environment

    /**
     * Environment variables for the Zink renderer: a persistent shader cache.
     * Mesa stores the SPIR-V that it compiles from GLSL, and the Vulkan pipelines, in this cache.
     * With it a shader is translated once and the next launches load it from disk.
     * The cache is outside of the app cache folder, so Android and the "Clear cache" button do not remove it.
     */
    public static Map<String, String> zinkEnvironment() {
        Map<String, String> env = new HashMap<>();
        File cache = new File(guardDir(), "shader_cache");
        if (cache.isDirectory() || cache.mkdirs()) {
            // Mesa 22.1 renamed MESA_GLSL_CACHE_* to MESA_SHADER_CACHE_*. The old name is deprecated,
            // so the new one is required for the cache to work with a recent Mesa.
            env.put("MESA_SHADER_CACHE_DIR", cache.getAbsolutePath());
            env.put("MESA_GLSL_CACHE_DIR", cache.getAbsolutePath());
            env.put("MESA_SHADER_CACHE_MAX_SIZE", SHADER_CACHE_MAX_SIZE);
        }
        env.putAll(tuningFor(sLastTier));
        return env;
    }

    /**
     * Settings that are added for one Vulkan tier. It is empty on purpose: Zink already picks
     * the core features of the Vulkan version that the driver reports. Add a flag here only after it was
     * tested on a real device of that tier, because a wrong Zink flag can break rendering.
     */
    private static Map<String, String> tuningFor(int tier) {
        return new HashMap<>();
    }

    // ------------------------------------------------------------------ crash guard

    private static File guardDir() {
        return new File(Tools.DIR_DATA, "supersonic_engine");
    }

    private static File attemptFile() {
        return new File(guardDir(), "vulkan_attempt");
    }

    private static File blockedFile() {
        return new File(guardDir(), "vulkan_blocked");
    }

    /**
     * Call right before the game starts on Vulkan. It writes a marker that is removed when the game
     * reached the point where Mesa Zink has rendered something. If the game dies before that,
     * the marker is still there at the next launch and Vulkan is switched off for Auto.
     * Plain files are used (and not preferences) because the launcher and the game run in different processes.
     */
    public static void armGuard() {
        sStable = false;
        writeText(attemptFile(), mode());

        // Minecraft logs "OpenGL Renderer: ..." once its context exists, which is the first moment
        // that Zink has really translated something.
        Logger.setLogListener(text -> {
            if (sStable || text == null || !text.contains(LOG_STABLE_LINE)) return;
            markStable();
            // Stop the callback, it is expensive for the log code. This is done on another thread
            // and not inside the callback itself.
            Thread stop = new Thread(() -> Logger.setLogListener(null), "supersonic-engine-guard-stop");
            stop.setDaemon(true);
            stop.start();
        });

        // Versions that do not log the renderer count as started after some time
        Thread timer = new Thread(() -> {
            try {
                Thread.sleep(STABLE_FALLBACK_MS);
            } catch (InterruptedException e) {
                return;
            }
            markStable();
        }, "supersonic-engine-guard");
        timer.setDaemon(true);
        timer.start();
    }

    /** The game started on Vulkan: remove the marker and any block. */
    public static void markStable() {
        sStable = true;
        attemptFile().delete();
        blockedFile().delete();
    }

    /**
     * An attempt marker from an earlier launch means that the game died before it was stable:
     * block Vulkan for the mode that was used. The block ends when the user changes the mode.
     */
    private static void refreshGuard(String mode) {
        File attempt = attemptFile();
        File blocked = blockedFile();
        if (attempt.exists()) {
            writeText(blocked, readText(attempt));
            attempt.delete();
        }
        if (blocked.exists() && !readText(blocked).equals(mode)) blocked.delete();
    }

    private static boolean isBlocked(String mode) {
        File blocked = blockedFile();
        return blocked.exists() && readText(blocked).equals(mode);
    }

    private static String readText(File file) {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeText(File file, String text) {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The guard is best effort. Without the marker the game simply starts as usual.
        }
    }
}
