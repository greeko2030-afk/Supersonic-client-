package net.kdt.pojavlaunch.supersonic;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small client for the public Modrinth API (https://docs.modrinth.com).
 * Used for searching, installing, updating mods/shaders and reading what is installed.
 * Every method blocks: call it from a background thread.
 */
public final class ModrinthClient {
    private static final String BASE = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "SupersonicClient/2.5.0 (Android Minecraft launcher)";

    private ModrinthClient() {}

    public static class Hit {
        public String projectId, slug, title, description, iconUrl, author, imageUrl;
        public long downloads;
        public List<String> categories = new ArrayList<>();
    }

    public static class Installed {
        public File file;
        public String sha1;
        public String projectId, slug, title, iconUrl, versionId;
        public boolean disabled;
    }

    public static class VersionInfo {
        public String id, projectId, versionNumber, fileName, fileUrl, sha1;
        public List<String> requiredProjects = new ArrayList<>();
    }

    // ---------------------------------------------------------------- HTTP

    private static String request(String method, String url, @Nullable String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(25000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = connection.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = stream == null ? "" : Tools.read(stream);
        connection.disconnect();
        if (code >= 400) throw new IOException("Modrinth HTTP " + code);
        return text;
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static JsonObject obj(String json) {
        return Tools.GLOBAL_GSON.fromJson(json, JsonObject.class);
    }

    private static JsonArray arr(String json) {
        return Tools.GLOBAL_GSON.fromJson(json, JsonArray.class);
    }

    private static String str(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static String loaderList(String loader) {
        if (SupersonicProfile.LOADER_QUILT.equals(loader)) return "[\"quilt\",\"fabric\"]";
        return "[\"" + loader + "\"]";
    }

    // ---------------------------------------------------------------- search

    /**
     * @param projectType "mod", "shader" or "modpack"
     * @param category    Modrinth category (e.g. "optimization") or null
     * @param loader      mod loader, only used for mods
     * @param index       relevance | downloads | newest | updated
     */
    public static List<Hit> search(String query, String projectType, @Nullable String category,
                                   @Nullable String mcVersion, @Nullable String loader,
                                   String index, int limit) throws IOException {
        StringBuilder facets = new StringBuilder("[[\"project_type:" + projectType + "\"]");
        if (mcVersion != null && !mcVersion.isEmpty()) facets.append(",[\"versions:").append(mcVersion).append("\"]");
        if ("mod".equals(projectType) && loader != null) {
            if (SupersonicProfile.LOADER_QUILT.equals(loader)) {
                facets.append(",[\"categories:quilt\",\"categories:fabric\"]");
            } else {
                facets.append(",[\"categories:").append(loader).append("\"]");
            }
        }
        if (category != null) facets.append(",[\"categories:").append(category).append("\"]");
        if ("mod".equals(projectType)) facets.append(",[\"client_side:required\",\"client_side:optional\"]");
        facets.append("]");

        String url = BASE + "/search?query=" + enc(query == null ? "" : query)
                + "&facets=" + enc(facets.toString()) + "&index=" + index + "&limit=" + limit;
        JsonArray hits = obj(request("GET", url, null)).getAsJsonArray("hits");
        List<Hit> result = new ArrayList<>();
        if (hits == null) return result;
        for (JsonElement element : hits) {
            JsonObject hit = element.getAsJsonObject();
            Hit h = new Hit();
            h.projectId = str(hit, "project_id");
            h.slug = str(hit, "slug");
            h.title = str(hit, "title");
            h.description = str(hit, "description");
            h.iconUrl = str(hit, "icon_url");
            h.imageUrl = str(hit, "featured_gallery");
            h.author = str(hit, "author");
            JsonElement downloads = hit.get("downloads");
            h.downloads = downloads == null || downloads.isJsonNull() ? 0 : downloads.getAsLong();
            JsonArray cats = hit.getAsJsonArray("display_categories");
            if (cats != null) for (JsonElement c : cats) h.categories.add(c.getAsString());
            result.add(h);
        }
        return result;
    }

    /** Looks projects up by id or slug. */
    public static List<Hit> projects(List<String> idsOrSlugs) throws IOException {
        List<Hit> result = new ArrayList<>();
        if (idsOrSlugs.isEmpty()) return result;
        StringBuilder ids = new StringBuilder("[");
        for (int i = 0; i < idsOrSlugs.size(); i++) {
            if (i > 0) ids.append(',');
            ids.append('"').append(idsOrSlugs.get(i)).append('"');
        }
        ids.append(']');
        JsonArray array = arr(request("GET", BASE + "/projects?ids=" + enc(ids.toString()), null));
        for (JsonElement element : array) {
            JsonObject project = element.getAsJsonObject();
            Hit h = new Hit();
            h.projectId = str(project, "id");
            h.slug = str(project, "slug");
            h.title = str(project, "title");
            h.description = str(project, "description");
            h.iconUrl = str(project, "icon_url");
            result.add(h);
        }
        return result;
    }

    // ---------------------------------------------------------------- versions / install

    private static VersionInfo parseVersion(JsonObject version) {
        VersionInfo info = new VersionInfo();
        info.id = str(version, "id");
        info.projectId = str(version, "project_id");
        info.versionNumber = str(version, "version_number");
        JsonArray files = version.getAsJsonArray("files");
        if (files != null && files.size() > 0) {
            JsonObject file = files.get(0).getAsJsonObject();
            for (JsonElement element : files) {
                JsonObject candidate = element.getAsJsonObject();
                JsonElement primary = candidate.get("primary");
                if (primary != null && !primary.isJsonNull() && primary.getAsBoolean()) {
                    file = candidate;
                    break;
                }
            }
            info.fileName = str(file, "filename");
            info.fileUrl = str(file, "url");
            JsonObject hashes = file.getAsJsonObject("hashes");
            if (hashes != null) info.sha1 = str(hashes, "sha1");
        }
        JsonArray deps = version.getAsJsonArray("dependencies");
        if (deps != null) {
            for (JsonElement element : deps) {
                JsonObject dep = element.getAsJsonObject();
                if ("required".equals(str(dep, "dependency_type")) && str(dep, "project_id") != null) {
                    info.requiredProjects.add(str(dep, "project_id"));
                }
            }
        }
        return info;
    }

    /** Newest compatible version of a project (prefers release builds), or null. */
    @Nullable
    public static VersionInfo latestVersion(String project, @Nullable String loader, String mcVersion) throws IOException {
        StringBuilder url = new StringBuilder(BASE + "/project/" + enc(project) + "/version?game_versions="
                + enc("[\"" + mcVersion + "\"]"));
        if (loader != null) url.append("&loaders=").append(enc(loaderList(loader)));
        JsonArray versions = arr(request("GET", url.toString(), null));
        if (versions == null || versions.size() == 0) return null;
        JsonObject chosen = versions.get(0).getAsJsonObject();
        for (JsonElement element : versions) {
            JsonObject candidate = element.getAsJsonObject();
            if ("release".equals(str(candidate, "version_type"))) {
                chosen = candidate;
                break;
            }
        }
        return parseVersion(chosen);
    }

    /**
     * Installs the newest compatible version of a mod or shader pack, including required dependencies
     * (for example Fabric API for many Fabric mods).
     *
     * @return the number of files that were downloaded
     */
    public static int install(String project, boolean shader, SupersonicProfile profile,
                              Set<String> installedProjectIds, Set<String> visited, int depth) throws IOException {
        if (depth > 3 || !visited.add(project)) return 0;
        String loader = shader ? null : profile.loader;
        VersionInfo version = latestVersion(project, loader, profile.mcVersion);
        if (version == null || version.fileUrl == null || version.fileName == null) {
            throw new IOException("No version of this project is available for Minecraft " + profile.mcVersion
                    + (shader ? "" : " (" + profile.loader + ")"));
        }
        if (version.projectId != null && installedProjectIds.contains(version.projectId)) return 0;

        int downloaded = 0;
        if (!shader) {
            for (String dependency : version.requiredProjects) {
                if (installedProjectIds.contains(dependency)) continue;
                try {
                    downloaded += install(dependency, false, profile, installedProjectIds, visited, depth + 1);
                } catch (IOException ignored) {
                    // A missing dependency should not block the main download
                }
            }
        }

        File dir = shader ? profile.shadersDir() : profile.modsDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File target = new File(dir, version.fileName);
        DownloadUtils.downloadFile(version.fileUrl, target);
        if (version.sha1 != null && !Tools.compareSHA1(target, version.sha1)) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw new IOException("Downloaded file is corrupted, please try again");
        }
        if (version.projectId != null) installedProjectIds.add(version.projectId);
        return downloaded + 1;
    }

    // ---------------------------------------------------------------- installed mods

    public static String sha1(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Lists .jar / .jar.disabled files and identifies them on Modrinth by hash (best effort, works offline too). */
    public static List<Installed> scanInstalled(File dir) {
        List<Installed> result = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jar") || name.endsWith(".jar.disabled"));
        if (files == null || files.length == 0) return result;

        Map<String, Installed> byHash = new HashMap<>();
        for (File file : files) {
            Installed installed = new Installed();
            installed.file = file;
            installed.disabled = file.getName().endsWith(".disabled");
            installed.title = file.getName().replace(".jar.disabled", "").replace(".jar", "");
            try {
                installed.sha1 = sha1(file);
                byHash.put(installed.sha1, installed);
            } catch (IOException ignored) { }
            result.add(installed);
        }

        try {
            JsonObject body = new JsonObject();
            JsonArray hashes = new JsonArray();
            for (String hash : byHash.keySet()) hashes.add(hash);
            body.add("hashes", hashes);
            body.addProperty("algorithm", "sha1");
            JsonObject versions = obj(request("POST", BASE + "/version_files", body.toString()));

            List<String> projectIds = new ArrayList<>();
            for (Map.Entry<String, Installed> entry : byHash.entrySet()) {
                JsonElement element = versions.get(entry.getKey());
                if (element == null || element.isJsonNull()) continue;
                JsonObject version = element.getAsJsonObject();
                entry.getValue().projectId = str(version, "project_id");
                entry.getValue().versionId = str(version, "id");
                if (entry.getValue().projectId != null && !projectIds.contains(entry.getValue().projectId)) {
                    projectIds.add(entry.getValue().projectId);
                }
            }

            Map<String, Hit> projects = new HashMap<>();
            for (Hit hit : projects(projectIds)) projects.put(hit.projectId, hit);
            for (Installed installed : result) {
                Hit hit = installed.projectId == null ? null : projects.get(installed.projectId);
                if (hit == null) continue;
                installed.slug = hit.slug;
                installed.title = hit.title;
                installed.iconUrl = hit.iconUrl;
            }
        } catch (IOException ignored) {
            // Offline: keep file names only
        }
        return result;
    }

    public static Set<String> projectIds(List<Installed> installed) {
        Set<String> ids = new HashSet<>();
        for (Installed item : installed) if (item.projectId != null && !item.disabled) ids.add(item.projectId);
        return ids;
    }

    /** Enables or disables a mod by renaming it (.jar <-> .jar.disabled). */
    public static boolean setEnabled(Installed installed, boolean enabled) {
        String name = installed.file.getName();
        String newName = enabled ? name.replace(".jar.disabled", ".jar")
                : (name.endsWith(".jar") ? name + ".disabled" : name);
        File target = new File(installed.file.getParentFile(), newName);
        if (target.equals(installed.file)) return true;
        boolean ok = installed.file.renameTo(target);
        if (ok) {
            installed.file = target;
            installed.disabled = !enabled;
        }
        return ok;
    }

    /** Updates every identified mod to its newest compatible version. @return number of updated mods */
    public static int updateAll(SupersonicProfile profile, List<Installed> installed) throws IOException {
        JsonArray hashes = new JsonArray();
        Map<String, Installed> byHash = new HashMap<>();
        for (Installed item : installed) {
            if (item.sha1 == null || item.projectId == null) continue;
            hashes.add(item.sha1);
            byHash.put(item.sha1, item);
        }
        if (hashes.size() == 0) return 0;

        JsonObject body = new JsonObject();
        body.add("hashes", hashes);
        body.addProperty("algorithm", "sha1");
        body.add("loaders", arr(loaderList(profile.loader)));
        body.add("game_versions", arr("[\"" + profile.mcVersion + "\"]"));
        JsonObject latest = obj(request("POST", BASE + "/version_files/update", body.toString()));

        int updated = 0;
        for (Map.Entry<String, Installed> entry : byHash.entrySet()) {
            JsonElement element = latest.get(entry.getKey());
            if (element == null || element.isJsonNull()) continue;
            VersionInfo version = parseVersion(element.getAsJsonObject());
            if (version.fileUrl == null || version.fileName == null) continue;
            if (entry.getKey().equalsIgnoreCase(version.sha1)) continue; // already newest

            Installed old = entry.getValue();
            String fileName = old.disabled ? version.fileName + ".disabled" : version.fileName;
            File target = new File(old.file.getParentFile(), fileName);
            DownloadUtils.downloadFile(version.fileUrl, target);
            if (version.sha1 != null && !Tools.compareSHA1(target, version.sha1)) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                continue;
            }
            if (!target.equals(old.file)) {
                //noinspection ResultOfMethodCallIgnored
                old.file.delete();
            }
            updated++;
        }
        return updated;
    }
}
