package net.kdt.pojavlaunch.supersonic;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.StatFs;

import com.google.gson.Gson;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The offline half of SS Agent: a rule-based diagnostic and repair tool.
 * It reads real data from this phone (RAM, storage, game log, mods folder, JVM arguments), detects
 * missing mods, mods that do not fit the game version, and JVM arguments that Java rejects, and it can
 * fix them. Every automatic fix is undoable ({@link #undoLast}). Nothing here uses the network except
 * downloading a missing mod from Modrinth. The online AI part is {@link AgentAi}.
 */
public final class AgentEngine {
    private AgentEngine() {}

    public static final int INFO = 0, WARNING = 1, PROBLEM = 2;

    public static final String FIX_OPTIMIZE = "optimize";
    public static final String FIX_CLEAN = "clean";
    public static final String FIX_DISABLE_MODS = "disable_mods";
    public static final String FIX_INSTALL_DEPS = "install_deps";
    public static final String FIX_JVM_ARGS = "fix_jvm";

    public static class Finding {
        public int severity;
        public String title, detail;
        /** null if there is nothing the Agent can fix automatically */
        public String fixId, fixLabel;
        /** What the fix works on: mod file paths, mod ids to install, or JVM arguments to turn off. */
        public List<String> data = new ArrayList<>();
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
                    FIX_OPTIMIZE, "Set safe amount"));
        }

        // Storage
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long freeMb = stat.getAvailableBytes() / (1024 * 1024);
            if (freeMb < 2048) {
                findings.add(finding(WARNING, "Low storage",
                        "Only " + freeMb + " MB free. Minecraft downloads need several GB.",
                        FIX_CLEAN, "Clean junk"));
            }
        } catch (Exception ignored) { }

        // Junk
        long junk = junkSize();
        if (junk > 50L * 1024 * 1024) {
            findings.add(finding(INFO, "Junk files found",
                    SupersonicUi.formatBytes(junk) + " of cache, logs and crash reports can be removed.",
                    FIX_CLEAN, "Clean junk"));
        }

        // Mods vs loader, mods vs game version
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
            if (profile.supportsMods()) checkModCompatibility(profile, findings);
        }

        // Last game log
        analyseLog(findings, profile);

        if (findings.isEmpty()) {
            findings.add(finding(INFO, "Everything looks good", "No problems were found in your setup.", null, null));
        }
        return findings;
    }

    // ------------------------------------------------------------------ mod versions

    /** Mods whose own metadata says they do not support this loader or Minecraft version. */
    private static void checkModCompatibility(SupersonicProfile profile, List<Finding> findings) {
        File[] jars = profile.modsDir().listFiles((d, n) -> n.endsWith(".jar"));
        if (jars == null) return;
        Arrays.sort(jars);

        Finding result = null;
        StringBuilder detail = new StringBuilder();
        int count = 0;
        for (File jar : jars) {
            ModJarInfo info;
            try {
                info = ModJarInfo.read(jar);
            } catch (IOException | RuntimeException e) {
                continue; // not a mod, or it has no readable metadata: never guess
            }
            if (info.serverOnly || info.variants.isEmpty()) continue;
            if (info.supports(profile.loader, profile.mcVersion)) continue;
            String why = info.whyNot(profile.loader, profile.mcVersion);
            if (why.startsWith("it does not say")) continue;

            if (result == null) {
                result = finding(PROBLEM, "", "", FIX_DISABLE_MODS, "Turn off");
            }
            count++;
            if (count <= 5) detail.append("• ").append(jar.getName()).append(": ").append(why).append('\n');
            result.data.add(jar.getAbsolutePath());
        }
        if (result != null) {
            if (count > 5) detail.append("• … and ").append(count - 5).append(" more\n");
            result.title = count + (count == 1 ? " mod does" : " mods do") + " not fit " + profile.mcVersion
                    + " (" + profile.loader + ")";
            result.detail = detail.toString().trim() + "\nThey will stop the game from starting. Turning them off is undoable.";
            findings.add(result);
        }
    }

    // ------------------------------------------------------------------ log

    private static final Pattern FABRIC_INSTALL = Pattern.compile("(?m)^\\s*-\\s*Install\\s+([A-Za-z0-9_.\\-]+)\\s*[,.]");
    private static final Pattern FABRIC_MISSING = Pattern.compile("(?i)of (?:[^\\n,(]*\\()?([a-z0-9_.\\-]+)\\)?,? which is missing");
    private static final Pattern FORGE_MISSING = Pattern.compile("Mod ID: '([^']+)'[^\\n]*Actual version: '\\[MISSING\\]'");
    private static final Pattern UNRECOGNIZED = Pattern.compile("Unrecognized (?:VM )?option[: ]+'?([^'\\s\\]]+)");
    private static final Pattern EXPERIMENTAL = Pattern.compile("VM option '([^']+)' is (?:experimental|diagnostic)");

    private static final Set<String> IGNORED_IDS = new HashSet<>(Arrays.asList(
            "minecraft", "java", "fabricloader", "fabric-loader", "fabric_loader", "quilt_loader", "quilt-loader",
            "forge", "neoforge", "fml", "javafml", "lowcodefml", "mclanguage"));

    /** Mod ids from log messages -> Modrinth slugs, for the mods whose id and slug differ. */
    private static final Map<String, String> MODRINTH_SLUGS = new HashMap<>();
    static {
        MODRINTH_SLUGS.put("fabric", "fabric-api");
        MODRINTH_SLUGS.put("fabric-api", "fabric-api");
        MODRINTH_SLUGS.put("cloth-config2", "cloth-config");
        MODRINTH_SLUGS.put("cloth_config", "cloth-config");
        MODRINTH_SLUGS.put("yet_another_config_lib_v3", "yacl");
        MODRINTH_SLUGS.put("owo", "owo-lib");
        MODRINTH_SLUGS.put("architectury", "architectury-api");
        MODRINTH_SLUGS.put("geckolib3", "geckolib");
        MODRINTH_SLUGS.put("kotlinforforge", "kotlin-for-forge");
        MODRINTH_SLUGS.put("puzzleslib", "puzzles-lib");
    }

    private static void analyseLog(List<Finding> findings, SupersonicProfile profile) {
        String text = readLogTail(600 * 1024);
        if (text == null) return;

        if (text.contains("OutOfMemoryError")) {
            findings.add(finding(PROBLEM, "The game ran out of memory",
                    "The last session crashed with OutOfMemoryError. Use a lighter performance mode, "
                            + "lower the render distance, or remove heavy mods.",
                    FIX_OPTIMIZE, "Apply best preset"));
        }
        if (text.contains("Could not create the Java Virtual Machine")) {
            findings.add(finding(PROBLEM, "Java could not start",
                    "The JVM refused to start, usually because too much RAM was requested or a JVM argument is invalid.",
                    FIX_OPTIMIZE, "Set safe RAM"));
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

        // Missing required mods (Fabric / Quilt / Forge dependency messages)
        Set<String> missing = missingDependencies(text);
        if (profile != null) missing.removeAll(presentIds(profile, missing));
        if (!missing.isEmpty()) {
            Finding f = finding(PROBLEM, "Required mod(s) are missing",
                    "The mod loader says these mods are needed but not installed: " + join(missing)
                            + ". SS Agent can download them from Modrinth.",
                    FIX_INSTALL_DEPS, "Install them");
            f.data.addAll(missing);
            findings.add(f);
        }

        // JVM arguments that Java does not accept
        Set<String> badOptions = new LinkedHashSet<>();
        Matcher m = UNRECOGNIZED.matcher(text);
        while (m.find()) badOptions.add(optionName(m.group(1)));
        m = EXPERIMENTAL.matcher(text);
        while (m.find()) badOptions.add(optionName(m.group(1)));
        badOptions.remove("");
        if (!badOptions.isEmpty()) analyseBadJvmOptions(findings, badOptions);
    }

    private static void analyseBadJvmOptions(List<Finding> findings, Set<String> badOptions) {
        List<String> stillOn = new ArrayList<>();
        boolean alreadyOff = false;
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs != null) {
            for (JvmArgsStore.Item item : JvmArgsStore.load(prefs)) {
                if (item.arg == null || !matchesAny(item.arg, badOptions)) continue;
                if (item.enabled) stillOn.add(item.arg.trim());
                else alreadyOff = true;
            }
        }
        if (!stillOn.isEmpty()) {
            Finding f = finding(PROBLEM, "Java rejected your JVM arguments",
                    "Java does not accept: " + join(stillOn) + ". The game cannot start with them. "
                            + "Turning them off is undoable.",
                    FIX_JVM_ARGS, "Turn them off");
            f.data.addAll(stillOn);
            findings.add(f);
        } else if (!alreadyOff) {
            findings.add(finding(PROBLEM, "Java rejected a JVM option",
                    "Java does not know: " + join(badOptions) + ". Check the JVM arguments of the instance "
                            + "and the Java runtime version (some flags only exist in newer Java).",
                    null, null));
        }
        // when the arguments are already off, the log line is old: nothing to report
    }

    /** "+UseZGC" / "UseZGC=1" -> "UseZGC". */
    private static String optionName(String raw) {
        String name = raw.trim();
        while (name.startsWith("+") || name.startsWith("-")) name = name.substring(1);
        int equals = name.indexOf('=');
        if (equals > 0) name = name.substring(0, equals);
        return name;
    }

    private static boolean matchesAny(String arg, Set<String> options) {
        for (String option : options) {
            if (!option.isEmpty() && arg.contains(option)) return true;
        }
        return false;
    }

    static Set<String> missingDependencies(String text) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = FABRIC_INSTALL.matcher(text);
        while (m.find()) addDependency(ids, m.group(1));
        m = FABRIC_MISSING.matcher(text);
        while (m.find()) addDependency(ids, m.group(1));
        m = FORGE_MISSING.matcher(text);
        while (m.find()) addDependency(ids, m.group(1));
        return ids;
    }

    private static void addDependency(Set<String> ids, String raw) {
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || IGNORED_IDS.contains(id)) return;
        ids.add(id);
    }

    /** Which of these ids are already in the mods folder (so an old log line is not a problem any more). */
    private static Set<String> presentIds(SupersonicProfile profile, Set<String> wanted) {
        Set<String> installed = new HashSet<>();
        File[] jars = profile.modsDir().listFiles((d, n) -> n.endsWith(".jar"));
        if (jars != null) {
            for (File jar : jars) {
                try {
                    ModJarInfo info = ModJarInfo.read(jar);
                    if (info.id != null) installed.add(info.id.toLowerCase(Locale.ROOT));
                } catch (IOException | RuntimeException ignored) { }
            }
        }
        Set<String> present = new HashSet<>();
        for (String id : wanted) {
            String canonical = MODRINTH_SLUGS.containsKey(id) ? MODRINTH_SLUGS.get(id) : id;
            if (installed.contains(id) || installed.contains(canonical)
                    || installed.contains(id.replace('_', '-')) || installed.contains(id.replace('-', '_'))) {
                present.add(id);
            }
        }
        return present;
    }

    private static String join(Iterable<String> items) {
        StringBuilder out = new StringBuilder();
        for (String item : items) {
            if (out.length() > 0) out.append(", ");
            out.append(item);
        }
        return out.toString();
    }

    /** @return the last part of the game log, or null if there is no log */
    public static String readLogTail(int maxBytes) {
        File log = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        if (!log.isFile()) return null;
        try (RandomAccessFile file = new RandomAccessFile(log, "r")) {
            long length = file.length();
            int size = (int) Math.min(length, maxBytes);
            byte[] buffer = new byte[size];
            file.seek(length - size);
            file.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ fixes

    /** @return a human readable result */
    public static String applyFix(Context context, String fixId) {
        switch (fixId) {
            case FIX_OPTIMIZE:
                return optimizePerformance(context);
            case FIX_CLEAN:
                return cleanJunk(context);
            default:
                return "Nothing to do";
        }
    }

    /** Applies the fix of one finding, including the fixes that need the finding's data. */
    public static String applyFix(Context context, Finding finding) {
        if (finding.fixId == null) return "Nothing to do";
        switch (finding.fixId) {
            case FIX_DISABLE_MODS:
                return disableMods(context, finding.data);
            case FIX_INSTALL_DEPS:
                return installDependencies(context, finding.data);
            case FIX_JVM_ARGS:
                return disableJvmArguments(context, finding.data);
            default:
                return applyFix(context, finding.fixId);
        }
    }

    /**
     * Applies every fix that is safe to run without asking: it never deletes anything (no junk cleaning,
     * because that would remove the log that is being analysed), and everything it changes can be undone
     * except the performance preset.
     *
     * @return one line per fix, for the report
     */
    public static List<String> applySafeFixes(Context context, List<Finding> findings) {
        List<String> results = new ArrayList<>();
        boolean optimized = false;
        for (Finding finding : findings) {
            if (finding.fixId == null || finding.severity < WARNING || FIX_CLEAN.equals(finding.fixId)) continue;
            if (FIX_OPTIMIZE.equals(finding.fixId)) {
                if (optimized) continue;
                optimized = true;
            }
            String result;
            try {
                result = applyFix(context, finding);
            } catch (RuntimeException e) {
                result = "failed (" + e.getMessage() + ")";
            }
            results.add(finding.title + " → " + result);
        }
        return results;
    }

    /** Picks a preset that fits this phone and applies it. */
    public static String optimizePerformance(Context context) {
        int totalMb = PerformanceModes.totalRamMb(context);
        int mode = totalMb >= 6000 ? PerformanceModes.ULTRA : PerformanceModes.BALANCED;
        return PerformanceModes.apply(context, mode);
    }

    /** Renames the mods to .jar.disabled (the same thing the Addons screen does). */
    private static String disableMods(Context context, List<String> paths) {
        Journal journal = loadJournal(context);
        int done = 0;
        for (String path : paths) {
            File file = new File(path);
            if (!file.isFile() || !file.getName().endsWith(".jar")) continue;
            File target = new File(file.getParentFile(), file.getName() + ".disabled");
            if (file.renameTo(target)) {
                journal.disabledMods.add(target.getAbsolutePath());
                done++;
            }
        }
        saveJournal(context, journal);
        return done == 0 ? "No mod was changed"
                : "Turned off " + done + " mod" + (done == 1 ? "" : "s") + " (turn back on in Addons or with Undo)";
    }

    /** Downloads the missing mods from Modrinth for the selected instance. Needs internet. */
    private static String installDependencies(Context context, List<String> ids) {
        SupersonicProfile profile = SupersonicProfile.current();
        if (profile == null || !profile.supportsMods()) return "No instance with a mod loader is selected";

        File dir = profile.modsDir();
        Set<String> before = fileNames(dir);
        Set<String> installedProjects = ModrinthClient.projectIds(ModrinthClient.scanInstalled(dir));
        Set<String> visited = new HashSet<>();
        List<String> failed = new ArrayList<>();

        for (String id : ids) {
            boolean ok = false;
            for (String slug : slugCandidates(id)) {
                try {
                    ModrinthClient.install(slug, false, profile, installedProjects, visited, 0);
                    ok = true;
                    break;
                } catch (IOException ignored) {
                    // try the next spelling
                }
            }
            if (!ok) failed.add(id);
        }

        Set<String> added = fileNames(dir);
        added.removeAll(before);
        if (!added.isEmpty()) {
            Journal journal = loadJournal(context);
            for (String name : added) journal.installedMods.add(new File(dir, name).getAbsolutePath());
            saveJournal(context, journal);
        }

        StringBuilder out = new StringBuilder();
        if (!added.isEmpty()) out.append("Installed ").append(join(added));
        if (!failed.isEmpty()) {
            if (out.length() > 0) out.append("; ");
            out.append("could not get ").append(join(failed)).append(" (no internet, or no version for Minecraft ")
                    .append(profile.mcVersion).append(")");
        }
        return out.length() == 0 ? "Already installed" : out.toString();
    }

    private static List<String> slugCandidates(String id) {
        List<String> slugs = new ArrayList<>();
        String alias = MODRINTH_SLUGS.get(id);
        if (alias != null) slugs.add(alias);
        if (!slugs.contains(id)) slugs.add(id);
        String hyphen = id.replace('_', '-');
        if (!slugs.contains(hyphen)) slugs.add(hyphen);
        return slugs;
    }

    private static Set<String> fileNames(File dir) {
        Set<String> names = new HashSet<>();
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) names.add(file.getName());
        return names;
    }

    /** Switches JVM arguments off (they stay in the list, so they can be switched on again). */
    private static String disableJvmArguments(Context context, List<String> args) {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null) return "Settings are not loaded yet";
        List<JvmArgsStore.Item> items = JvmArgsStore.load(prefs);
        Journal journal = loadJournal(context);
        int done = 0;
        for (JvmArgsStore.Item item : items) {
            if (!item.enabled || item.arg == null || !args.contains(item.arg.trim())) continue;
            item.enabled = false;
            journal.disabledArgs.add(item.arg.trim());
            done++;
        }
        if (done == 0) return "No JVM argument was changed";
        JvmArgsStore.save(prefs, items);
        saveJournal(context, journal);
        LauncherPreferences.loadPreferences(context);
        return "Turned off " + done + " JVM argument" + (done == 1 ? "" : "s") + " (turn back on in JVM arguments or with Undo)";
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

    // ------------------------------------------------------------------ undo

    /** What SS Agent changed, so that it can be put back. Stored as JSON in the launcher preferences. */
    private static final class Journal {
        List<String> disabledMods = new ArrayList<>();
        List<String> installedMods = new ArrayList<>();
        List<String> disabledArgs = new ArrayList<>();

        boolean isEmpty() {
            return disabledMods.isEmpty() && installedMods.isEmpty() && disabledArgs.isEmpty();
        }
    }

    private static final String PREF_JOURNAL = "ss_agent_journal";
    private static final Gson GSON = new Gson();

    private static SharedPreferences journalPrefs(Context context) {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        return prefs != null ? prefs : androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
    }

    private static Journal loadJournal(Context context) {
        try {
            Journal journal = GSON.fromJson(journalPrefs(context).getString(PREF_JOURNAL, ""), Journal.class);
            if (journal != null && journal.disabledMods != null && journal.installedMods != null
                    && journal.disabledArgs != null) {
                return journal;
            }
        } catch (RuntimeException ignored) { }
        return new Journal();
    }

    private static void saveJournal(Context context, Journal journal) {
        journalPrefs(context).edit().putString(PREF_JOURNAL, journal.isEmpty() ? "" : GSON.toJson(journal)).apply();
    }

    public static boolean hasUndo(Context context) {
        return !loadJournal(context).isEmpty();
    }

    /** Puts back the mods and JVM arguments that SS Agent changed, and removes the mods it downloaded. */
    public static String undoLast(Context context) {
        Journal journal = loadJournal(context);
        if (journal.isEmpty()) return "Nothing to undo";
        int mods = 0, removed = 0, args = 0;

        for (String path : journal.disabledMods) {
            File file = new File(path);
            if (!file.isFile() || !file.getName().endsWith(".jar.disabled")) continue;
            String name = file.getName();
            if (file.renameTo(new File(file.getParentFile(), name.substring(0, name.length() - ".disabled".length())))) mods++;
        }
        for (String path : journal.installedMods) {
            File file = new File(path);
            //noinspection ResultOfMethodCallIgnored
            if (file.isFile() && file.delete()) removed++;
        }
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs != null && !journal.disabledArgs.isEmpty()) {
            List<JvmArgsStore.Item> items = JvmArgsStore.load(prefs);
            for (JvmArgsStore.Item item : items) {
                if (!item.enabled && item.arg != null && journal.disabledArgs.contains(item.arg.trim())) {
                    item.enabled = true;
                    args++;
                }
            }
            JvmArgsStore.save(prefs, items);
            LauncherPreferences.loadPreferences(context);
        }
        saveJournal(context, new Journal());

        List<String> parts = new ArrayList<>();
        if (mods > 0) parts.add(mods + " mod(s) turned on again");
        if (removed > 0) parts.add(removed + " downloaded mod(s) removed");
        if (args > 0) parts.add(args + " JVM argument(s) turned on again");
        return parts.isEmpty() ? "Nothing needed to be put back" : join(parts);
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
