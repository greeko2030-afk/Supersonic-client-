package net.kdt.pojavlaunch.supersonic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What a mod jar says about itself: its id and version, and which Minecraft versions it supports for which
 * mod loader. This is read from the metadata inside of the jar (fabric.mod.json, quilt.mod.json,
 * META-INF/neoforge.mods.toml, META-INF/mods.toml, mcmod.info), so the file name does not matter.
 *
 * The class is also stored as JSON in the index of the repository mods, so it has plain fields.
 */
final class ModJarInfo {
    static final String FABRIC = "fabric";
    static final String QUILT = "quilt";
    static final String FORGE = "forge";
    static final String NEOFORGE = "neoforge";

    static final String TYPE_SEMVER = "semver";
    static final String TYPE_MAVEN = "maven";
    static final String TYPE_EXACT = "exact";

    /** The Minecraft versions that this jar supports for one mod loader. */
    static final class Variant {
        String loader;
        String type;
        /** The jar works when the Minecraft version is inside one of these. An empty list: it does not say. */
        List<String> ranges = new ArrayList<>();

        Variant() {}

        Variant(String loader, String type) {
            this.loader = loader;
            this.type = type;
        }
    }

    String id;
    String name;
    String version;
    /** True for a mod that only runs on a dedicated server. */
    boolean serverOnly;
    List<Variant> variants = new ArrayList<>();

    private ModJarInfo() {}

    // ------------------------------------------------------------------ support check

    /**
     * @param instanceLoader one of the SupersonicProfile.LOADER_* values
     * @param mcVersion      the Minecraft version of the instance, for example 1.20.1
     * @return true when this jar says that it supports exactly this loader and Minecraft version
     */
    boolean supports(String instanceLoader, String mcVersion) {
        if (serverOnly || instanceLoader == null || mcVersion == null) return false;
        for (Variant variant : variants) {
            if (!loaderAccepts(instanceLoader, variant.loader)) continue;
            for (String range : variant.ranges) {
                if (rangeMatches(variant.type, range, mcVersion)) return true;
            }
        }
        return false;
    }

    /** Quilt also loads Fabric mods. */
    private static boolean loaderAccepts(String instanceLoader, String modLoader) {
        if (instanceLoader.equals(modLoader)) return true;
        return QUILT.equals(instanceLoader) && FABRIC.equals(modLoader);
    }

    private static boolean rangeMatches(String type, String range, String mcVersion) {
        if (TYPE_MAVEN.equals(type)) return McVersionRange.matchesMaven(range, mcVersion);
        if (TYPE_EXACT.equals(type)) return McVersionRange.matchesExact(range, mcVersion);
        return McVersionRange.matchesSemver(range, mcVersion);
    }

    // ------------------------------------------------------------------ reading

    /** @throws IOException when the file is not a jar or has no mod metadata */
    static ModJarInfo read(File jar) throws IOException {
        ModJarInfo info = new ModJarInfo();
        try (ZipFile zip = new ZipFile(jar)) {
            // The first metadata file that names an id decides id, name and version
            JsonObject fabric = readJson(zip, "fabric.mod.json");
            if (fabric != null) readFabric(fabric, info);

            JsonObject quilt = readJson(zip, "quilt.mod.json");
            if (quilt != null) readQuilt(quilt, info);

            String neoforge = readText(zip, "META-INF/neoforge.mods.toml");
            if (neoforge != null) readToml(neoforge, NEOFORGE, info);

            String forge = readText(zip, "META-INF/mods.toml");
            if (forge != null) readToml(forge, FORGE, info);

            JsonObject legacy = readLegacyInfo(zip);
            if (legacy != null) readLegacy(legacy, info);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Unreadable mod metadata: " + e.getMessage(), e);
        }
        if (info.id == null || info.id.isEmpty() || info.variants.isEmpty()) {
            throw new IOException("Not a mod: no mod metadata in " + jar.getName());
        }
        if (info.name == null || info.name.isEmpty()) info.name = info.id;
        return info;
    }

    private static void readFabric(JsonObject json, ModJarInfo info) {
        if (info.id == null) {
            info.id = string(json, "id");
            info.name = string(json, "name");
            info.version = string(json, "version");
        }
        if ("server".equals(string(json, "environment"))) info.serverOnly = true;

        Variant variant = new Variant(FABRIC, TYPE_SEMVER);
        JsonElement depends = json.get("depends");
        if (depends != null && depends.isJsonObject()) {
            addStrings(depends.getAsJsonObject().get("minecraft"), variant.ranges);
        }
        info.variants.add(variant);
    }

    private static void readQuilt(JsonObject json, ModJarInfo info) {
        JsonElement loaderElement = json.get("quilt_loader");
        if (loaderElement == null || !loaderElement.isJsonObject()) return;
        JsonObject loader = loaderElement.getAsJsonObject();
        if (info.id == null) {
            info.id = string(loader, "id");
            info.version = string(loader, "version");
            JsonElement metadata = loader.get("metadata");
            if (metadata != null && metadata.isJsonObject()) info.name = string(metadata.getAsJsonObject(), "name");
        }
        JsonElement minecraft = json.get("minecraft");
        if (minecraft != null && minecraft.isJsonObject()
                && "dedicated_server".equals(string(minecraft.getAsJsonObject(), "environment"))) {
            info.serverOnly = true;
        }

        Variant variant = new Variant(QUILT, TYPE_SEMVER);
        JsonElement depends = loader.get("depends");
        if (depends != null && depends.isJsonArray()) {
            for (JsonElement dependency : depends.getAsJsonArray()) {
                if (dependency.isJsonPrimitive() && "minecraft".equals(dependency.getAsString())) {
                    variant.ranges.add("*"); // depends on Minecraft without naming a version
                } else if (dependency.isJsonObject() && "minecraft".equals(string(dependency.getAsJsonObject(), "id"))) {
                    addStrings(dependency.getAsJsonObject().get("versions"), variant.ranges);
                }
            }
        }
        info.variants.add(variant);
    }

