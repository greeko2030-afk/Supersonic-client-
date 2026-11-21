package net.kdt.pojavlaunch.supersonic;

import android.content.Context;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Native Vulkan: the game draws with Vulkan directly (no OpenGL, so nothing is translated while you play).
 * It uses the VulkanMod Fabric mod. Pojav's native layer already switches to Vulkan when the game asks for a window
 * without an OpenGL context, which is what VulkanMod does, so the missing pieces were getting VulkanMod and
 * making it run on Android. This class does both: it installs VulkanMod from Modrinth and prepares the jar
 * ({@link VulkanModPatcher}).
 *
 * Limits that come from VulkanMod itself: Fabric only, Vulkan 1.2 or newer, and it replaces Sodium-style rendering, so
 * Sodium, Iris and shader packs cannot be used together with it.
 */
public final class NativeVulkan {
    private NativeVulkan() {}

    public static final String MOD_ID = "vulkanmod";
    /** Mods that replace the same part of the game as VulkanMod. */
    private static final Set<String> CONFLICTING_IDS = new HashSet<>(Arrays.asList(
            "sodium", "embeddium", "iris", "indium", "optifabric", "canvas"));

    /** Reports what is going on. */
    public interface Progress {
        void step(String text);
    }

    // ------------------------------------------------------------------ what is possible and what is there

    /** @throws IOException with the reason, if native Vulkan cannot be used for this instance on this phone */
    public static void check(Context context, SupersonicProfile profile) throws IOException {
        if (profile == null) throw new IOException("No instance is selected.");
        if (!profile.supportsMods() || !"fabric".equalsIgnoreCase(profile.loader)) {
            throw new IOException("Native Vulkan (VulkanMod) needs a Fabric instance. This one uses " + profile.loader + ".");
        }
        VulkanEngine.Device device = VulkanEngine.detect(context);
        if (VulkanEngine.tierOf(device) == 0) {
            throw new IOException("Native Vulkan needs Vulkan 1.2 or newer. This phone has " + device.label() + ".");
        }
    }

    /**
     * The enabled VulkanMod jar of the instance, or null. This is called often (every time the main menu shows the
     * engine), so it only opens jars whose file name has "vulkanmod" in it. {@link #findJarDeep} checks every jar.
     */
    public static File findJar(SupersonicProfile profile) {
        return find(profile, false);
    }

    /** Like {@link #findJar}, but also finds a VulkanMod jar that was renamed (opens every jar). */
    static File findJarDeep(SupersonicProfile profile) {
        return find(profile, true);
    }

    private static File find(SupersonicProfile profile, boolean everyJar) {
        File[] files = profile == null ? null : profile.modsDir().listFiles((dir, name) -> name.endsWith(".jar"));
        if (files == null) return null;
        Arrays.sort(files);
        for (File file : files) {
            if (!everyJar && !file.getName().toLowerCase(Locale.ROOT).contains(MOD_ID)) continue;
            try {
                if (MOD_ID.equals(ModJarInfo.read(file).id)) return file;
            } catch (IOException | RuntimeException ignored) { }
        }
        return null;
    }

    /** VulkanMod is installed and prepared for Android. */
    public static boolean isReady(SupersonicProfile profile) {
        File jar = findJar(profile);
        return jar != null && VulkanModPatcher.isPatched(jar);
    }

    /** Names of the enabled mods that cannot be used together with VulkanMod. */
    public static List<String> conflicts(SupersonicProfile profile) {
        List<String> found = new ArrayList<>();
        File[] files = profile == null ? null : profile.modsDir().listFiles((dir, name) -> name.endsWith(".jar"));
        if (files == null) return found;
        Arrays.sort(files);
        for (File file : files) {
            try {
                String id = ModJarInfo.read(file).id;
                if (id != null && CONFLICTING_IDS.contains(id.toLowerCase(Locale.ROOT))) found.add(file.getName());
            } catch (IOException | RuntimeException ignored) { }
        }
        return found;
    }

    /** One short sentence for the settings screen and for SS Agent. */
    public static String status(Context context) {
        SupersonicProfile profile = SupersonicProfile.current();
        try {
            check(context, profile);
        } catch (IOException e) {
            return e.getMessage();
        }
        File jar = findJar(profile);
        if (jar != null && VulkanModPatcher.isPatched(jar)) {
            return "Ready in \"" + profile.displayName() + "\": the game draws with Vulkan directly, nothing is translated.";
        }
        if (jar != null) return "VulkanMod is in \"" + profile.displayName() + "\" but not prepared for Android yet.";
        return "Not installed in \"" + profile.displayName() + "\".";
    }

    // ------------------------------------------------------------------ install and remove

    /** CPU architecture tag of the Pojav LWJGL builds. */
    static List<String> archTags() {
        boolean x86 = Architecture.isx86Device();
        boolean is32 = Architecture.is32BitsDevice();
        return Collections.singletonList(x86 ? (is32 ? "x86" : "x64") : (is32 ? "arm32" : "arm64"));
    }

    /**
     * Installs VulkanMod (and what it needs) from Modrinth, if it is not there, and prepares it for Android.
     * Needs internet. Blocking: call from a background thread.
     *
     * @return what to tell the user
     */
    public static String install(Context context, SupersonicProfile profile, Progress progress) throws IOException {
        check(context, profile);
        File jar = findJarDeep(profile);
        if (jar == null) {
            say(progress, "Installing VulkanMod and Fabric API from Modrinth…");
            File dir = profile.modsDir();
            Set<String> installed = ModrinthClient.projectIds(ModrinthClient.scanInstalled(dir));
            ModrinthClient.install(MOD_ID, false, profile, installed, new HashSet<String>(), 0);
            jar = findJarDeep(profile);
            if (jar == null) {
                throw new IOException("VulkanMod could not be installed: Modrinth has no version for Minecraft "
                        + profile.mcVersion + " with Fabric.");
            }
        }
        if (VulkanModPatcher.isPatched(jar)) {
            return "VulkanMod is already ready in \"" + profile.displayName() + "\".";
        }

        String version = VulkanModPatcher.lwjglVersionOf(jar);
        say(progress, "Downloading the Android LWJGL build" + (version == null ? "" : " " + version) + "…");
        AndroidLwjglArtifacts artifacts = new AndroidLwjglArtifacts(new File(Tools.DIR_CACHE, "vulkanmod_patch"));
        try {
            say(progress, "Preparing VulkanMod for Android…");
            VulkanModPatcher.patch(jar, archTags(), artifacts);
        } finally {
            artifacts.cleanup();
        }
        return "Native Vulkan is ready in \"" + profile.displayName() + "\". Start the game: it now draws with Vulkan "
                + "directly. If it does not start, remove VulkanMod again in Settings.";
    }

    /** Takes VulkanMod out of the instance, with the backup of the original jar. */
    public static String remove(SupersonicProfile profile) {
        File jar = findJarDeep(profile);
        if (jar == null) return "VulkanMod is not in this instance.";
        boolean ok = jar.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(jar.getPath() + ".orig").delete();
        return ok ? "VulkanMod was removed from \"" + profile.displayName() + "\"." : "Could not remove " + jar.getName() + ".";
    }

    private static void say(Progress progress, String text) {
        if (progress != null) progress.step(text);
    }
}
