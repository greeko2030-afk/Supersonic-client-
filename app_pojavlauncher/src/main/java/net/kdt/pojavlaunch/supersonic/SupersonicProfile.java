package net.kdt.pojavlaunch.supersonic;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The currently selected instance: its folder, Minecraft version and mod loader. */
public final class SupersonicProfile {
    public static final String LOADER_VANILLA = "vanilla";
    public static final String LOADER_FABRIC = "fabric";
    public static final String LOADER_QUILT = "quilt";
    public static final String LOADER_FORGE = "forge";
    public static final String LOADER_NEOFORGE = "neoforge";
    public static final String LOADER_OPTIFINE = "optifine";

    public final String key;
    public final MinecraftProfile profile;
    public final File gameDir;
    public final String versionId;
    public final String mcVersion;
    public final String loader;

    private SupersonicProfile(String key, MinecraftProfile profile) {
        this.key = key;
        this.profile = profile;
        this.gameDir = Tools.getGameDirPath(profile);
        String id = profile.lastVersionId == null ? "" : profile.lastVersionId;
        try {
            id = AsyncMinecraftDownloader.normalizeVersionId(id);
        } catch (Throwable ignored) { }
        this.versionId = id;
        String[] parsed = parse(id);
        this.mcVersion = parsed[0];
        this.loader = parsed[1];
    }

    /** @return the selected instance, or null if there is none */
    @Nullable
    public static SupersonicProfile current() {
        LauncherProfiles.load();
        if (LauncherProfiles.mainProfileJson == null) return null;
        String key = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
        MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(key);
        if (profile == null) return null;
        return new SupersonicProfile(key, profile);
    }

    @Nullable
    public static SupersonicProfile of(String key) {
        if (LauncherProfiles.mainProfileJson == null) return null;
        MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(key);
        return profile == null ? null : new SupersonicProfile(key, profile);
    }

    private static final Pattern FABRIC = Pattern.compile("^fabric-loader-[0-9.]+(?:-beta\\.[0-9]+)?-(.+)$");
    private static final Pattern QUILT = Pattern.compile("^quilt-loader-[0-9.]+(?:-[a-z]+\\.[0-9]+)?-(.+)$");
    private static final Pattern FORGE = Pattern.compile("^(.+)-forge-[0-9.]+.*$");
    private static final Pattern NEOFORGE = Pattern.compile("^neoforge-(\\d+)\\.(\\d+)\\..*$");

    /** @return {minecraftVersion, loader} */
    static String[] parse(String id) {
        Matcher m = FABRIC.matcher(id);
        if (m.matches()) return new String[]{m.group(1), LOADER_FABRIC};
        m = QUILT.matcher(id);
        if (m.matches()) return new String[]{m.group(1), LOADER_QUILT};
        m = FORGE.matcher(id);
        if (m.matches()) return new String[]{m.group(1), LOADER_FORGE};
        m = NEOFORGE.matcher(id);
        if (m.matches()) {
            int major = Integer.parseInt(m.group(1));
            int minor = Integer.parseInt(m.group(2));
            String mc = minor == 0 ? "1." + major : "1." + major + "." + minor;
            return new String[]{mc, LOADER_NEOFORGE};
        }
        if (id.contains("OptiFine")) {
            int dash = id.indexOf('-');
            return new String[]{dash > 0 ? id.substring(0, dash) : id, LOADER_OPTIFINE};
        }
        return new String[]{id, LOADER_VANILLA};
    }

    /** True for loaders that Modrinth mods can be installed for. */
    public boolean supportsMods() {
        return !LOADER_VANILLA.equals(loader) && !LOADER_OPTIFINE.equals(loader);
    }

    public File modsDir() { return new File(gameDir, "mods"); }
    public File shadersDir() { return new File(gameDir, "shaderpacks"); }
    public File resourcePacksDir() { return new File(gameDir, "resourcepacks"); }
    public File savesDir() { return new File(gameDir, "saves"); }

    public String displayName() {
        return profile.name == null || profile.name.isEmpty() ? key : profile.name;
    }

    /** True when this version understands --quickPlayMultiplayer (Minecraft 1.20+ / snapshots 23w14a+). */
    public boolean supportsQuickPlay() {
        Matcher release = Pattern.compile("^1\\.(\\d+).*$").matcher(mcVersion);
        if (release.matches()) return Integer.parseInt(release.group(1)) >= 20;
        Matcher snapshot = Pattern.compile("^(\\d{2})w\\d{2}[a-z]$").matcher(mcVersion);
        if (snapshot.matches()) return Integer.parseInt(snapshot.group(1)) >= 23;
        return false;
    }
}