    /** A small reader of the parts of mods.toml that are needed: the first mod and the Minecraft dependency. */
    private static void readToml(String text, String loader, ModJarInfo info) {
        boolean inFirstMod = false;
        boolean firstModSeen = false;
        Map<String, String> dependency = null;
        List<Map<String, String>> dependencies = new ArrayList<>();
        String modId = null;
        String modVersion = null;
        String modName = null;

        for (String rawLine : text.split("\\r?\\n")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[")) {
                dependency = null;
                inFirstMod = false;
                if (line.startsWith("[[mods]]")) {
                    if (!firstModSeen) {
                        inFirstMod = true;
                        firstModSeen = true;
                    }
                } else if (line.startsWith("[[dependencies.")) {
                    dependency = new HashMap<>();
                    dependencies.add(dependency);
                }
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            String key = line.substring(0, equals).trim();
            String value = unquote(line.substring(equals + 1).trim());
            if (inFirstMod) {
                if (key.equals("modId")) modId = value;
                else if (key.equals("version")) modVersion = value;
                else if (key.equals("displayName")) modName = value;
            }
            if (dependency != null) dependency.put(key, value);
        }

        if (info.id == null) {
            info.id = modId;
            info.version = modVersion;
            info.name = modName;
        }
        Variant variant = new Variant(loader, TYPE_MAVEN);
        for (Map<String, String> entry : dependencies) {
            if ("minecraft".equals(entry.get("modId")) && entry.get("versionRange") != null) {
                variant.ranges.add(entry.get("versionRange"));
            }
        }
        info.variants.add(variant);
    }

    /** mcmod.info of Forge 1.12 and older: one Minecraft version per mod. */
    private static void readLegacy(JsonObject entry, ModJarInfo info) {
        if (info.id == null) {
            info.id = string(entry, "modid");
            info.name = string(entry, "name");
            info.version = string(entry, "version");
        }
        Variant variant = new Variant(FORGE, TYPE_EXACT);
        String mcVersion = string(entry, "mcversion");
        if (mcVersion != null && !mcVersion.isEmpty()) variant.ranges.add(mcVersion);
        info.variants.add(variant);
    }

    private static JsonObject readLegacyInfo(ZipFile zip) throws IOException {
        String text = readText(zip, "mcmod.info");
        if (text == null) return null;
        JsonElement root = parse(text);
        if (root.isJsonArray()) {
            JsonArray array = root.getAsJsonArray();
            return array.size() > 0 && array.get(0).isJsonObject() ? array.get(0).getAsJsonObject() : null;
        }
        if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            JsonElement list = object.get("modList");
            if (list != null && list.isJsonArray() && list.getAsJsonArray().size() > 0
                    && list.getAsJsonArray().get(0).isJsonObject()) {
                return list.getAsJsonArray().get(0).getAsJsonObject();
            }
            return object;
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject readJson(ZipFile zip, String name) throws IOException {
        String text = readText(zip, name);
        if (text == null) return null;
        JsonElement root = parse(text);
        return root.isJsonObject() ? root.getAsJsonObject() : null;
    }

    /**
     * Mod metadata may have comments, so the parser is lenient. When it still fails, it is tried once more
     * without the trailing commas that some mods have before a closing bracket.
     */
    private static JsonElement parse(String text) throws IOException {
        try {
            return parseLenient(text);
        } catch (RuntimeException first) {
            try {
                return parseLenient(text.replaceAll(",(\\s*[}\\]])", "$1"));
            } catch (RuntimeException second) {
                throw new IOException("Invalid JSON in mod metadata", first);
            }
        }
    }

    private static JsonElement parseLenient(String text) {
        JsonReader reader = new JsonReader(new java.io.StringReader(text));
        reader.setLenient(true);
        return new JsonParser().parse(reader);
    }

    /** @return the text of an entry of the jar, or null when there is no such entry */
    private static String readText(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) return null;
        if (entry.getSize() > 4 * 1024 * 1024) throw new IOException("Metadata file is too large: " + name);
        try (InputStream in = zip.getInputStream(entry);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) out.append(buffer, 0, read);
            // A byte order mark at the start would break the JSON parser
            return out.length() > 0 && out.charAt(0) == '\uFEFF' ? out.substring(1) : out.toString();
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /** A string, or an array of strings, as a list of strings. */
    private static void addStrings(JsonElement element, List<String> into) {
        if (element == null) return;
        if (element.isJsonPrimitive()) {
            into.add(element.getAsString());
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive()) into.add(item.getAsString());
            }
        }
    }

    /** Removes a # comment, unless the # is inside of a quoted value. */
    private static String stripComment(String line) {
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == quote) quoted = false;
            } else if (c == '"' || c == '\'') {
                quoted = true;
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'') && first == last) return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
