package net.kdt.pojavlaunch.supersonic;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The hands of SS Agent: the functions the AI may call. Like a coding assistant that works inside a
 * repository, it can read and change what the launcher owns on this phone: game folder, instances, mods,
 * configs, launcher settings, JVM arguments and logs.
 *
 * <ul>
 *   <li>Reading is automatic. Every change asks the user first (unless full auto mode is on).</li>
 *   <li>Every change is backed up and can be undone ({@link AgentUndo}).</li>
 *   <li>Account data, saved sessions and API keys are never readable or writable by the AI.</li>
 *   <li>Anything the AI reads is cleaned of access tokens before it is sent anywhere.</li>
 * </ul>
 */
public final class AgentTools {

    /** Asks the user whether an action may run. Blocks until answered. */
    public interface Approver {
        /**
         * @param always true for risky actions (downloads from the internet): ask even in full auto mode and
         *               even after "Allow all in this chat"
         */
        boolean approve(String title, String detail, boolean always);
    }

    private static final class UserDenied extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final int MAX_RESULT_CHARS = 3500;
    private static final int MAX_FILE_READ_BYTES = 2 * 1024 * 1024;
    private static final int MAX_WRITE_CHARS = 1_000_000;

    private final Context context;
    private final Approver approver;

    public AgentTools(Context context, Approver approver) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.approver = approver;
    }

    // ------------------------------------------------------------------ tool list

    public static List<AiChat.ToolSpec> specs() {
        return specs(true);
    }

    /** @param web false leaves out the tools that use the internet (web search is turned off in AI settings) */
    public static List<AiChat.ToolSpec> specs(boolean web) {
        List<AiChat.ToolSpec> tools = new ArrayList<>();

        tools.add(tool("get_environment", "Phone, RAM, settings, selected instance, folders. Call first.", schema()));
        tools.add(tool("diagnose",
                "Offline checks: RAM, storage, mods vs game version, missing mods, bad JVM args, crash patterns.",
                schema()));
        tools.add(tool("read_log",
                "Game log. latest_log (default) starts at the last error; or latest_log_tail, crash_report.",
                schema(new String[0], "source", enumProp("Log source", "latest_log", "latest_log_tail", "crash_report"))));
        tools.add(tool("list_dir", "List a folder. Relative paths start in the Minecraft folder.",
                schema(new String[0], "path", prop("string", "Folder, default Minecraft folder"))));
        tools.add(tool("read_file", "Read a text file. Long files are cut; continue with offset.",
                schema(new String[]{"path"},
                        "path", prop("string", "File"),
                        "offset", prop("integer", "Characters to skip"),
                        "max_chars", prop("integer", "Default 4000"))));
        tools.add(tool("search_files", "Find text in files under a folder (case-insensitive).",
                schema(new String[]{"query"},
                        "path", prop("string", "Folder, default Minecraft folder"),
                        "query", prop("string", "Text"),
                        "max_results", prop("integer", "Default 30"))));
        tools.add(tool("list_mods", "Mods of the selected instance: on/off, id, version, fits the game version or not.",
                schema()));
        tools.add(tool("search_modrinth", "Search Modrinth for mods or shader packs that fit the instance.",
                schema(new String[]{"query"},
                        "query", prop("string", "Search text"),
                        "type", enumProp("Default mod", "mod", "shader"))));
        tools.add(tool("list_settings", "Launcher settings and their values.", schema()));

        tools.add(tool("write_file", "Create or overwrite a text file (backed up, JSON checked). Prefer edit_file.",
                schema(new String[]{"path", "content"},
                        "path", prop("string", "File"),
                        "content", prop("string", "Full new content"))));
        tools.add(tool("edit_file", "Replace exact text in a file. Read it first. old_str must be unique unless replace_all.",
                schema(new String[]{"path", "old_str", "new_str"},
                        "path", prop("string", "File"),
                        "old_str", prop("string", "Exact text"),
                        "new_str", prop("string", "Replacement"),
                        "replace_all", prop("boolean", "Default false"))));
        tools.add(tool("delete_path", "Delete a file or folder (backed up).",
                schema(new String[]{"path"}, "path", prop("string", "Path"))));
        tools.add(tool("set_mod_enabled", "Turn a mod on or off (.jar <-> .jar.disabled).",
                schema(new String[]{"file_name", "enabled"},
                        "file_name", prop("string", "Name from list_mods"),
                        "enabled", prop("boolean", "true = on"))));
        tools.add(tool("install_mod", "Install a mod (with dependencies) or a shader pack from Modrinth.",
                schema(new String[]{"project"},
                        "project", prop("string", "Slug, e.g. fabric-api"),
                        "type", enumProp("Default mod", "mod", "shader"))));
        tools.add(tool("set_setting", "Change a launcher setting (keys from list_settings).",
                schema(new String[]{"key", "value"},
                        "key", prop("string", "Key"),
                        "value", prop("string", "New value"))));
        tools.add(tool("set_jvm_argument", "Add, enable, disable or remove a JVM argument.",
                schema(new String[]{"argument", "action"},
                        "argument", prop("string", "e.g. -XX:+UseG1GC"),
                        "action", enumProp("Action", "add", "enable", "disable", "remove"))));
        tools.add(tool("apply_safe_fixes", "Run the safe, undoable automatic fixes found by diagnose.", schema()));

        // shader packs
        JsonObject fileItem = new JsonObject();
        fileItem.addProperty("type", "object");
        JsonObject fileProps = new JsonObject();
        fileProps.add("path", prop("string", "Path inside the pack, e.g. shaders/final.fsh"));
        fileProps.add("content", prop("string", "Full text of the file"));
        fileItem.add("properties", fileProps);
        JsonArray fileRequired = new JsonArray();
        fileRequired.add(new com.google.gson.JsonPrimitive("path"));
        fileRequired.add(new com.google.gson.JsonPrimitive("content"));
        fileItem.add("required", fileRequired);
        JsonObject filesProp = new JsonObject();
        filesProp.addProperty("type", "array");
        filesProp.addProperty("description", "All files of the pack (shaders/*.vsh, *.fsh, shaders.properties, lib/*.glsl)");
        filesProp.add("items", fileItem);
        tools.add(tool("create_shader_pack",
                "Create a whole shader pack in shaderpacks/ in one step (folder, or zip). Text files under shaders/ only.",
                schema(new String[]{"name", "files"},
                        "name", prop("string", "Pack name, e.g. LiteRealism"),
                        "files", filesProp,
                        "zip", prop("boolean", "Make a .zip instead of a folder, default false"))));
        tools.add(tool("check_shader",
                "Static check of shader code (brackets, #version, main, includes). Path is relative to shaderpacks/.",
                schema(new String[]{"path"}, "path", prop("string", "Pack folder, .zip or one file"))));
        tools.add(tool("list_shader_packs", "List the shader packs in shaderpacks/.", schema()));

        if (web) {
            tools.add(tool("web_search", "Search the web. Returns titles, links and snippets.",
                    schema(new String[]{"query"},
                            "query", prop("string", "Search words"),
                            "max_results", prop("integer", "Default 6"))));
            tools.add(tool("web_fetch", "Read a web page or a text file (e.g. raw shader code) as plain text.",
                    schema(new String[]{"url"},
                            "url", prop("string", "http(s) address"),
                            "offset", prop("integer", "Characters to skip"),
                            "max_chars", prop("integer", "Default 6000"))));
            tools.add(tool("download_file",
                    "Download a .zip shader or resource pack into shaderpacks/ or resourcepacks/. Always asks the user.",
                    schema(new String[]{"url", "path"},
                            "url", prop("string", "http(s) address"),
                            "path", prop("string", "Target, e.g. shaderpacks/Name.zip"))));
        }
        return tools;
    }

    private static AiChat.ToolSpec tool(String name, String description, JsonObject parameters) {
        return new AiChat.ToolSpec(name, description, parameters);
    }

    private static JsonObject prop(String type, String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("description", description);
        return o;
    }

    private static JsonObject enumProp(String description, String... values) {
        JsonObject o = prop("string", description);
        JsonArray array = new JsonArray();
        for (String value : values) array.add(new com.google.gson.JsonPrimitive(value));
        o.add("enum", array);
        return o;
    }

    /** schema() = no arguments; schema(required, name1, prop1, name2, prop2...) */
    private static JsonObject schema(String[] required, Object... namesAndProps) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        for (int i = 0; i + 1 < namesAndProps.length; i += 2) {
            properties.add((String) namesAndProps[i], (JsonObject) namesAndProps[i + 1]);
        }
        root.add("properties", properties);
        if (required.length > 0) {
            JsonArray array = new JsonArray();
            for (String name : required) array.add(new com.google.gson.JsonPrimitive(name));
            root.add("required", array);
        }
        return root;
    }

    private static JsonObject schema() {
        return schema(new String[0]);
    }

    // ------------------------------------------------------------------ running a call

    /** Never throws: errors come back as text for the model. */
    public String execute(AiChat.ToolCall call) {
        try {
            return clip(dispatch(call.name, call.args), MAX_RESULT_CHARS);
        } catch (UserDenied e) {
            return "The user denied this action. Do not try it again; ask what they would like instead.";
        } catch (IOException | RuntimeException e) {
            String message = e.getMessage();
            return "ERROR: " + (message == null || message.isEmpty() ? e.getClass().getSimpleName() : message);
        }
    }

    /** A short, human readable line for the chat ("read_file options.txt"). */
    public static String describeCall(AiChat.ToolCall call) {
        StringBuilder out = new StringBuilder(call.name);
        for (String key : new String[]{"path", "file_name", "project", "query", "key", "argument", "source"}) {
            if (call.args.has(key) && call.args.get(key).isJsonPrimitive()) {
                out.append(' ').append(clip(call.args.get(key).getAsString(), 80));
                if ("key".equals(key) && call.args.has("value")) {
                    out.append(" = ").append(clip(call.args.get("value").getAsString(), 40));
                }
                break;
            }
        }
        return out.toString();
    }

    private String dispatch(String name, JsonObject args) throws IOException {
        switch (name) {
            case "get_environment":
                return AgentAi.environment(context);
            case "diagnose":
                return diagnose();
            case "read_log":
                return readLog(str(args, "source", "latest_log"));
            case "list_dir":
                return listDir(str(args, "path", ""));
            case "read_file":
                return readFile(requireStr(args, "path"), num(args, "offset", 0), num(args, "max_chars", 4000));
            case "search_files":
                return searchFiles(str(args, "path", ""), requireStr(args, "query"), num(args, "max_results", 30));
            case "list_mods":
                return listMods();
            case "search_modrinth":
                return searchModrinth(requireStr(args, "query"), str(args, "type", "mod"));
            case "list_settings":
                return listSettings();
            case "write_file":
                return writeFile(requireStr(args, "path"), requireStr(args, "content"));
            case "edit_file":
                return editFile(requireStr(args, "path"), requireStr(args, "old_str"),
                        str(args, "new_str", ""), bool(args, "replace_all", false));
            case "delete_path":
                return deletePath(requireStr(args, "path"));
            case "set_mod_enabled":
                return setModEnabled(requireStr(args, "file_name"), bool(args, "enabled", true));
            case "install_mod":
                return installMod(requireStr(args, "project"), str(args, "type", "mod"));
            case "create_shader_pack":
                return createShaderPack(args);
            case "check_shader":
                return checkShader(requireStr(args, "path"));
            case "list_shader_packs":
                return listShaderPacks();
            case "web_search":
                return webSearch(requireStr(args, "query"), num(args, "max_results", 6));
            case "web_fetch":
                return webFetch(requireStr(args, "url"), num(args, "offset", 0), num(args, "max_chars", 6000));
            case "download_file":
                return downloadFile(requireStr(args, "url"), requireStr(args, "path"));
            case "set_setting":
                return setSetting(requireStr(args, "key"), requireStr(args, "value"));
            case "set_jvm_argument":
                return setJvmArgument(requireStr(args, "argument"), requireStr(args, "action"));
            case "apply_safe_fixes":
                return applySafeFixes();
            default:
                throw new IOException("Unknown tool: " + name);
        }
    }

    // ------------------------------------------------------------------ approval

    private void approve(String title, String detail) throws UserDenied {
        if (AiConfig.autoApprove(context)) return;
        if (approver == null || !approver.approve(title, detail, false)) throw new UserDenied();
    }

    /** For risky actions: the user is always asked. */
    private void approveAlways(String title, String detail) throws UserDenied {
        if (approver == null || !approver.approve(title, detail, true)) throw new UserDenied();
    }

    // ------------------------------------------------------------------ sandbox

    private static File canonical(String path) throws IOException {
        return new File(path).getCanonicalFile();
    }

    private static boolean isInside(File file, File root) {
        String f = file.getPath();
        String r = root.getPath();
        return f.equals(r) || f.startsWith(r.endsWith(File.separator) ? r : r + File.separator);
    }

    /**
     * Turns what the model wrote into a real file, inside the launcher's folders only.
     * Relative paths start in the Minecraft folder.
     */
    File resolve(String path) throws IOException {
        String text = path == null ? "" : path.trim();
        File file = text.startsWith("/") ? new File(text) : new File(Tools.DIR_GAME_NEW, text);
        File resolved = file.getCanonicalFile();

        boolean allowed = false;
        for (String root : new String[]{Tools.DIR_GAME_HOME, Tools.DIR_DATA}) {
            if (root != null && isInside(resolved, canonical(root))) allowed = true;
        }
        if (!allowed) {
            throw new IOException("Access denied: " + text + " is outside the launcher's folders");
        }
        if (isProtected(resolved)) {
            throw new IOException("Access denied: " + resolved.getName() + " holds account data or secrets");
        }
        return resolved;
    }

    private static boolean isProtected(File resolved) throws IOException {
        if (Tools.DIR_DATA != null) {
            File data = canonical(Tools.DIR_DATA);
            for (String secret : new String[]{"accounts", "shared_prefs", "databases", "no_backup"}) {
                if (isInside(resolved, new File(data, secret))) return true;
            }
        }
        if (Tools.DIR_ACCOUNT_NEW != null && isInside(resolved, canonical(Tools.DIR_ACCOUNT_NEW))) return true;
        String name = resolved.getName().toLowerCase(Locale.ROOT);
        return name.contains("token") || name.contains("secret") || name.contains("credential")
                || name.contains("password") || name.equals("launcher_accounts.json");
    }

    private static boolean isInsideBackups(File resolved) throws IOException {
        return Tools.DIR_GAME_HOME != null && isInside(resolved, new File(canonical(Tools.DIR_GAME_HOME), "ss_agent_backups"));
    }

    // ------------------------------------------------------------------ read tools

    private String diagnose() {
        StringBuilder out = new StringBuilder();
        for (AgentEngine.Finding finding : AgentEngine.scan(context)) {
            out.append("- [").append(AgentEngine.severityLabel(finding.severity)).append("] ")
                    .append(finding.title).append(": ").append(finding.detail);
            if (finding.fixId != null) out.append("\n  (can be fixed automatically: ").append(finding.fixLabel).append(")");
            out.append('\n');
        }
        out.append("\nUse apply_safe_fixes to run the automatic fixes.");
        return out.toString();
    }

    private String readLog(String source) {
        if ("crash_report".equals(source)) {
            File[] reports = new File(Tools.DIR_HOME_CRASH).listFiles();
            if (reports == null || reports.length == 0) return "There are no crash reports.";
            File newest = reports[0];
            for (File report : reports) if (report.lastModified() > newest.lastModified()) newest = report;
            String text = readText(newest, 0, 14000);
            return "Crash report " + newest.getName() + ":\n" + AgentAi.redact(text == null ? "(unreadable)" : text);
        }
        if ("latest_log_tail".equals(source)) {
            String tail = AgentEngine.readLogTail(64 * 1024);
            if (tail == null) return "There is no log yet: the game has not been started.";
            return AgentAi.redact(tail.substring(Math.max(0, tail.length() - 6000)));
        }
        String log = AgentEngine.readLogTail(256 * 1024);
        if (log == null || log.trim().isEmpty()) return "There is no log yet: the game has not been started.";
        return AgentAi.redact(AgentAi.focusOnProblem(log));
    }

    private String listDir(String path) throws IOException {
        File dir = path.trim().isEmpty() ? resolve(Tools.DIR_GAME_NEW) : resolve(path);
        if (!dir.isDirectory()) throw new IOException(dir.getPath() + " is not a folder");
        File[] children = dir.listFiles();
        if (children == null) return dir.getPath() + " cannot be listed";
        Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        StringBuilder out = new StringBuilder(dir.getPath()).append(" (").append(children.length).append(" entries)\n");
        int shown = 0;
        for (File child : children) {
            if (shown++ >= 120) {
                out.append("... ").append(children.length - 120).append(" more\n");
                break;
            }
            if (isProtected(child.getCanonicalFile())) continue;
            if (child.isDirectory()) out.append("[dir]  ").append(child.getName()).append("/\n");
            else out.append("[file] ").append(child.getName()).append("  ").append(SupersonicUi.formatBytes(child.length())).append('\n');
        }
        return out.toString();
    }

    private String readFile(String path, int offset, int maxChars) throws IOException {
        File file = resolve(path);
        if (!file.isFile()) throw new IOException(file.getPath() + " is not a file");
        int limit = Math.max(200, Math.min(maxChars, 8000));
        String text = readText(file, Math.max(0, offset), limit);
        if (text == null) return file.getName() + " is a binary file (" + SupersonicUi.formatBytes(file.length()) + "), it cannot be read as text.";
        String note = "";
        if (file.length() > (long) offset + limit) {
            note = "\n[more content follows: file is " + SupersonicUi.formatBytes(file.length()) + "; use offset=" + (offset + limit) + "]";
        }
        return AgentAi.redactSecrets(text) + note;
    }

    /** @return the text, or null if the file is binary */
    private static String readText(File file, int offset, int maxChars) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(file.length(), MAX_FILE_READ_BYTES)];
            int read = 0, n;
            while (read < buffer.length && (n = in.read(buffer, read, buffer.length - read)) > 0) read += n;
            for (int i = 0; i < Math.min(read, 8000); i++) if (buffer[i] == 0) return null;
            String all = new String(buffer, 0, read, StandardCharsets.UTF_8);
            if (offset >= all.length()) return "";
            return all.substring(offset, Math.min(all.length(), offset + maxChars));
        } catch (IOException e) {
            return null;
        }
    }

    private String searchFiles(String path, String query, int maxResults) throws IOException {
        File root = path.trim().isEmpty() ? resolve(Tools.DIR_GAME_NEW) : resolve(path);
        if (query.isEmpty()) throw new IOException("query is empty");
        List<String> hits = new ArrayList<>();
        int[] scanned = {0};
        searchIn(root, root, query.toLowerCase(Locale.ROOT), Math.max(1, Math.min(maxResults, 100)), 0, hits, scanned);
        if (hits.isEmpty()) return "No match in " + scanned[0] + " files.";
        StringBuilder out = new StringBuilder();
        for (String hit : hits) out.append(hit).append('\n');
        return out.toString();
    }

    private void searchIn(File base, File file, String query, int max, int depth, List<String> hits, int[] scanned)
            throws IOException {
        if (hits.size() >= max || scanned[0] > 3000 || depth > 6) return;
        if (isProtected(file.getCanonicalFile()) || isInsideBackups(file.getCanonicalFile())) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return;
            Arrays.sort(children);
            for (File child : children) searchIn(base, child, query, max, depth + 1, hits, scanned);
            return;
        }
        if (file.length() > MAX_FILE_READ_BYTES) return;
        scanned[0]++;
        String text = readText(file, 0, MAX_FILE_READ_BYTES);
        if (text == null) return;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length && hits.size() < max; i++) {
            if (lines[i].toLowerCase(Locale.ROOT).contains(query)) {
                String relative = file.getPath().startsWith(base.getPath())
                        ? file.getPath().substring(base.getPath().length()).replaceFirst("^/", "") : file.getPath();
                hits.add((relative.isEmpty() ? file.getName() : relative) + ":" + (i + 1) + ": "
                        + AgentAi.redactSecrets(clip(lines[i].trim(), 200)));
            }
        }
    }

    private String listMods() {
        SupersonicProfile profile = SupersonicProfile.current();
        if (profile == null) return "No instance is selected.";
        File[] files = profile.modsDir().listFiles();
        if (files == null || files.length == 0) return "The mods folder of \"" + profile.displayName() + "\" is empty.";
        Arrays.sort(files);
        StringBuilder out = new StringBuilder("Instance \"").append(profile.displayName()).append("\": Minecraft ")
                .append(profile.mcVersion).append(", ").append(profile.loader).append('\n');
        int shown = 0;
        for (File file : files) {
            String name = file.getName();
            boolean on = name.endsWith(".jar");
            if (!on && !name.endsWith(".jar.disabled")) continue;
            if (shown++ >= 80) {
                out.append("... more\n");
                break;
            }
            out.append(on ? "[on]  " : "[off] ").append(name).append("  ").append(SupersonicUi.formatBytes(file.length()));
            try {
                ModJarInfo info = ModJarInfo.read(file);
                if (info.id != null) out.append("  id=").append(info.id);
                if (info.version != null) out.append(" v").append(info.version);
                if (on && profile.supportsMods() && !info.serverOnly && !info.variants.isEmpty()) {
                    if (info.supports(profile.loader, profile.mcVersion)) {
                        out.append("  fits");
                    } else {
                        out.append("  DOES NOT FIT: ").append(info.whyNot(profile.loader, profile.mcVersion));
                    }
                }
            } catch (IOException | RuntimeException e) {
                out.append("  (no readable mod info)");
            }
            out.append('\n');
        }
        return out.toString();
    }

    private String searchModrinth(String query, String type) throws IOException {
        SupersonicProfile profile = SupersonicProfile.current();
        String mc = profile == null ? null : profile.mcVersion;
        boolean shader = "shader".equals(type);
        String loader = !shader && profile != null && profile.supportsMods() ? profile.loader : null;
        List<ModrinthClient.Hit> hits = ModrinthClient.search(query, shader ? "shader" : "mod", null, mc, loader, "relevance", 8);
        if (hits.isEmpty()) return shader ? "No shader packs found." : "No mods found.";
        StringBuilder out = new StringBuilder();
        for (ModrinthClient.Hit hit : hits) {
            out.append("- ").append(hit.title).append(" (slug: ").append(hit.slug).append("): ")
                    .append(clip(hit.description == null ? "" : hit.description, 120)).append('\n');
        }
        return out.toString();
    }

    private String listSettings() throws IOException {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null) throw new IOException("Settings are not loaded yet");
        Map<String, ?> all = prefs.getAll();
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (isProtectedSetting(entry.getKey())) continue;
            Object value = entry.getValue();
            String type = value == null ? "null" : value.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            sorted.put(entry.getKey(), type + " = " + clip(String.valueOf(value), 120));
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            out.append(entry.getKey()).append(" (").append(entry.getValue()).append(")\n");
        }
        return out.toString();
    }

    private static boolean isProtectedSetting(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.startsWith("ss_ai_") || k.startsWith("ss_agent_") || k.contains("token") || k.contains("password")
                || k.contains("secret") || k.contains("apikey") || k.contains("api_key");
    }

    // ------------------------------------------------------------------ write tools

    private String writeFile(String path, String content) throws IOException {
        File file = resolve(path);
        if (isInsideBackups(file)) throw new IOException("The backup folder cannot be changed");
        if (file.isDirectory()) throw new IOException(file.getPath() + " is a folder");
        if (content.length() > MAX_WRITE_CHARS) throw new IOException("The content is too large (limit 1,000,000 characters)");
        if (file.isFile() && readText(file, 0, 10) == null && file.length() > 0) {
            throw new IOException(file.getName() + " is a binary file and cannot be overwritten with text");
        }
        validateText(file, content);

        String detail = (file.exists() ? "Overwrites " + SupersonicUi.formatBytes(file.length()) : "Creates a new file")
                + ":\n" + file.getPath() + "\n\n" + clip(content, 600);
        approve("Write file?", detail);

        AgentUndo.beforeModify(context, file);
        writeText(file, content);
        afterChange(file);
        return "Wrote " + file.getPath() + " (" + content.length() + " characters). A backup was saved; the user can undo.";
    }

    private String editFile(String path, String oldStr, String newStr, boolean replaceAll) throws IOException {
        File file = resolve(path);
        if (isInsideBackups(file)) throw new IOException("The backup folder cannot be changed");
        if (!file.isFile()) throw new IOException(file.getPath() + " is not a file");
        if (oldStr.isEmpty()) throw new IOException("old_str is empty");
        String text = readText(file, 0, MAX_FILE_READ_BYTES);
        if (text == null) throw new IOException(file.getName() + " is a binary file");
        if (file.length() > MAX_FILE_READ_BYTES) throw new IOException(file.getName() + " is too large to edit safely");

        int first = text.indexOf(oldStr);
        if (first < 0) throw new IOException("old_str was not found in the file. Read the file again and copy the text exactly.");
        int count = 0;
        for (int i = first; i >= 0; i = text.indexOf(oldStr, i + oldStr.length())) count++;
        if (count > 1 && !replaceAll) {
            throw new IOException("old_str appears " + count + " times. Add more surrounding text to make it unique, or set replace_all.");
        }
        String result = replaceAll ? text.replace(oldStr, newStr)
                : text.substring(0, first) + newStr + text.substring(first + oldStr.length());
        validateText(file, result);

        approve("Edit file?", file.getPath() + "\n\n- " + clip(oldStr, 400) + "\n+ " + clip(newStr, 400)
                + (count > 1 ? "\n\n(" + count + " places)" : ""));

        AgentUndo.beforeModify(context, file);
        writeText(file, result);
        afterChange(file);
        return "Edited " + file.getPath() + " (" + count + " replacement" + (count == 1 ? "" : "s") + "). The user can undo.";
    }

    private String deletePath(String path) throws IOException {
        File file = resolve(path);
        if (isInsideBackups(file)) throw new IOException("The backup folder cannot be changed");
        if (!file.exists()) throw new IOException(file.getPath() + " does not exist");
        for (String root : new String[]{Tools.DIR_GAME_HOME, Tools.DIR_GAME_NEW, Tools.DIR_DATA}) {
            if (root != null && file.equals(canonical(root))) throw new IOException("Refusing to delete a launcher root folder");
        }
        long size = AgentUndo.sizeOf(file);
        approve("Delete " + (file.isDirectory() ? "folder" : "file") + "?",
                file.getPath() + "\n" + SupersonicUi.formatBytes(size) + " (a backup is kept for undo)");
        AgentUndo.beforeModify(context, file);
        AgentUndo.deleteRecursive(file);
        afterChange(file);
        return "Deleted " + file.getPath();
    }

    private String setModEnabled(String fileName, boolean enabled) throws IOException {
        SupersonicProfile profile = SupersonicProfile.current();
        if (profile == null) throw new IOException("No instance is selected");
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IOException("Use the plain file name from list_mods");
        }
        File dir = profile.modsDir();
        File from = new File(dir, fileName);
        if (!from.isFile()) throw new IOException(fileName + " is not in the mods folder");
        File to;
        if (enabled) {
            if (!fileName.endsWith(".jar.disabled")) return fileName + " is already on";
            to = new File(dir, fileName.substring(0, fileName.length() - ".disabled".length()));
        } else {
            if (!fileName.endsWith(".jar")) return fileName + " is already off";
            to = new File(dir, fileName + ".disabled");
        }
        if (to.exists()) throw new IOException(to.getName() + " already exists");
        approve(enabled ? "Turn mod on?" : "Turn mod off?", fileName + "\nin " + profile.displayName());
        if (!from.renameTo(to)) throw new IOException("Could not rename " + fileName);
        AgentUndo.recordRename(context, from, to);
        return (enabled ? "Turned on " : "Turned off ") + fileName + ". The user can undo.";
    }

    private String installMod(String project, String type) throws IOException {
        boolean shader = "shader".equals(type);
        SupersonicProfile profile = SupersonicProfile.current();
        if (profile == null) throw new IOException("No instance is selected");
        if (!shader && !profile.supportsMods()) throw new IOException("The selected instance has no mod loader");
        String slug = project.trim().toLowerCase(Locale.ROOT);
        if (slug.isEmpty() || slug.contains("/") || slug.contains(" ")) throw new IOException("Use the Modrinth slug, e.g. fabric-api");
        approve(shader ? "Install shader pack from Modrinth?" : "Install mod from Modrinth?",
                slug + "\ninto " + profile.displayName() + " (Minecraft " + profile.mcVersion
                        + (shader ? ")" : ", " + profile.loader + "), with required dependencies"));

        File dir = shader ? profile.shadersDir() : profile.modsDir();
        Set<String> before = fileNames(dir);
        Set<String> installed = shader ? new HashSet<String>() : ModrinthClient.projectIds(ModrinthClient.scanInstalled(dir));
        ModrinthClient.install(slug, shader, profile, installed, new HashSet<String>(), 0);
        Set<String> added = fileNames(dir);
        added.removeAll(before);
        List<String> names = new ArrayList<>(added);
        Collections.sort(names);
        for (String name : names) AgentUndo.recordCreated(context, new File(dir, name));
        return names.isEmpty() ? slug + " is already installed" : "Installed " + joinWith(", ", names) + ". The user can undo.";
    }

    private String setSetting(String key, String value) throws IOException {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null) throw new IOException("Settings are not loaded yet");
        if (isProtectedSetting(key)) throw new IOException("This setting is protected");
        Map<String, ?> all = prefs.getAll();
        if (!all.containsKey(key)) throw new IOException("Unknown setting: " + key + ". Use list_settings to see the keys.");
        Object old = all.get(key);

        Object parsed;
        try {
            if (old instanceof Boolean) {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IOException("Expected true or false");
                parsed = Boolean.parseBoolean(value);
            } else if (old instanceof Integer) {
                parsed = Integer.parseInt(value.trim());
            } else if (old instanceof Long) {
                parsed = Long.parseLong(value.trim());
            } else if (old instanceof Float) {
                parsed = Float.parseFloat(value.trim());
            } else if (old instanceof String) {
                parsed = value;
            } else {
                throw new IOException("This kind of setting cannot be changed here");
            }
        } catch (NumberFormatException e) {
            throw new IOException("\"" + value + "\" is not a valid value for " + key);
        }

        approve("Change setting?", key + ": " + clip(String.valueOf(old), 100) + "  →  " + clip(value, 100));
        AgentUndo.recordPref(context, key, old);
        SharedPreferences.Editor editor = prefs.edit();
        if (parsed instanceof Boolean) editor.putBoolean(key, (Boolean) parsed);
        else if (parsed instanceof Integer) editor.putInt(key, (Integer) parsed);
        else if (parsed instanceof Long) editor.putLong(key, (Long) parsed);
        else if (parsed instanceof Float) editor.putFloat(key, (Float) parsed);
        else editor.putString(key, (String) parsed);
        editor.apply();
        LauncherPreferences.loadPreferences(context);
        return "Set " + key + " = " + value + " (was " + old + "). The user can undo.";
    }

    private String setJvmArgument(String argument, String action) throws IOException {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null) throw new IOException("Settings are not loaded yet");
        String arg = argument.trim();
        if (arg.isEmpty() || arg.contains("\n")) throw new IOException("Invalid argument");
        if (!Arrays.asList("add", "enable", "disable", "remove").contains(action)) {
            throw new IOException("action must be add, enable, disable or remove");
        }

        List<JvmArgsStore.Item> items = JvmArgsStore.load(prefs);
        JvmArgsStore.Item found = null;
        for (JvmArgsStore.Item item : items) if (item.arg != null && item.arg.trim().equals(arg)) found = item;

        switch (action) {
            case "add":
                if (found == null) items.add(new JvmArgsStore.Item(arg, true));
                else found.enabled = true;
                break;
            case "enable":
                if (found == null) throw new IOException(arg + " is not in the JVM argument list");
                found.enabled = true;
                break;
            case "disable":
                if (found == null) throw new IOException(arg + " is not in the JVM argument list");
                found.enabled = false;
                break;
            default:
                if (found == null) throw new IOException(arg + " is not in the JVM argument list");
                items.remove(found);
        }

        approve("Change JVM arguments?", action + " " + arg);
        Map<String, ?> all = prefs.getAll();
        AgentUndo.recordPref(context, JvmArgsStore.PREF_ARGS, all.get(JvmArgsStore.PREF_ARGS));
        AgentUndo.recordPref(context, JvmArgsStore.PREF_LIST, all.get(JvmArgsStore.PREF_LIST));
        JvmArgsStore.save(prefs, items);
        LauncherPreferences.loadPreferences(context);
        return "Done: " + action + " " + arg + ". The user can undo.";
    }

    private String applySafeFixes() {
        List<AgentEngine.Finding> findings = AgentEngine.scan(context);
        List<String> titles = new ArrayList<>();
        for (AgentEngine.Finding finding : findings) {
            if (finding.fixId != null && finding.severity >= AgentEngine.WARNING && !AgentEngine.FIX_CLEAN.equals(finding.fixId)) {
                titles.add(finding.title);
            }
        }
        if (titles.isEmpty()) return "There is nothing the safe automatic fixes can do.";
        try {
            approve("Apply safe fixes?", joinWith("\n", titles));
        } catch (UserDenied e) {
            return "The user denied this action.";
        }
        List<String> results = AgentEngine.applySafeFixes(context, findings);
        return results.isEmpty() ? "Nothing was changed." : joinWith("\n", results);
    }

    // ------------------------------------------------------------------ helpers

    // ------------------------------------------------------------------ web

    private void requireWeb() throws IOException {
        if (!AiConfig.webEnabled(context)) throw new IOException("Web access is turned off in AI settings");
    }

    private String webSearch(String query, int maxResults) throws IOException {
        requireWeb();
        return WebAccess.search(query, maxResults, AiConfig.searchKey(context));
    }

    private String webFetch(String url, int offset, int maxChars) throws IOException {
        requireWeb();
        return WebAccess.fetchPage(url, Math.max(0, offset), maxChars);
    }

    /** Only .zip shader / resource packs, only into their folders. Never mods or other code. The user is always asked. */
    private String downloadFile(String url, String path) throws IOException {
        requireWeb();
        File target = resolve(path);
        boolean inShaders = isInside(target, canonical(shaderpacksDir().getPath()));
        boolean inResources = isInside(target, canonical(resourcepacksDir().getPath()));
        if (!inShaders && !inResources) {
            throw new IOException("Downloads are only allowed into shaderpacks/ or resourcepacks/ (mods come from install_mod)");
        }
        if (!extensionOf(target.getName()).equals("zip") || target.isDirectory()) {
            throw new IOException("Only .zip shader or resource packs can be downloaded");
        }
        WebAccess.checkUrl(new URL(url)); // refuse bad addresses before the user is asked

        approveAlways("Download file from the internet?", url + "\n\nSave as: " + target.getPath()
                + "\n\nOnly a .zip pack, up to 150 MB. You are always asked for downloads.");

        AgentUndo.beforeModify(context, target);
        long size = WebAccess.download(url, target);
        byte[] head = new byte[4];
        try (InputStream in = new FileInputStream(target)) {
            if (in.read(head) < 4 || head[0] != 'P' || head[1] != 'K') {
                AgentUndo.deleteRecursive(target);
                throw new IOException("The downloaded file is not a zip file, so it was removed");
            }
        }
        return "Downloaded " + target.getName() + " (" + SupersonicUi.formatBytes(size) + ") to " + target.getPath()
                + ". The user can undo.";
    }

    // ------------------------------------------------------------------ shader packs

    private File shaderpacksDir() {
        SupersonicProfile profile = SupersonicProfile.current();
        return profile != null ? profile.shadersDir() : new File(Tools.DIR_GAME_NEW, "shaderpacks");
    }

    private File resourcepacksDir() {
        SupersonicProfile profile = SupersonicProfile.current();
        return profile != null ? profile.resourcePacksDir() : new File(Tools.DIR_GAME_NEW, "resourcepacks");
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String createShaderPack(JsonObject args) throws IOException {
        String name = ShaderPacks.cleanName(requireStr(args, "name"));
        boolean zip = bool(args, "zip", false);
        if (!args.has("files") || !args.get("files").isJsonArray()) {
            throw new IOException("Missing argument: files (a list of {path, content})");
        }
        List<ShaderPacks.PackFile> raw = new ArrayList<>();
        for (JsonElement element : args.getAsJsonArray("files")) {
            if (!element.isJsonObject()) throw new IOException("Every file must be an object with path and content");
            JsonObject file = element.getAsJsonObject();
            raw.add(new ShaderPacks.PackFile(requireStr(file, "path"), requireStr(file, "content")));
        }
        List<ShaderPacks.PackFile> files = ShaderPacks.validate(raw);

        Set<String> allPaths = new HashSet<>();
        for (ShaderPacks.PackFile file : files) allPaths.add(file.path.toLowerCase(Locale.ROOT));
        StringBuilder problems = new StringBuilder();
        int problemCount = 0;
        long chars = 0;
        for (ShaderPacks.PackFile file : files) {
            chars += file.content.length();
            for (String issue : ShaderPacks.lint(file.path, file.content, allPaths)) {
                problems.append(file.path).append(": ").append(issue).append('\n');
                problemCount++;
            }
        }

        File target = resolve(new File(shaderpacksDir(), zip ? name + ".zip" : name).getPath());
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < Math.min(files.size(), 12); i++) list.append("  ").append(files.get(i).path).append('\n');
        if (files.size() > 12) list.append("  … ").append(files.size() - 12).append(" more\n");
        approve("Create shader pack?", (target.exists() ? "Replaces: " : "New: ") + target.getPath() + "\n"
                + files.size() + " files, " + (chars / 1024 + 1) + " KB" + (zip ? ", as zip" : "") + "\n" + list
                + (problemCount > 0 ? "\nStatic checks found " + problemCount + " problem(s)." : ""));

        AgentUndo.beforeModify(context, target);
        if (target.isDirectory()) AgentUndo.deleteRecursive(target); // no leftovers of an older version
        File written = zip ? ShaderPacks.writeZip(shaderpacksDir(), name, files)
                : ShaderPacks.writeFolder(shaderpacksDir(), name, files);

        StringBuilder out = new StringBuilder("Created " + written.getPath() + " with " + files.size() + " files. "
                + "The user can undo.\n");
        if (problemCount == 0) {
            out.append("Static checks found no problems. The shader is only compiled when the game loads it, so the user must test it: "
                    + "Options > Video Settings > Shader Packs.");
        } else {
            out.append("Static checks found problems:\n").append(problems)
                    .append("Fix them with edit_file (folder packs) and check again with check_shader.");
        }
        return out.toString();
    }

    private String checkShader(String path) throws IOException {
        File packs = canonical(shaderpacksDir().getPath());
        String p = path.trim();
        File requested = p.startsWith("/") ? new File(p) : new File(packs, p);
        File target = resolve(requested.getPath());
        if (!isInside(target, packs) || target.equals(packs)) {
            throw new IOException("Give a pack folder, a .zip or a file inside the shaderpacks folder");
        }
        if (!target.exists()) throw new IOException(target.getPath() + " does not exist");

        Map<String, String> texts = new TreeMap<>(); // path inside the pack -> text, only what is checked
        Set<String> allPaths = new HashSet<>();
        if (target.isFile() && extensionOf(target.getName()).equals("zip")) {
            try (ZipFile zip = new ZipFile(target)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || entry.getSize() > ShaderPacks.MAX_FILE_CHARS) continue;
                    String entryPath = entry.getName().replace('\\', '/');
                    allPaths.add(entryPath.toLowerCase(Locale.ROOT));
                    if (texts.size() < 200) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            texts.put(entryPath, new String(readAllBytes(in), StandardCharsets.UTF_8));
                        }
                    }
                }
            }
        } else {
            String relative = target.getPath().substring(packs.getPath().length() + 1);
            int slash = relative.indexOf('/');
            File root = new File(packs, slash < 0 ? relative : relative.substring(0, slash));
            if (!root.isDirectory()) {
                throw new IOException("Give a pack folder, a .zip or a file inside a pack folder");
            }
            collectPackFiles(root, root, allPaths, texts, target);
        }

        StringBuilder out = new StringBuilder();
        int problems = 0;
        for (Map.Entry<String, String> entry : texts.entrySet()) {
            for (String issue : ShaderPacks.lint(entry.getKey(), entry.getValue(), allPaths)) {
                out.append(entry.getKey()).append(": ").append(issue).append('\n');
                problems++;
            }
        }
        String header = "Checked " + texts.size() + " file(s) of " + target.getName() + ".\n";
        return problems == 0
                ? header + "No problems found. These are static checks only: the game compiles the shader when it loads it."
                : header + problems + " problem(s):\n" + out;
    }

    /** Collects every pack file's path (for #include checks) and the text of those under {@code only}. */
    private void collectPackFiles(File root, File dir, Set<String> allPaths, Map<String, String> texts, File only)
            throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children);
        for (File child : children) {
            if (child.isDirectory()) {
                collectPackFiles(root, child, allPaths, texts, only);
                continue;
            }
            String relative = child.getPath().substring(root.getPath().length() + 1).replace(File.separatorChar, '/');
            allPaths.add(relative.toLowerCase(Locale.ROOT));
            boolean wanted = only.equals(child) || isInside(child, only);
            if (wanted && texts.size() < 200 && child.length() <= ShaderPacks.MAX_FILE_CHARS) {
                String text = readText(child, 0, ShaderPacks.MAX_FILE_CHARS);
                if (text != null) texts.put(relative, text);
            }
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private String listShaderPacks() {
        File dir = shaderpacksDir();
        File[] entries = dir.listFiles();
        if (entries == null || entries.length == 0) return "There are no shader packs in " + dir.getPath();
        Arrays.sort(entries);
        StringBuilder out = new StringBuilder("Shader packs in " + dir.getPath() + ":\n");
        for (File entry : entries) {
            if (entry.isDirectory()) {
                out.append("[folder] ").append(entry.getName()).append("  ")
                        .append(SupersonicUi.formatBytes(AgentUndo.sizeOf(entry))).append('\n');
            } else if (extensionOf(entry.getName()).equals("zip")) {
                out.append("[zip]    ").append(entry.getName()).append("  ")
                        .append(SupersonicUi.formatBytes(entry.length())).append('\n');
            }
        }
        return out.toString();
    }

    private void afterChange(File file) {
        try {
            if ("launcher_profiles.json".equals(file.getName())) LauncherProfiles.load();
        } catch (RuntimeException ignored) { }
    }

    /** JSON files must stay valid JSON, or the launcher could stop starting. */
    private static void validateText(File file, String content) throws IOException {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".json")) return;
        try {
            JsonElement element = new JsonParser().parse(content);
            if ("launcher_profiles.json".equals(name)
                    && !(element.isJsonObject() && element.getAsJsonObject().has("profiles"))) {
                throw new IOException("launcher_profiles.json must be an object with a \"profiles\" entry");
            }
        } catch (JsonParseException e) {
            throw new IOException("The result is not valid JSON (" + e.getMessage() + "), so the file was not changed");
        }
    }

    private static void writeText(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Set<String> fileNames(File dir) {
        Set<String> names = new HashSet<>();
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) names.add(file.getName());
        return names;
    }

    private static String joinWith(String separator, List<String> items) {
        StringBuilder out = new StringBuilder();
        for (String item : items) {
            if (out.length() > 0) out.append(separator);
            out.append(item);
        }
        return out.toString();
    }

    private static String clip(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "\n[... clipped ...]";
    }

    private static String str(JsonObject args, String key, String fallback) {
        JsonElement element = args.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    private static String requireStr(JsonObject args, String key) throws IOException {
        JsonElement element = args.get(key);
        if (element == null || !element.isJsonPrimitive()) throw new IOException("Missing argument: " + key);
        return element.getAsString();
    }

    private static int num(JsonObject args, String key, int fallback) {
        JsonElement element = args.get(key);
        try {
            return element != null && element.isJsonPrimitive() ? (int) Double.parseDouble(element.getAsString()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject args, String key, boolean fallback) {
        JsonElement element = args.get(key);
        return element != null && element.isJsonPrimitive() ? Boolean.parseBoolean(element.getAsString()) : fallback;
    }
}
