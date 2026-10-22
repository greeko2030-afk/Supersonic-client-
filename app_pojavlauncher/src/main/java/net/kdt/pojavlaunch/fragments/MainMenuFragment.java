package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.openPath;
import static net.kdt.pojavlaunch.Tools.shareLog;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.SupersonicNavBar;
import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.ModIconCache;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.supersonic.AgentFragment;
import net.kdt.pojavlaunch.supersonic.InstancesFragment;
import net.kdt.pojavlaunch.supersonic.ModrinthClient;
import net.kdt.pojavlaunch.supersonic.PerformanceModes;
import net.kdt.pojavlaunch.supersonic.ResourcePacksFragment;
import net.kdt.pojavlaunch.supersonic.SupersonicProfile;
import net.kdt.pojavlaunch.supersonic.SupersonicUi;
import net.kdt.pojavlaunch.supersonic.VulkanEngine;
import net.kdt.pojavlaunch.supersonic.WorldsFragment;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    /** Mods shown in "Quick optimization": {Modrinth slug, display name, short description} */
    private static final String[][] QUICK_MODS = {
            {"sodium", "Sodium", "FPS boost"},
            {"iris", "Iris", "Shaders"},
            {"lithium", "Lithium", "Performance"},
            {"entityculling", "Entity Culling", "Rendering"}
    };

    private mcVersionSpinner mVersionSpinner;
    private TextView mRamValue, mGameRamValue, mPingValue, mPerfChip, mQuickStatus, mModpackStatus;
    private ProgressBar mRamBar;
    private LinearLayout mQuickTiles, mModpackCards;
    private ModIconCache mIconCache;

    public MainMenuFragment(){
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mCustomControlButton = view.findViewById(R.id.custom_control_button);
        Button mInstallJarButton = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton = view.findViewById(R.id.share_logs_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);

        View mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        mRamValue = view.findViewById(R.id.home_stat_ram_value);
        mRamBar = view.findViewById(R.id.home_stat_ram_bar);
        mGameRamValue = view.findViewById(R.id.home_stat_game_ram_value);
        mPingValue = view.findViewById(R.id.home_stat_ping_value);
        mPerfChip = view.findViewById(R.id.home_perf_chip);
        mQuickStatus = view.findViewById(R.id.home_quick_opt_status);
        mQuickTiles = view.findViewById(R.id.home_quick_opt_tiles);
        mModpackStatus = view.findViewById(R.id.home_modpacks_status);
        mModpackCards = view.findViewById(R.id.home_modpack_cards);

        mCustomControlButton.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation(false));
        mInstallJarButton.setOnLongClickListener(v->{
            runInstallerWithConfirmation(true);
            return true;
        });
        mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));

        mPlayButton.setOnClickListener(v -> ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));

        mShareLogsButton.setOnClickListener((v) -> shareLog(requireContext()));

        mOpenDirectoryButton.setOnClickListener((v)-> {
            Tools.switchDemo(Tools.isDemoProfile(v.getContext())); // avoid switching accounts being able to access
            if(Tools.isDemoProfile(v.getContext())){
                Toast.makeText(v.getContext(), R.string.toast_not_available_demo, Toast.LENGTH_LONG).show();
                return;
            }

            openPath(v.getContext(), getCurrentProfileDirectory(), false);
        });

        // Hidden feature of the stock launcher: long-press "Custom controls" opens the gamepad mapper
        mCustomControlButton.setOnLongClickListener((v)->{
            Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
            return true;
        });

        view.findViewById(R.id.home_quick_opt_manage).setOnClickListener(v -> openTab(SupersonicNavBar.TAB_ADDONS));
        view.findViewById(R.id.home_modpacks_browse).setOnClickListener(v -> openTab(SupersonicNavBar.TAB_MODPACKS));
        View.OnClickListener openAgent = v -> {
            Bundle args = new Bundle();
            args.putBoolean(AgentFragment.ARG_SCAN, true);
            Tools.swapFragment(requireActivity(), AgentFragment.class, AgentFragment.TAG, args);
        };
        view.findViewById(R.id.home_agent_scan).setOnClickListener(openAgent);
        view.findViewById(R.id.home_agent_card).setOnClickListener(openAgent);
        view.findViewById(R.id.home_lib_instances).setOnClickListener(v ->
                Tools.swapFragment(requireActivity(), InstancesFragment.class, InstancesFragment.TAG, null));
        view.findViewById(R.id.home_lib_resource_packs).setOnClickListener(v ->
                Tools.swapFragment(requireActivity(), ResourcePacksFragment.class, ResourcePacksFragment.TAG, null));
        view.findViewById(R.id.home_lib_worlds).setOnClickListener(v ->
                Tools.swapFragment(requireActivity(), WorldsFragment.class, WorldsFragment.TAG, null));

        mIconCache = new ModIconCache();
    }

    private void openTab(int tab) {
        if (requireActivity() instanceof LauncherActivity) {
            ((LauncherActivity) requireActivity()).openTab(tab);
        }
    }

    private File getCurrentProfileDirectory() {
        String currentProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        if(!Tools.isValidString(currentProfile)) return new File(Tools.DIR_GAME_NEW);
        LauncherProfiles.load();
        MinecraftProfile profileObject = LauncherProfiles.mainProfileJson.profiles.get(currentProfile);
        if(profileObject == null) return new File(Tools.DIR_GAME_NEW);
        return Tools.getGameDirPath(profileObject);
    }

    /** The renderer that the selected instance sets for itself, or null. */
    @Nullable
    private String getCurrentProfileRenderer() {
        String key = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        if(!Tools.isValidString(key) || LauncherProfiles.mainProfileJson == null) return null;
        MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(key);
        return profile == null ? null : profile.pojavRendererName;
    }

    @Override
    public void onResume() {
        super.onResume();
        mVersionSpinner.reloadProfiles();
        refreshStats();
        loadQuickOptimization();
        loadFeaturedModpacks();
    }

    private void toast(String message) {
        if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    /** Runs on the UI thread only while this screen is still visible. */
    private void ui(Runnable runnable) {
        Tools.runOnUiThread(() -> {
            if (isAdded() && getView() != null) runnable.run();
        });
    }

    // ------------------------------------------------------------------ stats (all measured, nothing is made up)

    private void refreshStats() {
        Context context = requireContext();
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(info);
        float totalGb = info.totalMem / (1024f * 1024f * 1024f);
        float usedGb = (info.totalMem - info.availMem) / (1024f * 1024f * 1024f);
        mRamValue.setText(String.format(Locale.US, "%.1f / %.1f GB", usedGb, totalGb));
        mRamBar.setProgress(info.totalMem == 0 ? 0 : (int) (100f * (info.totalMem - info.availMem) / info.totalMem));
        mGameRamValue.setText(LauncherPreferences.PREF_RAM_ALLOCATION + " MB");
        String engine = VulkanEngine.MODE_AUTO.equals(VulkanEngine.mode())
                ? "  \u2022  Engine: " + VulkanEngine.preview(context, getCurrentProfileRenderer()).shortLabel()
                : "";
        mPerfChip.setText("Performance: " + PerformanceModes.currentName(context) + engine);

        mPingValue.setText("…");
        PojavApplication.sExecutorService.execute(() -> {
            long ms = measurePing();
            ui(() -> mPingValue.setText(ms < 0 ? "--" : ms + " ms"));
        });
    }

    /** Real network latency: time to open a TCP connection to Mojang's launcher servers. */
    private static long measurePing() {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("launchermeta.mojang.com", 443), 3000);
            return (System.nanoTime() - start) / 1_000_000;
        } catch (IOException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ quick optimization

    private void loadQuickOptimization() {
        final SupersonicProfile profile = SupersonicProfile.current();
        PojavApplication.sExecutorService.execute(() -> {
            List<ModrinthClient.Installed> installed = new ArrayList<>();
            if (profile != null && profile.supportsMods()) installed = ModrinthClient.scanInstalled(profile.modsDir());
            Map<String, ModrinthClient.Hit> info = new HashMap<>();
            try {
                List<String> slugs = new ArrayList<>();
                for (String[] mod : QUICK_MODS) slugs.add(mod[0]);
                for (ModrinthClient.Hit hit : ModrinthClient.projects(slugs)) info.put(hit.slug, hit);
            } catch (IOException ignored) { }
            final List<ModrinthClient.Installed> installedFinal = installed;
            ui(() -> buildQuickTiles(profile, installedFinal, info));
        });
    }

    private void buildQuickTiles(SupersonicProfile profile, List<ModrinthClient.Installed> installed,
                                 Map<String, ModrinthClient.Hit> info) {
        mQuickTiles.removeAllViews();
        Set<String> installedSlugs = new HashSet<>();
        for (ModrinthClient.Installed item : installed) {
            if (item.slug != null && !item.disabled) installedSlugs.add(item.slug);
        }
        boolean canInstall = profile != null && profile.supportsMods();
        mQuickStatus.setText(profile == null ? getString(R.string.supersonic_home_quick_opt_desc)
                : canInstall ? profile.displayName() + " • " + profile.loader + " " + profile.mcVersion
                : "Select a Fabric, Quilt or Forge instance to install mods");

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (String[] mod : QUICK_MODS) {
            final String slug = mod[0];
            View tile = inflater.inflate(R.layout.item_quick_tile, mQuickTiles, false);
            ((TextView) tile.findViewById(R.id.tile_title)).setText(mod[1]);
            TextView status = tile.findViewById(R.id.tile_status);
            final boolean isInstalled = installedSlugs.contains(slug);
            status.setText(isInstalled ? "✓ " + getString(R.string.supersonic_installed)
                    : canInstall ? getString(R.string.supersonic_install) : mod[2]);
            status.setTextColor(getResources().getColor(isInstalled
                    ? R.color.supersonic_accent_light : R.color.secondary_text));

            ModrinthClient.Hit hit = info.get(slug);
            if (hit != null && hit.iconUrl != null) {
                final ImageView icon = tile.findViewById(R.id.tile_icon);
                mIconCache.getImage(bitmap -> icon.setImageBitmap(bitmap), "qo_" + slug, hit.iconUrl);
            }

            tile.setOnClickListener(v -> {
                if (profile == null || !profile.supportsMods()) {
                    toast("Select a Fabric, Quilt or Forge instance first (Library → Instances)");
                } else if (isInstalled) {
                    toast(mod[1] + " is already installed. Manage it in Addons.");
                } else {
                    installQuickMod(profile, mod[1], slug, installed);
                }
            });
            mQuickTiles.addView(tile);
        }
    }

    private void installQuickMod(SupersonicProfile profile, String name, String slug,
                                 List<ModrinthClient.Installed> installed) {
        toast("Installing " + name + "…");
        final Set<String> ids = ModrinthClient.projectIds(installed);
        PojavApplication.sExecutorService.execute(() -> {
            String message;
            try {
                int count = ModrinthClient.install(slug, false, profile, ids, new HashSet<>(), 0);
                message = count > 0 ? name + " installed" : name + " is already installed";
            } catch (IOException e) {
                message = "Could not install " + name + ": " + e.getMessage();
            }
            final String finalMessage = message;
            ui(() -> {
                toast(finalMessage);
                loadQuickOptimization();
            });
        });
    }

    // ------------------------------------------------------------------ featured modpacks

    private void loadFeaturedModpacks() {
        mModpackStatus.setVisibility(View.VISIBLE);
        mModpackStatus.setText("Loading…");
        PojavApplication.sExecutorService.execute(() -> {
            List<ModrinthClient.Hit> hits = new ArrayList<>();
            boolean failed = false;
            try {
                hits = ModrinthClient.search("", "modpack", null, null, null, "downloads", 2);
            } catch (IOException e) {
                failed = true;
            }
            final List<ModrinthClient.Hit> hitsFinal = hits;
            final boolean failedFinal = failed;
            ui(() -> {
                mModpackCards.removeAllViews();
                if (failedFinal || hitsFinal.isEmpty()) {
                    mModpackStatus.setVisibility(View.VISIBLE);
                    mModpackStatus.setText("Could not load modpacks. Check your internet connection.");
                    return;
                }
                mModpackStatus.setVisibility(View.GONE);
                LayoutInflater inflater = LayoutInflater.from(requireContext());
                for (ModrinthClient.Hit hit : hitsFinal) {
                    View card = inflater.inflate(R.layout.item_modpack_card, mModpackCards, false);
                    ((TextView) card.findViewById(R.id.card_title)).setText(hit.title);
                    ((TextView) card.findViewById(R.id.card_subtitle)).setText(hit.author == null ? "" : "by " + hit.author);
                    ((TextView) card.findViewById(R.id.card_stats)).setText("⬇ " + SupersonicUi.formatCount(hit.downloads));
                    String image = hit.imageUrl != null ? hit.imageUrl : hit.iconUrl;
                    if (image != null) {
                        final ImageView banner = card.findViewById(R.id.card_banner);
                        mIconCache.getImage(bitmap -> banner.setImageBitmap(bitmap), "mp_" + hit.projectId, image);
                    }
                    card.findViewById(R.id.card_install).setOnClickListener(v -> openTab(SupersonicNavBar.TAB_MODPACKS));
                    mModpackCards.addView(card);
                }
            });
        });
    }

    private void runInstallerWithConfirmation(boolean isCustomArgs) {
        // Local (offline) accounts may run installers. Only demo accounts are restricted.
        if(Tools.isDemoProfile(requireContext())){
            Toast.makeText(requireContext(), R.string.toast_not_available_demo, Toast.LENGTH_LONG).show();
            return;
        }

        if (ProgressKeeper.getTaskCount() == 0)
            Tools.installMod(requireActivity(), isCustomArgs);
        else
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }
}
