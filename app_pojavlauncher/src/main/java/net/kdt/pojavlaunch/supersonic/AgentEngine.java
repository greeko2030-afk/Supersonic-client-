package net.kdt.pojavlaunch.supersonic;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.StatFs;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "Supersonic Agent": a rule-based diagnostic and repair tool.
 * It reads real data from this phone (RAM, storage, logs, mods folder) and changes real settings.
 * It is not a chatbot and does not use any online AI service.
 */
public final class AgentEngine {
    private AgentEngine() {}

    public static final int INFO = 0, WARNING = 1, PROBLEM = 2;

    public static class Finding {
        public int severity;
        public String title, detail;
        /** null if there is nothing the Agent can fix automatically */
        public String fixId, fixLabel;
    }

    private static Finding finding(int severity, String title, String detail, String fixId, String fixLabel) {
        Finding f = new Finding();
        f.severity = severity;
        f.title = title;
        f.detail = detail;
        f.fixId = fixId;
        f.fixLabel = fixLabel;
        return f;
    }

    // ------------------------------------------------------------------ scan

    public static List<Finding> scan(Context context) {
        List<Finding> findings = new ArrayList<>();
        int totalMb = PerformanceModes.totalRamMb(context);
        int allocation = LauncherPreferences.PREF_RAM_ALLOCATION;

        // RAM
        if (totalMb > 0 && allocation > totalMb * 0.6) {
            findings.add(finding(WARNING, "Too much RAM given to the game",
                    allocation + " MB of " + totalMb + " MB is allocated. Android may kill the game.",
                    "optimize", "Set safe amount"));
        }

        // Storage
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long freeMb = stat.getAvailableBytes() / (1024 * 1024);
            if (freeMb < 2048) {
                findings.add(finding(WARNING, "Low storage",
                        "Only " + freeMb + " MB free. Minecraft downloads need several GB.",
                        "clean", "Clean junk"));
            }
        } catch (Exception ignored) { }

        // Junk
        long junk = junkSize();
        if (junk > 50L * 1024 * 1024) {
            findings.add(finding(INFO, "Junk files found",
                    SupersonicUi.formatBytes(junk) + " of cache, logs and crash reports can be removed.",
                    "clean", "Clean junk"));
        }

        // Mods vs loader
        SupersonicProfile profile = SupersonicProfile.current();
        if (profile != null) {
            File[] mods = profile.modsDir().listFiles((d, n) -> n.endsWith(".jar"));
            int modCount = mods == null ? 0 : mods.length;
            if (modCount > 0 && !profile.supportsMods()) {
                findings.add(finding(WARNING, "Mods without a mod loader",
                        "The mods folder of \"" + profile.displayName() + "\" has " + modCount
                                + " mod(s), but this instance has no Fabric/Quilt/Forge loader, so they will not load.",
                        null, null));
            }
        }

        // Last game log
        analyseLog(findings);

