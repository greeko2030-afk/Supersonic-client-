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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
        boolean approve(String title, String detail);
    }

    private static final class UserDenied extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final int MAX_RESULT_CHARS = 6000;
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
        List<AiChat.ToolSpec> tools = new ArrayList<>();

        tools.add(tool("get_environment",
                "Phone, RAM, launcher settings, the selected Minecraft instance (version, loader, mods, JVM arguments) "
                        + "and the folders you can use. Call this first.",
                schema()));
        tools.add(tool("diagnose",
                "Runs the built in checks: RAM, storage, mods that do not fit the game version, missing mods, "
                        + "bad JVM arguments, and known crash patterns in the last log. Cheap and offline.",
                schema()));
        tools.add(tool("read_log",
                "Reads the game log. source: latest_log (default, starts at the last error), latest_log_tail "
                        + "(just the end of the log) or crash_report (newest file in crash-reports).",
                schema(new String[0], "source", enumProp("latest_log, latest_log_tail or crash_report",
                        "latest_log", "latest_log_tail", "crash_report"))));
        tools.add(tool("list_dir",
                "Lists a folder. Relative paths start in the Minecraft folder. Empty path = the Minecraft folder.",
                schema(new String[0], "path", prop("string", "Folder path"))));
        tools.add(tool("read_file",
                "Reads a text file (config, options.txt, launcher_profiles.json, mod configs, logs). "
                        + "Large files are cut; use offset to continue.",
                schema(new String[]{"path"},
                        "path", prop("string", "File path"),
                        "offset", prop("integer", "Characters to skip, default 0"),
                        "max_chars", prop("integer", "Maximum characters to return, default 8000"))));
        tools.add(tool("search_files",
                "Searches text files under a folder for a text (case-insensitive), like grep.",
                schema(new String[]{"query"},
                        "path", prop("string", "Folder to search, default the Minecraft folder"),
                        "query", prop("string", "Text to find"),
                        "max_results", prop("integer", "Default 30"))));
        tools.add(tool("list_mods",
                "Lists the mods of the selected instance: on/off, id, version and whether each one fits the "
                        + "instance's Minecraft version and loader.",
                schema()));
        tools.add(tool("search_modrinth",
                "Searches Modrinth for mods that fit the selected instance.",
                schema(new String[]{"query"}, "query", prop("string", "Search text"))));
        tools.add(tool("list_settings",
                "Lists the launcher settings (renderer, RAM, resolution scale, controls...) with their current values.",
                schema()));

        tools.add(tool("write_file",
                "Creates or overwrites a text file. The old content is backed up. Prefer edit_file for small changes. "
                        + "JSON files are checked before they are written.",
                schema(new String[]{"path", "content"},
                        "path", prop("string", "File path"),
                        "content", prop("string", "Complete new content of the file"))));
        tools.add(tool("edit_file",
                "Replaces text in a file. old_str must match the file exactly (read the file first) and must be "
                        + "unique unless replace_all is true.",
                schema(new String[]{"path", "old_str", "new_str"},
                        "path", prop("string", "File path"),
                        "old_str", prop("string", "Exact text to replace"),
                        "new_str", prop("string", "Replacement text (empty to delete)"),
                        "replace_all", prop("boolean", "Replace every occurrence, default false"))));
        tools.add(tool("delete_path",
                "Deletes a file or folder (backed up first, up to 100 MB).",
                schema(new String[]{"path"}, "path", prop("string", "Path to delete"))));
        tools.add(tool("set_mod_enabled",
                "Turns a mod of the selected instance on or off by renaming it (.jar <-> .jar.disabled).",
                schema(new String[]{"file_name", "enabled"},
                        "file_name", prop("string", "Mod file name as shown by list_mods"),
                        "enabled", prop("boolean", "true = on, false = off"))));
        tools.add(tool("install_mod",
                "Downloads a mod and its required dependencies from Modrinth into the selected instance.",
                schema(new String[]{"project"}, "project", prop("string", "Modrinth slug or id, e.g. fabric-api"))));
        tools.add(tool("set_setting",
                "Changes one launcher setting. Use list_settings for the keys. The value keeps the setting's type.",
                schema(new String[]{"key", "value"},
                        "key", prop("string", "Setting key"),
                        "value", prop("string", "New value, e.g. true, 3072, opengles2"))));
        tools.add(tool("set_jvm_argument",
                "Adds, enables, disables or removes one JVM argument, e.g. -XX:+UseG1GC.",
                schema(new String[]{"argument", "action"},
                        "argument", prop("string", "The JVM argument"),
                        "action", enumProp("add, enable, disable or remove", "add", "enable", "disable", "remove"))));
        tools.add(tool("apply_safe_fixes",
                "Runs the automatic fixes found by diagnose that are safe and undoable (turn off mods that do not "
                        + "fit, install missing mods, turn off bad JVM arguments, pick safe RAM).",
                schema()));
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
                return readFile(requireStr(args, "path"), num(args, "offset", 0), num(args, "max_chars", 8000));
            case "search_files":
                return searchFiles(str(args, "path", ""), requireStr(args, "query"), num(args, "max_results", 30));
            case "list_mods":
                return listMods();
            case "search_modrinth":
                return searchModrinth(requireStr(args, "query"));
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
                return installMod(requireStr(args, "project"));
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
        if (approver == null || !approver.approve(title, detail)) throw new UserDenied();
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
            if (shown++ >= 200) {
                out.append("... ").append(children.length - 200).append(" more\n");
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
        int limit = Math.max(200, Math.min(maxChars, 12000));
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
            if (shown++ >= 120) {
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

    private String searchModrinth(String query) throws IOException {
        SupersonicProfile profile = SupersonicProfile.current();
        String mc = profile == null ? null : profile.mcVersion;
        String loader = profile != null && profile.supportsMods() ? profile.loader : null;
        List<ModrinthClient.Hit> hits = ModrinthClient.search(query, "mod", null, mc, loader, "relevance", 8);
        if (hits.isEmpty()) return "No mods found.";
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

    private String installMod(String project) throws IOException {
        SupersonicProfile profile = SupersonicProfile.current();
        if (profile == null || !profile.supportsMods()) throw new IOException("The selected instance has no mod loader");
        String slug = project.trim().toLowerCase(Locale.ROOT);
        if (slug.isEmpty() || slug.contains("/") || slug.contains(" ")) throw new IOException("Use the Modrinth slug, e.g. fabric-api");
        approve("Install mod from Modrinth?", slug + "\ninto " + profile.displayName()
                + " (Minecraft " + profile.mcVersion + ", " + profile.loader + "), with required dependencies");

        File dir = profile.modsDir();
        Set<String> before = fileNames(dir);
        Set<String> installed = ModrinthClient.projectIds(ModrinthClient.scanInstalled(dir));
        ModrinthClient.install(slug, false, profile, installed, new HashSet<String>(), 0);
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
