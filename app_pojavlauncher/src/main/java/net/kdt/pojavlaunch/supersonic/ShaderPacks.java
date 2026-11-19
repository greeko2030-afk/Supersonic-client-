package net.kdt.pojavlaunch.supersonic;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds OptiFine / Iris style shader packs from text, and checks shader code for common mistakes.
 *
 * A pack is a folder (or a zip with the same layout) inside the shaderpacks folder:
 * <pre>
 *   MyPack/
 *     shaders/
 *       shaders.properties
 *       gbuffers_terrain.vsh / .fsh, composite.fsh, final.fsh ...
 *       lib/common.glsl ...
 * </pre>
 * Only text files in this layout can be written, so a shader pack can never contain code that is not a shader.
 */
public final class ShaderPacks {
    private ShaderPacks() {}

    static final int MAX_FILES = 80;
    static final int MAX_FILE_CHARS = 400_000;
    static final int MAX_PACK_CHARS = 1_500_000;

    private static final Set<String> TEXT_EXTENSIONS = new HashSet<>(java.util.Arrays.asList(
            "fsh", "vsh", "gsh", "csh", "frag", "vert", "geom", "glsl", "inc", "h",
            "properties", "txt", "md", "json", "lang"));
    private static final Set<String> STAGE_EXTENSIONS = new HashSet<>(java.util.Arrays.asList(
            "fsh", "vsh", "gsh", "csh", "frag", "vert", "geom"));
    private static final Set<String> LIBRARY_EXTENSIONS = new HashSet<>(java.util.Arrays.asList("glsl", "inc", "h"));
    private static final Set<String> ROOT_FILES = new HashSet<>(java.util.Arrays.asList(
            "readme.txt", "readme.md", "license", "license.txt", "license.md"));

    /** One file of a pack: a path inside the pack and its text. */
    public static final class PackFile {
        public final String path;
        public final String content;

        public PackFile(String path, String content) {
            this.path = path;
            this.content = content;
        }
    }

    // ------------------------------------------------------------------ names and paths

