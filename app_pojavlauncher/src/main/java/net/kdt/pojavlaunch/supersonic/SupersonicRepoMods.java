package net.kdt.pojavlaunch.supersonic;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The mods of the Supersonic repository on GitHub (github.com/greeko2030-afk/mods).
 *
 * Every .jar in that repository is a mod. When the launcher opens, it checks the repository, downloads the
 * mods that are new or changed, and installs each one into every instance that it supports. Which instances
 * those are is read from the mod itself (see ModJarInfo): the Minecraft version and the mod loader that the
 * jar names in its own metadata have to match the instance exactly. A mod that does not say which version it
 * supports is not installed.
 *
 * <ul>
 *   <li>The list of the repository is one request to the GitHub API, and it is answered with
 *       "304 Not Modified" (which is not counted against the rate limit) when nothing changed.
 *       The API only allows 60 requests per hour for one IP address, and many phones share one address.
 *       When GitHub refuses because of that, the repository is downloaded as one zip file instead,
 *       which has no such limit.</li>
 *   <li>Each download is checked against the git hash that GitHub lists for the file.</li>
 *   <li>A mod that the user removed from an instance is not installed again, and a mod that the user disabled
 *       stays disabled when it is updated. A mod that the user installed on their own is never replaced.</li>
 *   <li>Without a network, the mods that were downloaded earlier are still installed.</li>
 * </ul>
 */
public final class SupersonicRepoMods {
    private static final String TAG = "SupersonicRepoMods";

    /** The repository with the mods. */
    static final String OWNER = "greeko2030-afk";
    static final String REPO = "mods";
    static final String BRANCH = "main";
    // not final: the tests point them to a local server
    static String sApiBase = "https://api.github.com";
    static String sRawBase = "https://raw.githubusercontent.com";
    static String sZipUrl = "https://codeload.github.com/" + OWNER + "/" + REPO + "/zip/refs/heads/" + BRANCH;

    /** Key of the switch in the settings. It is on by default. */
    public static final String PREF_KEY = "supersonic_repo_mods";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final long MAX_JAR_BYTES = 64L * 1024 * 1024;
    private static final long MAX_LIST_BYTES = 8L * 1024 * 1024;
    /** The repository is not asked again within this time while the app stays open. */
    private static final long MIN_SYNC_INTERVAL_MS = 10 * 60 * 1000L;
    /** A launch waits at most this long for a check that is still running. */
    private static final long LAUNCH_WAIT_MS = 20_000L;

    private static final String STATE_FILE = ".supersonic-repo-mods.json";
    private static final Gson GSON = new Gson();