        if (findings.isEmpty()) {
            findings.add(finding(INFO, "Everything looks good", "No problems were found in your setup.", null, null));
        }
        return findings;
    }

    private static void analyseLog(List<Finding> findings) {
        File log = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        if (!log.isFile()) return;
        String text;
        try (RandomAccessFile file = new RandomAccessFile(log, "r")) {
            long length = file.length();
            int size = (int) Math.min(length, 600 * 1024);
            byte[] buffer = new byte[size];
            file.seek(length - size);
            file.readFully(buffer);
            text = new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }

        if (text.contains("OutOfMemoryError")) {
            findings.add(finding(PROBLEM, "The game ran out of memory",
                    "The last session crashed with OutOfMemoryError. Use a lighter performance mode, "
                            + "lower the render distance, or remove heavy mods.",
                    "optimize", "Apply best preset"));
        }
        if (text.contains("Could not create the Java Virtual Machine")) {
            findings.add(finding(PROBLEM, "Java could not start",
                    "The JVM refused to start, usually because too much RAM was requested.",
                    "optimize", "Set safe RAM"));
        }
        if (text.contains("UnsupportedClassVersionError")) {
            findings.add(finding(PROBLEM, "Wrong Java version",
                    "A mod or the game needs a newer Java. Check Settings → Minecraft → Java runtime.",
                    null, null));
        }
        if (text.contains("net.fabricmc") && text.contains("NoClassDefFoundError")) {
            findings.add(finding(WARNING, "A Fabric mod is missing a dependency",
                    "Install Fabric API from Addons and try again.", null, null));
        }
        if (text.contains("Mixin apply failed") || text.contains("MixinApplyError")) {
            findings.add(finding(WARNING, "Two mods conflict",
                    "A mixin failed to apply. Disable recently added mods in Addons → Installed.", null, null));
        }
    }

    // ------------------------------------------------------------------ fixes

    /** @return a human readable result */
    public static String applyFix(Context context, String fixId) {
        switch (fixId) {
            case "optimize":
                return optimizePerformance(context);
            case "clean":
                return cleanJunk(context);
            default:
                return "Nothing to do";
        }
    }

    /** Picks a preset that fits this phone and applies it. */
    public static String optimizePerformance(Context context) {
        int totalMb = PerformanceModes.totalRamMb(context);
        int mode = totalMb >= 6000 ? PerformanceModes.ULTRA : PerformanceModes.BALANCED;
        return PerformanceModes.apply(context, mode);
    }

    public static long junkSize() {
        long total = 0;
        if (Tools.DIR_CACHE != null) total += sizeOf(Tools.DIR_CACHE);
        for (File file : junkFiles()) total += sizeOf(file);
        return total;
    }

    private static List<File> junkFiles() {
        List<File> files = new ArrayList<>();
        files.add(new File(Tools.DIR_GAME_HOME, "latestlog.old.txt"));
        files.add(new File(Tools.DIR_GAME_NEW, "crash-reports"));
        files.add(new File(Tools.DIR_GAME_NEW, "logs"));
        return files;
    }

    /** Deletes cache, old logs and crash reports. Worlds, mods and settings are never touched. */
    public static String cleanJunk(Context context) {
        long before = junkSize();
        if (Tools.DIR_CACHE != null) deleteChildren(Tools.DIR_CACHE);
        for (File file : junkFiles()) deleteRecursively(file);
        long freed = Math.max(0, before - junkSize());
        return "Freed " + SupersonicUi.formatBytes(freed);
    }

    private static final String[] SETTING_KEYS = {
            "renderer", "ignoreNotch", "resolutionRatio", "sustainedPerformance", "alternate_surface",
            "force_vsync", "vsync_in_zink", "bigCoreAffinity", "allocation", "javaArgs", "java_sandbox",
            "checkLibraries", "arc_capes", "force_english", "downloadSource", "verifyManifest",
            "zinkPreferSystemDriver", "dump_shaders", PerformanceModes.PREF_KEY,
            "buttonscale", "mousescale", "mousespeed", "timeLongPressTrigger", "disableGestures",
            "disableDoubleTap", "mouse_start", "enableGyro"
    };

    /** Resets launcher settings to their defaults. Accounts, instances and worlds are kept. */
    public static String resetConfigurations(Context context) {
        SharedPreferences.Editor editor = LauncherPreferences.DEFAULT_PREF.edit();
        for (String key : SETTING_KEYS) editor.remove(key);
        editor.apply();
        LauncherPreferences.loadPreferences(context);
        return "Settings were reset to defaults";
    }

    // ------------------------------------------------------------------ files

    private static long sizeOf(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += sizeOf(child);
        return total;
    }

    private static void deleteChildren(File dir) {
        File[] children = dir.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    public static String severityLabel(int severity) {
        switch (severity) {
            case PROBLEM: return "✖ Problem";
            case WARNING: return "⚠ Warning";
            default: return "ℹ Info";
        }
    }

    public static String summary(List<Finding> findings) {
        int problems = 0;
        for (Finding f : findings) if (f.severity >= WARNING) problems++;
        return problems == 0 ? "No issues found" : String.format(Locale.US, "%d issue%s found", problems, problems == 1 ? "" : "s");
    }
}
