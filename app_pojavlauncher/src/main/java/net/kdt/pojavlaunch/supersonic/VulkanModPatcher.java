package net.kdt.pojavlaunch.supersonic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Makes a VulkanMod jar from Modrinth run on Android. It does what scripts/patch_vulkanmod.sh does on a PC:
 * <ol>
 *   <li>the bundled lwjgl-vulkan jar is replaced by the Android build of LWJGL's Vulkan module, keeping the
 *       jar's own META-INF and fabric.mod.json so that Fabric still recognizes it;</li>
 *   <li>the Android versions of libshaderc.so and liblwjgl_vma.so are added to the bundled Linux natives
 *       jars, for the CPU architecture of this phone.</li>
 * </ol>
 * The LWJGL version is read from the names of the bundled jars, so it works for any VulkanMod build that
 * bundles LWJGL the usual way. The original jar is kept next to it as "<name>.jar.orig" (which Fabric does not load).
 * A patched jar carries a marker file, so patching twice does nothing.
 */
public final class VulkanModPatcher {
    private VulkanModPatcher() {}

    static final String MARKER = "META-INF/supersonic_native_vulkan";

    private static final Pattern VULKAN_JAR = Pattern.compile("^META-INF/jars/lwjgl-vulkan-([0-9][^/]*)\\.jar$");
    private static final Pattern SHADERC_JAR = Pattern.compile("^META-INF/jars/lwjgl-shaderc-([0-9][^/]*)-natives-linux\\.jar$");
    private static final Pattern VMA_JAR = Pattern.compile("^META-INF/jars/lwjgl-vma-([0-9][^/]*)-natives-linux\\.jar$");

    /** Where the Android builds of LWJGL come from. */
    public interface Natives {
        /** The Android build of lwjgl-vulkan.jar for this LWJGL version. */
        byte[] vulkanJar(String lwjglVersion) throws IOException;

        /** An Android native library ("libshaderc.so" or "liblwjgl_vma.so") for one architecture. */
        byte[] nativeLib(String lwjglVersion, String arch, String libraryName) throws IOException;
    }

    /** What happened. */
    public static final class Result {
        public final boolean changed;
        public final String lwjglVersion;
        public final String message;

        Result(boolean changed, String lwjglVersion, String message) {
            this.changed = changed;
            this.lwjglVersion = lwjglVersion;
            this.message = message;
        }
    }

    // ------------------------------------------------------------------ inspecting