    /** A pack name that is safe as a folder or file name. */
    static String cleanName(String name) throws IOException {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 60) throw new IOException("The pack name must be 1 to 60 characters");
        if (!n.matches("[A-Za-z0-9][A-Za-z0-9 _.\\-]*") || n.contains("..")) {
            throw new IOException("The pack name may only use letters, digits, spaces, _ - and .");
        }
        return n;
    }

    /** Normalizes and checks one path inside a pack. @return the clean path with / separators */
    static String cleanPath(String path) throws IOException {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        if (p.isEmpty() || p.startsWith("/") || p.endsWith("/")) throw new IOException("Bad file path: \"" + path + "\"");
        String[] parts = p.split("/", -1);
        if (parts.length > 6) throw new IOException("Path is too deep: " + path);
        StringBuilder clean = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith(".")
                    || !part.matches("[A-Za-z0-9_.\\- ]+")) {
                throw new IOException("Bad file path: \"" + path + "\" (only letters, digits, _ - . and spaces; no ..)");
            }
            if (clean.length() > 0) clean.append('/');
            clean.append(part);
        }
        String result = clean.toString();
        String extension = extensionOf(result);
        if (!TEXT_EXTENSIONS.contains(extension)) {
            throw new IOException("File type not allowed in a shader pack: " + path
                    + " (allowed: fsh vsh gsh csh glsl inc properties txt md json lang)");
        }
        boolean inShaders = result.startsWith("shaders/") && result.length() > "shaders/".length();
        if (!inShaders && !ROOT_FILES.contains(result.toLowerCase(Locale.ROOT))) {
            throw new IOException("Shader files must be inside the shaders/ folder: " + path);
        }
        return result;
    }

    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Checks a whole pack before anything is written. @return the files with clean paths */
    static List<PackFile> validate(List<PackFile> files) throws IOException {
        if (files == null || files.isEmpty()) throw new IOException("No files were given");
        if (files.size() > MAX_FILES) throw new IOException("Too many files (limit " + MAX_FILES + ")");
        Map<String, PackFile> byPath = new LinkedHashMap<>();
        long total = 0;
        boolean hasShader = false;
        for (PackFile file : files) {
            String path = cleanPath(file.path);
            if (file.content == null) throw new IOException("No content for " + path);
            if (file.content.length() > MAX_FILE_CHARS) throw new IOException(path + " is too large");
            if (byPath.put(path.toLowerCase(Locale.ROOT), new PackFile(path, file.content)) != null) {
                throw new IOException("The same file is listed twice: " + path);
            }
            total += file.content.length();
            if (path.startsWith("shaders/")) hasShader = true;
        }
        if (total > MAX_PACK_CHARS) throw new IOException("The pack is too large");
        if (!hasShader) throw new IOException("A shader pack needs files inside shaders/");
        return new ArrayList<>(byPath.values());
    }

    // ------------------------------------------------------------------ writing

    /** Writes the pack as a folder {@code <dir>/<name>/}. @return the folder */
    static File writeFolder(File shaderpacksDir, String name, List<PackFile> files) throws IOException {
        File root = new File(shaderpacksDir, name);
        for (PackFile file : files) {
            File target = new File(root, file.path);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(file.content.getBytes(StandardCharsets.UTF_8));
            }
        }
        return root;
    }

    /** Writes the pack as {@code <dir>/<name>.zip} with shaders/ at the top of the zip. @return the zip file */
    static File writeZip(File shaderpacksDir, String name, List<PackFile> files) throws IOException {
        if (!shaderpacksDir.isDirectory() && !shaderpacksDir.mkdirs()) throw new IOException("Cannot create " + shaderpacksDir);
        File zip = new File(shaderpacksDir, name + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (PackFile file : files) {
                out.putNextEntry(new ZipEntry(file.path));
                out.write(file.content.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }

    // ------------------------------------------------------------------ checking shader code

    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#\\s*version\\s+(\\d+)(?:\\s+(core|compatibility|es))?");
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*#\\s*include\\s+[\"<]([^\">]+)[\">]");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(");
    private static final Pattern REMOVED_IN_CORE = Pattern.compile(
            "\\b(texture2D|texture2DLod|texture3D|textureCube|gl_FragData|gl_FragColor|gl_Vertex|gl_Normal|gl_Color"
                    + "|gl_MultiTexCoord\\d|gl_ModelViewMatrix|gl_ProjectionMatrix|gl_ModelViewProjectionMatrix"
                    + "|gl_NormalMatrix|gl_TextureMatrix)\\b");
    private static final Pattern NEW_STYLE_IO = Pattern.compile(
            "\\blayout\\s*\\(|(?m)^\\s*(in|out)\\s+(vec[234]|float|int|uint|mat[234]|ivec[234]|bool)\\b");
    private static final Pattern OLD_STYLE_IO = Pattern.compile("\\b(varying|attribute)\\b");

    /**
     * Static checks only: this phone cannot compile GLSL outside the game, so a pack that passes can still fail
     * in game. It catches the usual mistakes (unbalanced brackets, missing main, version / syntax mismatches,
     * missing includes).
     *
     * @param path     path of the file inside the pack, e.g. shaders/gbuffers_terrain.fsh
     * @param allPaths lower-case paths of every file in the pack, for #include checks; null to skip that check
     * @return problems, empty if none was found
     */
    static List<String> lint(String path, String text, Set<String> allPaths) {
        List<String> issues = new ArrayList<>();
        String ext = extensionOf(path);
        boolean stage = STAGE_EXTENSIONS.contains(ext);
        boolean library = LIBRARY_EXTENSIONS.contains(ext);
        if (!stage && !library) return issues;

        String code = stripCommentsAndStrings(text);
        brackets(code, issues);

        if (stage) {
            String firstLine = "";
            for (String line : code.split("\n", -1)) {
                if (!line.trim().isEmpty()) {
                    firstLine = line.trim();
                    break;
                }
            }
            if (!firstLine.startsWith("#") || !VERSION.matcher(firstLine).find()) {
                issues.add("The first line should be a #version line, e.g. #version 120 or #version 330 compatibility.");
            }
            if (!MAIN.matcher(code).find()) issues.add("There is no void main() function.");

            Matcher version = VERSION.matcher(code);
            if (version.find()) {
                int v = Integer.parseInt(version.group(1));
                String profile = version.group(2) == null ? "" : version.group(2);
                boolean core = profile.equals("core") || (v >= 150 && profile.isEmpty());
                if (v < 130 && NEW_STYLE_IO.matcher(code).find()) {
                    issues.add("#version " + v + " has no layout()/in/out. Use varying/attribute, or raise the version.");
                }
                if (core && OLD_STYLE_IO.matcher(code).find()) {
                    issues.add("varying/attribute do not exist in the core profile (#version " + v
                            + "). Use in/out, or write '#version " + v + " compatibility'.");
                }
                Matcher removed = REMOVED_IN_CORE.matcher(code);
                if (core && removed.find()) {
                    issues.add(removed.group(1) + " is not available in the core profile. Use '#version "
                            + (v >= 150 ? v : 330) + " compatibility' or the 120 style.");
                }
            }
        }

        if (allPaths != null) {
            String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
            Matcher include = INCLUDE.matcher(strip(text, false));
            while (include.find()) {
                String target = include.group(1).trim();
                String resolved = normalize(target.startsWith("/") ? "shaders" + target : (dir.isEmpty() ? target : dir + "/" + target));
                if (resolved == null || !allPaths.contains(resolved.toLowerCase(Locale.ROOT))) {
                    issues.add("#include \"" + target + "\" was not found in the pack.");
                }
            }
        }
        return issues;
    }

    /** Resolves "." and ".." in a relative path; null if it climbs out of the pack. */
    private static String normalize(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (out.isEmpty()) return null;
                out.remove(out.size() - 1);
            } else {
                out.add(part);
            }
        }
        StringBuilder result = new StringBuilder();
        for (String part : out) {
            if (result.length() > 0) result.append('/');
            result.append(part);
        }
        return result.toString();
    }

    /** Replaces comments and string contents with spaces (line breaks stay), so counting is not fooled by them. */
    static String stripCommentsAndStrings(String text) {
        return strip(text, true);
    }

    /** @param stripStrings false keeps the text of strings (needed to read #include "path") */
    static String strip(String text, boolean stripStrings) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            char next = i + 1 < n ? text.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && text.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(text.charAt(i) == '*' && i + 1 < n && text.charAt(i + 1) == '/')) {
                    out.append(text.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
            } else if (c == '"') {
                out.append(stripStrings ? ' ' : c);
                i++;
                while (i < n && text.charAt(i) != '"' && text.charAt(i) != '\n') {
                    out.append(stripStrings ? ' ' : text.charAt(i));
                    i++;
                }
                if (i < n && text.charAt(i) == '"') {
                    out.append(stripStrings ? ' ' : '"');
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void brackets(String code, List<String> issues) {
        String open = "({[", close = ")}]";
        int[] opened = new int[3];
        int[] closed = new int[3];
        int line = 1;
        boolean reported = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\n') line++;
            int o = open.indexOf(c), k = close.indexOf(c);
            if (o >= 0) opened[o]++;
            if (k >= 0) {
                closed[k]++;
                if (closed[k] > opened[k] && !reported) {
                    issues.add("Line " + line + ": unexpected closing " + c + " with nothing open.");
                    reported = true;
                }
            }
        }
        for (int j = 0; j < 3; j++) {
            if (opened[j] != closed[j]) {
                issues.add("Unbalanced " + open.charAt(j) + close.charAt(j) + ": " + opened[j] + " opened, " + closed[j] + " closed.");
            }
        }
    }
}
