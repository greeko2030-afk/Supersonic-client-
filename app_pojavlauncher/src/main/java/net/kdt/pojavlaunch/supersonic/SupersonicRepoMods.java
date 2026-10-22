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
 *       "304 Not Modified" (which is not counted against the rate limit) when nothing changed.</li>
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

    /** Checks the repository, then installs into every instance. @return how many mods were installed or updated */
    private static int syncAndInstallAll() {
        try {
            int downloaded = syncRepo();
            Log.i(TAG, "Repository checked, " + downloaded + " new or changed mods");
        } catch (IOException e) {
            Log.w(TAG, "Could not check the repository, using the mods from earlier: " + e.getMessage());
        }
        int installed = 0;
        LauncherProfiles.load();
        if (LauncherProfiles.mainProfileJson == null) return 0;
        for (String key : new ArrayList<>(LauncherProfiles.mainProfileJson.profiles.keySet())) {
            SupersonicProfile profile = SupersonicProfile.of(key);
            if (profile != null) installed += installForProfile(profile);
        }
        return installed;
    }

    static int installForProfile(SupersonicProfile profile) {
        if (!profile.supportsMods()) return 0;
        return installForInstance(profile.modsDir(), profile.loader, profile.mcVersion);
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

    /**
     * Reads the list of the repository, downloads the jars that are new or changed and forgets the jars that
     * were deleted from the repository.
     *
     * @return how many jars were downloaded
     */
    static synchronized int syncRepo() throws IOException {
        Index known = loadIndex();
        File jars = jarsDir();
        if (!jars.isDirectory() && !jars.mkdirs()) throw new IOException("Cannot create " + jars);

        String listUrl = sApiBase + "/repos/" + OWNER + "/" + REPO + "/git/trees/" + BRANCH + "?recursive=1";
        HttpURLConnection connection = open(listUrl, "application/vnd.github+json");
        // Nothing changed since the last time: GitHub answers 304 and it is not counted as a request
        if (known.etag != null && !known.files.isEmpty()) connection.setRequestProperty("If-None-Match", known.etag);
        String body;
        String etag;
        try {
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return 0;
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("GitHub answered HTTP " + code);
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
            if (size > MAX_JAR_BYTES) continue;

            Entry old = known.files.get(path);
            File cached = new File(jars, sha + ".jar");
            // Unchanged: the same git hash, and the jar is still on disk
            if (old != null && sha.equals(old.sha) && (old.error != null || (old.info != null && cached.isFile()))) {
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
                complete = false;
                continue;
            }
            try {
                entry.info = ModJarInfo.read(cached);
            } catch (IOException e) {
                // The jar itself is not a usable mod, so there is no point to download it again
                entry.error = e.getMessage();
                cached.delete();
                Log.w(TAG, "Not a usable mod: " + path + ": " + e.getMessage());
            }
            next.files.put(path, entry);
        }
        // When something could not be downloaded, the list is asked again at the next check
        if (!complete) next.etag = null;

        // Forget the jars that are not in the repository any more (the copies in the instances stay)
        Set<String> keep = new HashSet<>();
        for (Entry entry : next.files.values()) {
            if (entry.info != null) keep.add(entry.sha + ".jar");
        }
        File[] cachedJars = jars.listFiles();
        if (cachedJars != null) {
            for (File cached : cachedJars) {
                if (!keep.contains(cached.getName())) cached.delete();
            }
        }
        saveIndex(next);
        return downloaded;
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

    /**
     * Installs the mods of the repository that support this loader and this Minecraft version
     * into a mods folder.
     *
     * @return how many mods were installed or updated
     */
    static synchronized int installForInstance(File modsDir, String loader, String mcVersion) {
        if (!isModLoader(loader) || mcVersion == null || mcVersion.isEmpty()) return 0;
        Map<String, Entry> builds = bestBuilds(loadIndex(), loader, mcVersion);
        if (builds.isEmpty()) return 0;
        if (!modsDir.isDirectory() && !modsDir.mkdirs()) return 0;

        InstanceState state = loadState(modsDir);
        boolean stateChanged = false;
        int changes = 0;

        for (Entry entry : builds.values()) {
            String modId = entry.info.id;
            File cached = new File(jarsDir(), entry.sha + ".jar");
            if (!cached.isFile()) continue;
            String fileName = safeFileName(baseName(entry.path));
            Installed record = state.mods.get(modId);

            if (record != null) {
                if (entry.sha.equals(record.sha)) continue; // this build was installed before: leave it as it is

                File oldJar = new File(modsDir, record.file);
                File oldDisabled = new File(modsDir, record.file + ".disabled");
                boolean disabled = !oldJar.exists() && oldDisabled.exists();
                boolean removedByUser = !oldJar.exists() && !oldDisabled.exists();
                if (removedByUser) continue; // the user does not want this mod

                // An update replaces the old build, and a mod that the user disabled stays disabled
                File target = new File(modsDir, disabled ? fileName + ".disabled" : fileName);
                if (!copyInto(cached, target)) continue;
                if (!record.file.equals(fileName)) {
                    oldJar.delete();
                    oldDisabled.delete();
                }
                state.mods.put(modId, installed(fileName, entry));
                stateChanged = true;
                changes++;
                continue;
            }

            // Not installed by the launcher yet
            if (hasModFromElsewhere(modsDir, modId)) {
                Log.i(TAG, modId + " is already in " + modsDir + ", it is not replaced");
                continue;
            }
            File target = new File(modsDir, fileName);
            if (target.exists()) {
                try {
                    if (!entry.sha.equalsIgnoreCase(gitBlobSha(target))) continue; // a different file of the user
                } catch (IOException e) {
                    continue;
                }
                state.mods.put(modId, installed(fileName, entry)); // the same file is already there
                stateChanged = true;
                continue;
            }
            if (copyInto(cached, target)) {
                state.mods.put(modId, installed(fileName, entry));
                stateChanged = true;
                changes++;
                Log.i(TAG, "Installed " + fileName + " into " + modsDir);
            }
        }
        if (stateChanged) saveState(modsDir, state);
        return changes;
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






























































































































































































