    /** True if this jar was already made Android-ready. */
    public static boolean isPatched(File jar) {
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (MARKER.equals(entry.getName())) return true;
            }
        } catch (IOException ignored) { }
        return false;
    }

    /** The LWJGL version that this VulkanMod bundles, or null if it does not bundle lwjgl-vulkan. */
    public static String lwjglVersionOf(File jar) {
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Matcher m = VULKAN_JAR.matcher(entry.getName());
                if (m.matches()) return m.group(1);
            }
        } catch (IOException ignored) { }
        return null;
    }

    // ------------------------------------------------------------------ patching

    /**
     * @param archs architecture tags as used by the Pojav LWJGL builds: arm64, arm32, x64, x86
     * @return what was done; the jar is only replaced when everything worked
     */
    public static Result patch(File jar, List<String> archs, Natives natives) throws IOException {
        if (archs.isEmpty()) throw new IOException("No CPU architecture given");
        if (isPatched(jar)) return new Result(false, lwjglVersionOf(jar), "Already prepared for Android");

        // 1) read the outer jar: keep unchanged entries as they are, load the three jars that change
        Map<String, byte[]> nested = new LinkedHashMap<>();
        String version = null;
        String vulkanName = null, shadercName = null, vmaName = null;
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                Matcher vulkan = VULKAN_JAR.matcher(name);
                if (vulkan.matches()) {
                    version = vulkan.group(1);
                    vulkanName = name;
                }
                if (SHADERC_JAR.matcher(name).matches()) shadercName = name;
                if (VMA_JAR.matcher(name).matches()) vmaName = name;
                if (name.equals(vulkanName) || name.equals(shadercName) || name.equals(vmaName)) {
                    nested.put(name, readAll(in));
                }
            }
        }
        if (vulkanName == null) {
            throw new IOException("This VulkanMod build does not contain the LWJGL Vulkan jar that has to be replaced, "
                    + "so it cannot be prepared for Android");
        }

        // 2) build the replacements (nothing is written to disk yet, so a failure leaves the jar untouched)
        Map<String, byte[]> replaced = new LinkedHashMap<>();
        replaced.put(vulkanName, mergeVulkanJar(nested.get(vulkanName), natives.vulkanJar(version)));
        if (shadercName != null) {
            replaced.put(shadercName, addNatives(nested.get(shadercName), version, archs, natives,
                    "org/lwjgl/shaderc/", "libshaderc.so"));
        }
        if (vmaName != null) {
            replaced.put(vmaName, addNatives(nested.get(vmaName), version, archs, natives,
                    "org/lwjgl/vma/", "liblwjgl_vma.so"));
        }

        // 3) write the new jar next to the old one, then swap
        File temp = new File(jar.getPath() + ".patching");
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(jar));
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(temp))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    out.putNextEntry(new ZipEntry(name));
                    out.closeEntry();
                    continue;
                }
                out.putNextEntry(new ZipEntry(name));
                if (replaced.containsKey(name)) out.write(replaced.get(name));
                else copy(in, out);
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry(MARKER));
            out.write(("LWJGL " + version + " " + archs).getBytes("UTF-8"));
            out.closeEntry();
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw e;
        }

        File original = new File(jar.getPath() + ".orig");
        if (!original.exists() && !jar.renameTo(original)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("Cannot keep a copy of the original " + jar.getName());
        }
        if (jar.exists() && !jar.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("Cannot replace " + jar.getName());
        }
        if (!temp.renameTo(jar)) throw new IOException("Cannot save the prepared " + jar.getName());
        return new Result(true, version, "Prepared for Android (LWJGL " + version + ", " + archs + ")");
    }

    /**
     * The Android lwjgl-vulkan classes, plus META-INF and fabric.mod.json of the jar that was there
     * (they win over files of the same name, like zip -r does in the script).
     */
    static byte[] mergeVulkanJar(byte[] oldJar, byte[] androidJar) throws IOException {
        Map<String, byte[]> keep = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(oldJar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory() && (name.startsWith("META-INF/") || name.equals("fabric.mod.json"))) {
                    keep.put(name, readAll(in));
                }
            }
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(androidJar));
             ZipOutputStream out = new ZipOutputStream(result)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory() || keep.containsKey(entry.getName())) continue;
                out.putNextEntry(new ZipEntry(entry.getName()));
                copy(in, out);
                out.closeEntry();
            }
            for (Map.Entry<String, byte[]> k : keep.entrySet()) {
                out.putNextEntry(new ZipEntry(k.getKey()));
                out.write(k.getValue());
                out.closeEntry();
            }
        }
        return result.toByteArray();
    }

    /** Adds linux/{arch}/{folder}{library} for every architecture (an existing entry of that name is replaced). */
    static byte[] addNatives(byte[] jar, String version, List<String> archs, Natives natives, String folder, String library)
            throws IOException {
        Map<String, byte[]> add = new LinkedHashMap<>();
        for (String arch : archs) add.put("linux/" + arch + "/" + folder + library, natives.nativeLib(version, arch, library));

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar));
             ZipOutputStream out = new ZipOutputStream(result)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (add.containsKey(entry.getName())) continue;
                out.putNextEntry(new ZipEntry(entry.getName()));
                if (!entry.isDirectory()) copy(in, out);
                out.closeEntry();
            }
            for (Map.Entry<String, byte[]> a : add.entrySet()) {
                out.putNextEntry(new ZipEntry(a.getKey()));
                out.write(a.getValue());
                out.closeEntry();
            }
        }
        return result.toByteArray();
    }

    /** Puts the original jar back (undoes {@link #patch}). @return true if a jar was restored */
    public static boolean restoreOriginal(File jar) {
        File original = new File(jar.getPath() + ".orig");
        if (!original.isFile()) return false;
        if (jar.exists() && !jar.delete()) return false;
        return original.renameTo(jar);
    }

    // ------------------------------------------------------------------ helpers

    static List<String> names(File zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) names.add(entry.getName());
        }
        return names;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16384];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
    }
}
