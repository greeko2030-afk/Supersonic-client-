package net.kdt.pojavlaunch.supersonic;

import android.app.AlertDialog;
import android.widget.Toast;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.fragments.ProfileEditorFragment;
import net.kdt.pojavlaunch.fragments.ProfileTypeSelectFragment;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lists, selects, edits and deletes the game profiles (instances). */
public class InstancesFragment extends BaseListFragment {
    public static final String TAG = "InstancesFragment";

    @Override
    protected int spanCount() {
        return 1;
    }

    @Override
    protected void onViewReady() {
        setHeader(R.string.supersonic_instances_title, R.string.supersonic_instances_subtitle, R.drawable.ic_nav_instances);
        reload();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mAdapter != null) reload();
    }

    @Override
    protected RowAdapter.Listener createListener() {
        return new RowAdapter.Listener() {
            @Override
            public void onPrimary(RowAdapter.Row row) {
                select(row.id);
                reload();
            }

            @Override
            public void onSecondary(RowAdapter.Row row) {
                select(row.id);
                Tools.swapFragment(requireActivity(), ProfileEditorFragment.class, ProfileEditorFragment.TAG, null);
            }

            @Override
            public void onTertiary(RowAdapter.Row row) {
                confirmDelete(row.id, row.title);
            }
        };
    }

    private void select(String key) {
        LauncherPreferences.DEFAULT_PREF.edit().putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key).apply();
    }

    private void reload() {
        LauncherProfiles.load();
        List<RowAdapter.Row> rows = new ArrayList<>();
        if (LauncherProfiles.mainProfileJson != null) {
            String selected = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
            List<Map.Entry<String, MinecraftProfile>> entries =
                    new ArrayList<>(LauncherProfiles.mainProfileJson.profiles.entrySet());
            Collections.sort(entries, (a, b) -> nameOf(a).compareToIgnoreCase(nameOf(b)));
            for (Map.Entry<String, MinecraftProfile> entry : entries) {
                SupersonicProfile profile = SupersonicProfile.of(entry.getKey());
                if (profile == null) continue;
                RowAdapter.Row row = new RowAdapter.Row();
                row.id = entry.getKey();
                row.title = nameOf(entry);
                row.subtitle = profile.versionId + (SupersonicProfile.LOADER_VANILLA.equals(profile.loader)
                        ? "" : "  •  " + profile.loader);
                row.description = profile.gameDir.getAbsolutePath();
                boolean isSelected = entry.getKey().equals(selected);
                row.badge = isSelected ? "✓ Selected" : "";
                row.iconRes = iconFor(profile.loader);
                row.primaryLabel = isSelected ? null : "Select";
                row.secondaryLabel = "Edit";
                row.tertiaryLabel = "Delete";
                rows.add(row);
            }
        }
        showSummary(String.format(Locale.US, "%d instance%s", rows.size(), rows.size() == 1 ? "" : "s"), "New",
                v -> Tools.swapFragment(requireActivity(), ProfileTypeSelectFragment.class,
                        ProfileTypeSelectFragment.TAG, null));
        setRows(rows, "No instances yet.");
    }

    private static String nameOf(Map.Entry<String, MinecraftProfile> entry) {
        String name = entry.getValue().name;
        return name == null || name.isEmpty() ? entry.getKey() : name;
    }

    private static int iconFor(String loader) {
        if (SupersonicProfile.LOADER_FABRIC.equals(loader)) return R.drawable.ic_fabric;
        if (SupersonicProfile.LOADER_QUILT.equals(loader)) return R.drawable.ic_quilt;
        return R.drawable.ic_pojav_full;
    }

    private void confirmDelete(String key, String title) {
        if (LauncherProfiles.mainProfileJson == null || LauncherProfiles.mainProfileJson.profiles.size() <= 1) {
            Toast.makeText(requireContext(), "You need at least one instance", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage("Delete this instance? Its worlds and mods in a separate game folder are not deleted.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    LauncherProfiles.mainProfileJson.profiles.remove(key);
                    LauncherProfiles.write();
                    String current = LauncherPreferences.DEFAULT_PREF.getString(
                            LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
                    if (key.equals(current)) {
                        String first = LauncherProfiles.mainProfileJson.profiles.keySet().iterator().next();
                        select(first);
                    }
                    reload();
                })
                .show();
    }
}