    private static final Object LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "supersonic-repo-mods");
        thread.setDaemon(true);
        return thread;
    });
    private static Future<?> sSyncFuture;
    private static long sLastSyncStart;

    private SupersonicRepoMods() {}

    // ------------------------------------------------------------------ data

    /** One jar of the repository. */
    static final class Entry {
        String path;
        String sha;
        long size;
        /** What the jar says about itself. Null when the jar could not be read. */
        ModJarInfo info;
        /** Why the jar is not a usable mod. */
        String error;
    }

    /** What is known about the repository: the last list and the mods that were read from it. */
    static final class Index {
        String etag;
        Map<String, Entry> files = new LinkedHashMap<>();
    }

    /** A mod that the launcher installed into an instance. */
    static final class Installed {
        String file;
        String sha;
        String path;
    }

    /** The mods that the launcher installed into one instance. The key is the id of the mod. */
    static final class InstanceState {
        Map<String, Installed> mods = new LinkedHashMap<>();
    }

    /** Receives the text of a check that the user asked for. */
    public interface ReportCallback {
        void onReport(String report);
    }

    /** What happened during a check, in words: the repository, each jar, and each instance. */
    static final class Report {
        private final StringBuilder text = new StringBuilder();

        void add(String line) {
            text.append(line).append('\n');
        }

        @Override
        public String toString() {
            return text.toString().trim();
        }
    }

    private static void note(@Nullable Report report, String line) {
        if (report != null) report.add(line);
    }

    /** GitHub answered the list request with an error status, for example 403 when the rate limit is reached. */
    static final class ApiException extends IOException {
        final int code;

        ApiException(int code) {
            super("GitHub answered HTTP " + code
                    + (code == 403 || code == 429 ? " (too many requests from this network)" : ""));
            this.code = code;
        }
    }

    // ------------------------------------------------------------------ entry points

    /** Is the automatic installation of the repository mods turned on? */
    public static boolean isEnabled() {
        try {
            return LauncherPreferences.DEFAULT_PREF == null
                    || LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_KEY, true);
        } catch (ClassCastException e) {
            return true;
        }
    }

    /**
     * Call when the launcher opens. It checks the repository in the background, downloads new and changed
     * mods and installs them into the instances that they support.
     *
     * @param context used for a short message when mods were installed, may be null
     */
    public static void syncAsync(@Nullable Context context) {
        if (!isEnabled()) return;
        synchronized (LOCK) {
            if (sSyncFuture != null && !sSyncFuture.isDone()) return;
            long now = System.currentTimeMillis();
            if (sLastSyncStart != 0 && now - sLastSyncStart < MIN_SYNC_INTERVAL_MS) return;
            sLastSyncStart = now;
            final Context appContext = context == null ? null : context.getApplicationContext();
            sSyncFuture = EXECUTOR.submit(() -> {
                int installed = 0;
                try {
                    installed = syncAndInstallAll();
                } catch (Throwable t) {
                    Log.w(TAG, "The mod check failed", t);
                }
                if (installed > 0 && appContext != null) {
                    final String text = "Supersonic: installed or updated " + installed
                            + (installed == 1 ? " mod" : " mods");
                    Tools.runOnUiThread(() -> Toast.makeText(appContext, text, Toast.LENGTH_LONG).show());
                }
            });
        }
    }

    /**
     * Call right before the game starts. It waits for a check that is still running (at most a few seconds)
     * and makes sure that the instance that starts has all the mods that it supports. It never throws.
     */
    public static void prepareForLaunch() {
        if (!isEnabled()) return;
        try {
            Future<?> running;
            synchronized (LOCK) {
                running = sSyncFuture;
            }
            if (running != null && !running.isDone()) {
                try {
                    running.get(LAUNCH_WAIT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    Log.w(TAG, "The mod check is not finished, the mods from earlier are used");
                }
            }
            SupersonicProfile profile = SupersonicProfile.current();
            if (profile != null) installForProfile(profile);
        } catch (Throwable t) {
            Log.w(TAG, "Could not prepare the mods for the launch", t);
        }
    }

    /**
     * The check that the user asks for in the settings. It does the same as the check at the start of the app,
     * and tells in words what it found and why a mod was or was not installed.
     */
    public static void checkNow(final ReportCallback callback) {
        EXECUTOR.submit(() -> {
            Report report = new Report();
            if (!isEnabled()) report.add("Note: the \"Supersonic mods\" switch is off, so this check was started by hand.");
            try {
                runCheck(report);
            } catch (Throwable t) {
                report.add("The check stopped with an error: " + t);
            }
            saveReport(report);
            callback.onReport(report.toString());
        });
    }

    /** The report of the last check, or null. */
    @Nullable
    public static String lastReport() {
        File file = new File(rootDir(), "last_report.txt");
        return file.isFile() ? readFile(file) : null;
    }

    private static void saveReport(Report report) {
        try {
            writeText(new File(rootDir(), "last_report.txt"), report.toString());
        } catch (IOException ignored) {
            // The report is only an aid
        }
    }

    /** Checks the repository, then installs into every instance. @return how many mods were installed or updated */
    private static int syncAndInstallAll() {
        Report report = new Report();
        int installed = runCheck(report);
        saveReport(report);
        return installed;
    }

    private static int runCheck(Report report) {
        report.add("Repository: github.com/" + OWNER + "/" + REPO + " (branch " + BRANCH + ")");
        try {
            syncRepo(report);
        } catch (IOException e) {
            report.add("Could not check the repository: " + e.getMessage());
            report.add("The mods that were downloaded earlier are used.");
        }

        Index index = loadIndex();
        report.add("Jar files in the repository: " + index.files.size());
        for (Entry entry : index.files.values()) {
            if (entry.info != null) {
                report.add("- " + entry.path + ": mod \"" + entry.info.name + "\" "
                        + (entry.info.version == null ? "" : entry.info.version) + ", supports " + entry.info.describeSupport());
            } else {
                report.add("- " + entry.path + ": not a usable mod (" + entry.error + ")");
            }
        }

        int installed = 0;
        int instances = 0;
        LauncherProfiles.load();
        if (LauncherProfiles.mainProfileJson != null) {
            for (String key : new ArrayList<>(LauncherProfiles.mainProfileJson.profiles.keySet())) {
                SupersonicProfile profile = SupersonicProfile.of(key);
                if (profile == null) continue;
                instances++;
                String title = "Instance \"" + profile.displayName() + "\" ("
                        + (profile.supportsMods() ? ModJarInfo.loaderName(profile.loader) : profile.loader)
                        + " " + profile.mcVersion + ")";
                if (!profile.supportsMods()) {
                    report.add(title + ": no mod loader, so no mods are installed here");
                    continue;
                }
                report.add(title + ":");
                installed += installForInstance(profile.modsDir(), profile.loader, profile.mcVersion, report);
            }
        }
        if (instances == 0) report.add("There is no instance yet.");
        report.add(installed == 0 ? "Nothing was installed or updated."
                : "Installed or updated: " + installed + (installed == 1 ? " mod" : " mods") + ".");
        return installed;
    }

    static int installForProfile(SupersonicProfile profile) {
        if (!profile.supportsMods()) return 0;
        return installForInstance(profile.modsDir(), profile.loader, profile.mcVersion, null);
    }

    // ------------------------------------------------------------------ the repository

    private static File rootDir() {
        return new File(Tools.DIR_DATA, "supersonic_repo_mods");
    }

    private static File jarsDir() {
        return new File(rootDir(), "jars");
    }

    private static File indexFile() {
        return new File(rootDir(), "index.json");
    }

    static Index loadIndex() {
        File file = indexFile();
        if (!file.isFile()) return new Index();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Index index = GSON.fromJson(reader, Index.class);
            if (index == null) return new Index();
            if (index.files == null) index.files = new LinkedHashMap<>();
            return index;
        } catch (Exception e) {
            return new Index();
        }
    }

    private static void saveIndex(Index index) throws IOException {
        writeText(indexFile(), GSON.toJson(index));
    }

    static synchronized int syncRepo() throws IOException {
        return syncRepo(null);
    }

    /**
     * Reads the list of the repository, downloads the jars that are new or changed and forgets the jars that
     * were deleted from the repository. The list comes from the GitHub API. When the API refuses (for example
     * because too many phones share the IP address), the whole repository is downloaded as a zip file.
     *
     * @return how many jars were downloaded
     */
    static synchronized int syncRepo(@Nullable Report report) throws IOException {
        Index known = loadIndex();
        File jars = jarsDir();
        if (!jars.isDirectory() && !jars.mkdirs()) throw new IOException("Cannot create " + jars);
        try {
            return syncFromApi(known, jars, report);
        } catch (ApiException e) {
            note(report, "GitHub API: " + e.getMessage() + ". The repository is downloaded as a zip file instead.");
            Log.w(TAG, e.getMessage() + ", using the zip download");
            return syncFromZip(known, jars, report);
        }
    }

    private static int syncFromApi(Index known, File jars, @Nullable Report report) throws IOException {
        String listUrl = sApiBase + "/repos/" + OWNER + "/" + REPO + "/git/trees/" + BRANCH + "?recursive=1";
        HttpURLConnection connection = open(listUrl, "application/vnd.github+json");
        // Nothing changed since the last time: GitHub answers 304 and it is not counted as a request
        if (known.etag != null && !known.files.isEmpty()) connection.setRequestProperty("If-None-Match", known.etag);
        String body;
        String etag;
        try {
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                note(report, "The repository did not change since the last check.");
                return 0;
            }
            if (code != HttpURLConnection.HTTP_OK) throw new ApiException(code);
            etag = connection.getHeaderField("ETag");
            body = new String(readAll(connection.getInputStream(), MAX_LIST_BYTES), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }

        JsonObject root;
        try {
            root = new JsonParser().parse(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("GitHub sent an answer that is not a list of files", e);
        }
        JsonArray tree = root.has("tree") && root.get("tree").isJsonArray() ? root.getAsJsonArray("tree") : new JsonArray();

        Index next = new Index();
        next.etag = etag;
        int downloaded = 0;
        boolean complete = true;
        for (JsonElement element : tree) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String type = text(item, "type");
            String path = text(item, "path");
            String sha = text(item, "sha");
            if (!"blob".equals(type) || path == null || sha == null) continue;
            if (!path.toLowerCase().endsWith(".jar")) continue;
            long size = item.has("size") && item.get("size").isJsonPrimitive() ? item.get("size").getAsLong() : -1;
            if (size > MAX_JAR_BYTES) {
                note(report, "Skipped " + path + ": the file is larger than " + (MAX_JAR_BYTES / 1024 / 1024) + " MB.");
                continue;
            }

            Entry old = known.files.get(path);
            File cached = new File(jars, sha + ".jar");
            if (isUnchanged(old, sha, cached)) {
                next.files.put(path, old);
                continue;
            }

            Entry entry = new Entry();
            entry.path = path;
            entry.sha = sha;
            entry.size = size;
            try {
                download(path, sha, cached);
                downloaded++;
            } catch (IOException e) {
                // A network problem or a damaged download is tried again at the next check
                Log.w(TAG, "Could not download " + path + ": " + e.getMessage());
                note(report, "Could not download " + path + ": " + e.getMessage());
                complete = false;
                continue;
            }
            readInfo(entry, cached);
            next.files.put(path, entry);
        }
        // When something could not be downloaded, the list is asked again at the next check
        if (!complete) next.etag = null;
        finishIndex(next, jars);
        note(report, "Repository checked through the GitHub API: " + downloaded + " new or changed jar file(s).");
        return downloaded;
    }

    /**
     * The repository as one zip file. It is used when the API refuses. The jars are read from the zip while it
     * is downloaded, and the hash of each one is worked out in the same way that git does it, so the result is
     * the same as with the API.
     */
    private static int syncFromZip(Index known, File jars, @Nullable Report report) throws IOException {
        Index next = new Index(); // no ETag: the API is asked first at the next check
        int downloaded = 0;
        HttpURLConnection connection = open(sZipUrl, "application/zip");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("The repository download answered HTTP " + code);
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(connection.getInputStream()))) {
                ZipEntry zipEntry;
                while ((zipEntry = zip.getNextEntry()) != null) {
                    if (zipEntry.isDirectory()) continue;
                    String path = withoutRootFolder(zipEntry.getName());
                    if (path == null || !path.toLowerCase().endsWith(".jar")) continue;

                    File part = new File(jars, ".zip-" + next.files.size() + ".part");
                    long size;
                    try {
                        size = copyLimited(zip, part, MAX_JAR_BYTES);
                    } catch (IOException e) {
                        part.delete();
                        if (e instanceof TooLargeException) {
                            note(report, "Skipped " + path + ": the file is larger than " + (MAX_JAR_BYTES / 1024 / 1024) + " MB.");
                            continue;
                        }
                        throw e;
                    }
                    String sha = gitBlobSha(part);
                    File cached = new File(jars, sha + ".jar");
                    Entry old = known.files.get(path);
                    if (isUnchanged(old, sha, cached)) {
                        part.delete();
                        next.files.put(path, old);
                        continue;
                    }
                    if (cached.exists() && !cached.delete()) {
                        part.delete();
                        throw new IOException("Cannot replace " + cached);
                    }
                    if (!part.renameTo(cached)) {
                        part.delete();
                        throw new IOException("Cannot move the download into place");
                    }
                    Entry entry = new Entry();
                    entry.path = path;
                    entry.sha = sha;
                    entry.size = size;
                    readInfo(entry, cached);
                    next.files.put(path, entry);
                    downloaded++;
                }
            }
        } finally {
            connection.disconnect();
        }
        finishIndex(next, jars);
        note(report, "Repository downloaded as a zip file: " + downloaded + " new or changed jar file(s).");
        return downloaded;
    }

    /** "mods-main/folder/a.jar" -> "folder/a.jar". The zip of GitHub has one folder around everything. */
    @Nullable
    private static String withoutRootFolder(String name) {
        int slash = name.indexOf('/');
        return slash < 0 || slash == name.length() - 1 ? null : name.substring(slash + 1);
    }

    private static final class TooLargeException extends IOException {
        TooLargeException() {
            super("The file is too large");
        }
    }

    /** @return the number of bytes that were copied */
    private static long copyLimited(InputStream in, File target, long max) throws IOException {
        long total = 0;
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > max) throw new TooLargeException();
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    /** Unchanged: the same git hash as before, and the jar is still on disk. */
    private static boolean isUnchanged(@Nullable Entry old, String sha, File cached) {
        return old != null && sha.equals(old.sha) && (old.error != null || (old.info != null && cached.isFile()));
    }

    /** Reads what the jar says about itself. A jar that is not a mod is remembered, so it is not downloaded again. */
    private static void readInfo(Entry entry, File cached) {
        try {
            entry.info = ModJarInfo.read(cached);
        } catch (IOException e) {
            // The cache names a jar by its hash, the report should name it as it is in the repository
            String message = e.getMessage() == null ? "unreadable" : e.getMessage();
            entry.error = message.replace(cached.getName(), entry.path);
            cached.delete();
            Log.w(TAG, "Not a usable mod: " + entry.path + ": " + e.getMessage());
        }
    }

    /** Forgets the cached jars that are not in the new list, and saves the list. */
    private static void finishIndex(Index next, File jars) throws IOException {
        Set<String> keep = new HashSet<>();
        for (Entry entry : next.files.values()) {
            if (entry.info != null) keep.add(entry.sha + ".jar");
        }
        File[] cachedJars = jars.listFiles();
        if (cachedJars != null) {
            for (File cached : cachedJars) {
                if (!keep.contains(cached.getName())) cached.delete(); // the copies in the instances stay
            }
        }
        saveIndex(next);
    }

    private static void download(String path, String sha, File target) throws IOException {
        HttpURLConnection connection = open(sRawBase + "/" + OWNER + "/" + REPO + "/" + BRANCH + "/" + encodePath(path), "*/*");
        File part = new File(target.getParentFile(), target.getName() + ".part");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            if (connection.getContentLength() > MAX_JAR_BYTES) throw new IOException("The file is too large");
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[32768];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_JAR_BYTES) throw new IOException("The file is too large");
                    out.write(buffer, 0, read);
                }
            }
            // GitHub lists the git hash of every file, so a damaged or cut download is found here
            if (!sha.equalsIgnoreCase(gitBlobSha(part))) throw new IOException("The checksum does not match");
            if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target);
            if (!part.renameTo(target)) throw new IOException("Cannot move the download into place");
        } finally {
            connection.disconnect();
            if (part.exists()) part.delete();
        }
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "Supersonic-Client");
        connection.setRequestProperty("Accept", accept);
        return connection;
    }

    private static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String segment : path.split("/")) {
            if (out.length() > 0) out.append('/');
            try {
                out.append(URLEncoder.encode(segment, "UTF-8").replace("+", "%20"));
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        return out.toString();
    }

    /** The hash that git (and the GitHub API) gives to a file: SHA-1 of "blob <size>\0" and the content. */
    static String gitBlobSha(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + file.length() + "\0").getBytes(StandardCharsets.UTF_8));
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[32768];
                int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    // ------------------------------------------------------------------ the instances

    static synchronized int installForInstance(File modsDir, String loader, String mcVersion) {
        return installForInstance(modsDir, loader, mcVersion, null);
    }

    /**
     * Installs the mods of the repository that support this loader and this Minecraft version
     * into a mods folder.
     *
     * @param report gets a line for every mod (installed, kept, or why it does not fit), may be null
     * @return how many mods were installed or updated
     */
    static synchronized int installForInstance(File modsDir, String loader, String mcVersion, @Nullable Report report) {
        if (!isModLoader(loader) || mcVersion == null || mcVersion.isEmpty()) return 0;
        Index index = loadIndex();
        Map<String, Entry> builds = bestBuilds(index, loader, mcVersion);
        if (report != null) explainSkipped(index, builds, loader, mcVersion, report);
        if (builds.isEmpty()) {
            note(report, "  Nothing in the repository supports this instance.");
            return 0;
        }
        if (!modsDir.isDirectory() && !modsDir.mkdirs()) {
            note(report, "  The mods folder cannot be created: " + modsDir);
            return 0;
        }

        InstanceState state = loadState(modsDir);
        boolean stateChanged = false;
        int changes = 0;

        for (Entry entry : builds.values()) {
            String modId = entry.info.id;
            File cached = new File(jarsDir(), entry.sha + ".jar");
            if (!cached.isFile()) {
                note(report, "  - " + entry.path + ": fits, but the jar is not downloaded yet");
                continue;
            }
            String fileName = safeFileName(baseName(entry.path));
            Installed record = state.mods.get(modId);

            if (record != null) {
                if (entry.sha.equals(record.sha)) {
                    note(report, "  - " + fileName + ": already installed");
                    continue; // this build was installed before: leave it as it is
                }

                File oldJar = new File(modsDir, record.file);
                File oldDisabled = new File(modsDir, record.file + ".disabled");
                boolean disabled = !oldJar.exists() && oldDisabled.exists();
                boolean removedByUser = !oldJar.exists() && !oldDisabled.exists();
                if (removedByUser) {
                    note(report, "  - " + fileName + ": not installed, you removed this mod earlier");
                    continue; // the user does not want this mod
                }

                // An update replaces the old build, and a mod that the user disabled stays disabled
                File target = new File(modsDir, disabled ? fileName + ".disabled" : fileName);
                if (!copyInto(cached, target)) {
                    note(report, "  - " + fileName + ": could not be copied");
                    continue;
                }
                if (!record.file.equals(fileName)) {
                    oldJar.delete();
                    oldDisabled.delete();
                }
                state.mods.put(modId, installed(fileName, entry));
                stateChanged = true;
                changes++;
                note(report, "  - " + fileName + ": UPDATED" + (disabled ? " (it stays disabled)" : ""));
                continue;
            }

            // Not installed by the launcher yet
            if (hasModFromElsewhere(modsDir, modId)) {
                Log.i(TAG, modId + " is already in " + modsDir + ", it is not replaced");
                note(report, "  - " + fileName + ": not installed, the mods folder already has this mod (\"" + modId + "\")");
                continue;
            }
            File target = new File(modsDir, fileName);
            if (target.exists()) {
                try {
                    if (!entry.sha.equalsIgnoreCase(gitBlobSha(target))) {
                        note(report, "  - " + fileName + ": not installed, a different file with this name is in the mods folder");
                        continue; // a different file of the user
                    }
                } catch (IOException e) {
                    continue;
                }
                state.mods.put(modId, installed(fileName, entry)); // the same file is already there
                stateChanged = true;
                note(report, "  - " + fileName + ": already installed");
                continue;
            }
            if (copyInto(cached, target)) {
                state.mods.put(modId, installed(fileName, entry));
                stateChanged = true;
                changes++;
                Log.i(TAG, "Installed " + fileName + " into " + modsDir);
                note(report, "  - " + fileName + ": INSTALLED");
            } else {
                note(report, "  - " + fileName + ": could not be copied into " + modsDir);
            }
        }
        if (stateChanged) saveState(modsDir, state);
        return changes;
    }

    /** One line for each jar that is not installed into this instance, with the reason. */
    private static void explainSkipped(Index index, Map<String, Entry> builds, String loader, String mcVersion, Report report) {
        for (Entry entry : index.files.values()) {
            if (entry.info == null) continue; // the jar list already says that it is not a mod
            if (builds.get(entry.info.id) == entry) continue; // this one is used
            if (entry.info.supports(loader, mcVersion)) {
                report.add("  - " + entry.path + ": not used, another build of \"" + entry.info.id + "\" fits better");
            } else {
                report.add("  - " + entry.path + ": not installed, " + entry.info.whyNot(loader, mcVersion));
            }
        }
    }

    private static boolean isModLoader(String loader) {
        return ModJarInfo.FABRIC.equals(loader) || ModJarInfo.QUILT.equals(loader)
                || ModJarInfo.FORGE.equals(loader) || ModJarInfo.NEOFORGE.equals(loader);
    }

    /** For each mod id, the newest build that supports the instance. */
    private static Map<String, Entry> bestBuilds(Index index, String loader, String mcVersion) {
        Map<String, Entry> best = new LinkedHashMap<>();
        for (Entry entry : index.files.values()) {
            if (entry.info == null || entry.info.id == null || !entry.info.supports(loader, mcVersion)) continue;
            Entry current = best.get(entry.info.id);
            if (current == null || compareVersions(entry.info.version, current.info.version) > 0) {
                best.put(entry.info.id, entry);
            }
        }
        return best;
    }

    private static Installed installed(String fileName, Entry entry) {
        Installed record = new Installed();
        record.file = fileName;
        record.sha = entry.sha;
        record.path = entry.path;
        return record;
    }

    /** True when the folder has a jar (or a disabled jar) of this mod that the launcher did not install. */
    private static boolean hasModFromElsewhere(File modsDir, String modId) {
        File[] files = modsDir.listFiles();
        if (files == null) return false;
        for (File file : files) {
            String name = file.getName();
            if (!file.isFile() || !(name.endsWith(".jar") || name.endsWith(".jar.disabled"))) continue;
            try {
                if (modId.equals(ModJarInfo.read(file).id)) return true;
            } catch (IOException ignored) {
                // not a readable mod
            }
        }
        return false;
    }

    private static InstanceState loadState(File modsDir) {
        File file = new File(modsDir, STATE_FILE);
        if (!file.isFile()) return new InstanceState();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            InstanceState state = GSON.fromJson(reader, InstanceState.class);
            if (state == null) return new InstanceState();
            if (state.mods == null) state.mods = new LinkedHashMap<>();
            return state;
        } catch (Exception e) {
            return new InstanceState();
        }
    }

    private static void saveState(File modsDir, InstanceState state) {
        try {
            writeText(new File(modsDir, STATE_FILE), GSON.toJson(state));
        } catch (IOException e) {
            Log.w(TAG, "Could not save which mods were installed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ small helpers

    /** Copies through a temporary file, so the game never sees a half written jar. */
    private static boolean copyInto(File source, File target) {
        File part = new File(target.getParentFile(), "." + target.getName() + ".part");
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        } catch (IOException e) {
            part.delete();
            return false;
        }
        if (target.exists() && !target.delete()) {
            part.delete();
            return false;
        }
        if (!part.renameTo(target)) {
            part.delete();
            return false;
        }
        return true;
    }

    private static void writeText(File file, String text) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File part = new File(dir, file.getName() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (file.exists() && !file.delete()) throw new IOException("Cannot replace " + file);
        if (!part.renameTo(file)) throw new IOException("Cannot write " + file);
    }

    private static String readFile(File file) {
        try {
            return new String(readAll(new FileInputStream(file), MAX_LIST_BYTES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static byte[] readAll(InputStream in, long max) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            long total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > max) throw new IOException("The answer is too large");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** A file name that cannot leave the mods folder or confuse a file system. */
    static String safeFileName(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._+\\-]", "_");
        return safe.startsWith(".") ? "_" + safe : safe;
    }

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /** Compares versions such as 1.2.10 and 1.2.9 by their numbers. */
    static int compareVersions(@Nullable String a, @Nullable String b) {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        Matcher first = NUMBER.matcher(a);
        Matcher second = NUMBER.matcher(b);
        while (true) {
            boolean hasFirst = first.find();
            boolean hasSecond = second.find();
            if (!hasFirst && !hasSecond) return a.compareTo(b);
            if (!hasFirst) return -1;
            if (!hasSecond) return 1;
            long x = parseLong(first.group());
            long y = parseLong(second.group());
            if (x != y) return x < y ? -1 : 1;
        }
    }

    private static long parseLong(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
