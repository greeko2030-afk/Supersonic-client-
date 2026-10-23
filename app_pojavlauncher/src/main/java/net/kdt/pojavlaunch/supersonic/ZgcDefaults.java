package net.kdt.pojavlaunch.supersonic;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ZGC as the default garbage collector of the game.
 *
 * <ul>
 *   <li>Java 17 gets {@code -XX:+UseZGC}. Java 21 also gets {@code -XX:+ZGenerational} (generational ZGC).
 *       Java 8 has no ZGC, so it keeps its default collector.</li>
 *   <li>A collector that the user chose in the Java arguments is never replaced: two collectors together
 *       make the JVM stop with "Multiple garbage collectors selected".</li>
 *   <li>The switch in the settings turns the default off.</li>
 *   <li>Safety: ZGC needs a large address space and a way to map memory that some Android devices do not allow.
 *       When the JVM stops right at the start with a message about its initialization, ZGC is not used any more
 *       and the default collector of Java is used instead. This lasts until the launcher is updated. A user who
 *       puts {@code -XX:+UseZGC} in the Java arguments gets ZGC anyway.</li>
 * </ul>
 */
public final class ZgcDefaults {
    private ZgcDefaults() {}

    /** Key of the switch in the settings. It is on by default. */
    public static final String PREF_KEY = "supersonic_zgc";

    /** ZGC is a production feature from Java 15, and the runtimes of the launcher are 8, 17 and 21. */
    private static final int MIN_JAVA_VERSION = 17;
    /** Generational ZGC was added in Java 21. */
    private static final int GENERATIONAL_JAVA_VERSION = 21;
    /** A JVM that stops after the start of the game within this time failed in its initialization. */
    private static final long STARTUP_WINDOW_MS = 30_000L;
    private static final int LOG_TAIL_BYTES = 64 * 1024;

    /** Any collector flag: -XX:+UseG1GC, -XX:+UseParallelGC, -XX:+UseSerialGC, -XX:+UseZGC, -XX:+UseShenandoahGC ... */
    private static final Pattern COLLECTOR_FLAG = Pattern.compile("^-XX:[+-]Use\\w+GC$");
    private static final Pattern GENERATIONAL_FLAG = Pattern.compile("^-XX:[+-]ZGenerational(=.*)?$");

    /** The arguments to add, and why there are none when the list is empty. */
    public static final class Result {
        public final List<String> arguments;
        public final String reason;

        Result(List<String> arguments, String reason) {
            this.arguments = Collections.unmodifiableList(arguments);
            this.reason = reason;
        }

        public boolean used() {
            return !arguments.isEmpty();
        }
    }

    /** Is the ZGC default turned on in the settings? */
    public static boolean isEnabled() {
        try {
            return LauncherPreferences.DEFAULT_PREF == null
                    || LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_KEY, true);
        } catch (ClassCastException e) {
            return true;
        }
    }

    /**
     * @param javaVersion     the major version of the runtime: 8, 17 or 21
     * @param userArguments   the Java arguments of the user (they are looked at, and not changed)
     * @param launcherVersion the version of the launcher, to end a block when the launcher is updated
     */
    public static Result decide(int javaVersion, List<String> userArguments, String launcherVersion) {
        if (!isEnabled()) return none("it is turned off in the settings");
        if (javaVersion < MIN_JAVA_VERSION) return none("Java " + javaVersion + " has no ZGC");
        for (String argument : userArguments) {
            if (COLLECTOR_FLAG.matcher(argument).matches()) {
                // The user chose a collector on purpose, also when it is ZGC after a block
                return none("the Java arguments already select a collector (" + argument + ")");
            }
        }
        if (isBlocked(launcherVersion)) {
            return none("it did not start on this device before. Add -XX:+UseZGC to the Java arguments to try it again");
        }

        List<String> arguments = new ArrayList<>();
        arguments.add("-XX:+UseZGC");
        if (javaVersion >= GENERATIONAL_JAVA_VERSION && !hasArgument(userArguments, GENERATIONAL_FLAG)) {
            arguments.add("-XX:+ZGenerational");
        }
        return new Result(arguments, "default");
    }

    private static Result none(String reason) {
        return new Result(new ArrayList<String>(), reason);
    }

    private static boolean hasArgument(List<String> arguments, Pattern pattern) {
        for (String argument : arguments) {
            if (pattern.matcher(argument).matches()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the start-failure guard

    private static File blockedFile() {
        return new File(new File(Tools.DIR_DATA, "supersonic_engine"), "zgc_blocked");
    }

    /** True when ZGC did not start with this version of the launcher. A newer launcher tries it again. */
    static boolean isBlocked(String launcherVersion) {
        File file = blockedFile();
        return file.isFile() && readText(file).equals(launcherVersion);
    }

    /**
     * Call when the JVM has stopped. When ZGC was used and the JVM stopped in its initialization, ZGC is blocked.
     *
     * @param exitCode        the exit code of the JVM
     * @param runtimeMs       how long the JVM ran
     * @param logFile         the game log, where the JVM wrote its message
     * @param launcherVersion the version of the launcher
     * @return true when ZGC was blocked
     */
    public static boolean onJvmExit(int exitCode, long runtimeMs, File logFile, String launcherVersion) {
        if (exitCode == 0 || runtimeMs > STARTUP_WINDOW_MS) return false;
        if (!looksLikeStartFailure(tail(logFile))) return false;
        writeText(blockedFile(), launcherVersion);
        return true;
    }

    /**
     * The messages of a JVM that could not start with its collector, for example
     * "Failed to reserve enough address space for Java heap" and "Error occurred during initialization of VM".
     */
    static boolean looksLikeStartFailure(String logTail) {
        return logTail.contains("Error occurred during initialization of VM")
                || logTail.contains("Failed to reserve enough address space")
                || logTail.contains("[error][gc");
    }

    private static String tail(File file) {
        if (file == null || !file.isFile()) return "";
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, in.length() - LOG_TAIL_BYTES);
            in.seek(start);
            byte[] bytes = new byte[(int) (in.length() - start)];
            in.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String readText(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
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
            // The guard is best effort. Without it the game simply starts as usual.
        }
    }
}
