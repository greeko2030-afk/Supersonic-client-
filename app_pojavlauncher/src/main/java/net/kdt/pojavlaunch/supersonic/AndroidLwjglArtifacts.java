package net.kdt.pojavlaunch.supersonic;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The Android builds of LWJGL that Pojav publishes (the same files that scripts/patch_vulkanmod.sh downloads):
 * lwjgl3-android-modules.zip (with lwjgl-vulkan/lwjgl-vulkan.jar) and lwjgl3-android-natives-{arch}.zip
 * (with libshaderc.so and liblwjgl_vma.so). They are downloaded once per use into a temporary folder.
 */
final class AndroidLwjglArtifacts implements VulkanModPatcher.Natives {
    /** The unit tests point this at a local server. */
    static String base = "https://nightly.link/PojavLauncherTeam/lwjgl3/workflows/build-android";

    private final File tempDir;
    private final Map<String, File> downloaded = new HashMap<>();

    AndroidLwjglArtifacts(File tempDir) {
        this.tempDir = tempDir;
    }

    private File zip(String version, String artifact) throws IOException {
        String key = version + "/" + artifact;
        File file = downloaded.get(key);
        if (file != null) return file;
        if (!tempDir.isDirectory() && !tempDir.mkdirs()) throw new IOException("Cannot create " + tempDir);
        File target = new File(tempDir, artifact + "-" + version + ".zip");
        String url = base + "/" + version + "/" + artifact + ".zip";
        try {
            WebAccess.download(url, target);
        } catch (IOException e) {
            throw new IOException("Could not download the Android LWJGL build \"" + artifact + "\" for LWJGL " + version
                    + " (" + e.getMessage() + "). This VulkanMod version may need a newer Android build than Pojav offers.", e);
        }
        downloaded.put(key, target);
        return target;
    }

    @Override
    public byte[] vulkanJar(String lwjglVersion) throws IOException {
        return entry(zip(lwjglVersion, "lwjgl3-android-modules"), "lwjgl-vulkan/lwjgl-vulkan.jar");
    }

    @Override
    public byte[] nativeLib(String lwjglVersion, String arch, String libraryName) throws IOException {
        return entry(zip(lwjglVersion, "lwjgl3-android-natives-" + arch), libraryName);
    }

    /** Deletes the downloaded files. */
    void cleanup() {
        for (File file : downloaded.values()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        downloaded.clear();
        //noinspection ResultOfMethodCallIgnored
        tempDir.delete();
    }

    private static byte[] entry(File zip, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.getName().equals(name)) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                return out.toByteArray();
            }
        }
        throw new IOException(zip.getName() + " does not contain " + name);
    }
}
