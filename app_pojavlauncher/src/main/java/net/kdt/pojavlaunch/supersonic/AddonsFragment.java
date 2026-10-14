package net.kdt.pojavlaunch.supersonic;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;

import net.kdt.pojavlaunch.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Real addon manager: search Modrinth, install with dependencies, enable/disable, remove and update mods. */
public class AddonsFragment extends BaseListFragment {
    public static final String TAG = "AddonsFragment";

    private static final String[] CHIPS = {"All", "Performance", "Shaders", "Utility", "Visuals", "Installed"};
    private static final String[] CATEGORIES = {null, "optimization", null, "utility", "decoration", null};
    private static final int CHIP_SHADERS = 2;
    private static final int CHIP_INSTALLED = 5;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mSearchRunnable;
    private SupersonicProfile mProfile;
    private List<ModrinthClient.Installed> mInstalled = new ArrayList<>();
    private int mChip = 0;
    private String mQuery = "";
    private int mRequestId = 0;

    @Override
    protected int spanCount() {
        return 2;
    }

    @Override
    protected void onViewReady() {
        setHeader(R.string.supersonic_addons_title, R.string.supersonic_addons_subtitle, R.drawable.ic_nav_addons);
        mSearch.setVisibility(View.VISIBLE);
        mChipsScroll.setVisibility(View.VISIBLE);
        SupersonicUi.buildChips(mChips, CHIPS, mChip, index -> {
            mChip = index;
            reload();
        });

        mSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (mSearchRunnable != null) mHandler.removeCallbacks(mSearchRunnable);
                mSearchRunnable = () -> {
                    mQuery = s.toString().trim();
                    reload();
                };
                mHandler.postDelayed(mSearchRunnable, 600);
            }
        });
        mSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                if (mSearchRunnable != null) mHandler.removeCallbacks(mSearchRunnable);
                mQuery = mSearch.getText().toString().trim();
                reload();
                return true;
            }
            return false;
        });
        reload();
    }

    @Override
    public void onDestroyView() {
        if (mSearchRunnable != null) mHandler.removeCallbacks(mSearchRunnable);
        super.onDestroyView();
    }

    @Override
    protected RowAdapter.Listener createListener() {
        return new RowAdapter.Listener() {
            @Override
            public void onPrimary(RowAdapter.Row row) {
                if (row.tag instanceof ModrinthClient.Hit) {
                    install((ModrinthClient.Hit) row.tag);
                }
            }

            @Override
            public void onSecondary(RowAdapter.Row row) {
                if (row.tag instanceof ModrinthClient.Installed) {
                    confirmRemove((ModrinthClient.Installed) row.tag);
                }
            }

            @Override
            public void onToggle(RowAdapter.Row row, boolean checked) {
                if (row.tag instanceof ModrinthClient.Installed) {
                    ModrinthClient.Installed installed = (ModrinthClient.Installed) row.tag;
                    if (!ModrinthClient.setEnabled(installed, checked)) {
                        toast("Could not change this addon");
                        reload();
                    }
                }
            }
        };
    }

    private void toast(String message) {
        if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    private ModrinthClient.Installed findInstalled(String projectId) {
        for (ModrinthClient.Installed item : mInstalled) {
            if (projectId != null && projectId.equals(item.projectId)) return item;
        }
        return null;
    }

    private void reload() {
        mProfile = SupersonicProfile.current();
        if (mProfile == null) {
            showSummary("No instance selected", null, null);
            setRows(new ArrayList<>(), "Create or select an instance first (Home → Instances).");
            return;
        }
        final SupersonicProfile profile = mProfile;
        final int chip = mChip;
        final String query = mQuery;
        final boolean shaders = chip == CHIP_SHADERS;

        if (!shaders && !profile.supportsMods()) {
            showSummary(profile.displayName() + " • " + profile.mcVersion + " • no mod loader", null, null);
            setRows(new ArrayList<>(), "This instance has no mod loader, so mods cannot be installed.\n"
                    + "Create a Fabric, Quilt or Forge instance (Home → Instances → New) and select it.");
            return;
        }

        final int request = ++mRequestId;
        setBusy(true);
        runBg(() -> {
            List<ModrinthClient.Installed> installed = ModrinthClient.scanInstalled(profile.modsDir());
            List<ModrinthClient.Hit> found = new ArrayList<>();
            String error = null;
            if (chip != CHIP_INSTALLED) {
                try {
                    found = ModrinthClient.search(query, shaders ? "shader" : "mod", CATEGORIES[chip],
                            profile.mcVersion, shaders ? null : profile.loader,
                            query.isEmpty() ? "downloads" : "relevance", 40);
                } catch (IOException e) {
                    error = "Could not reach Modrinth. Check your internet connection.";
                }
            }
            final List<ModrinthClient.Installed> installedFinal = installed;
            final List<ModrinthClient.Hit> foundFinal = found;
            final String errorFinal = error;
            ui(() -> {
                if (request != mRequestId) return;
                mInstalled = installedFinal;
                setBusy(false);
                showResults(profile, chip, query, foundFinal, errorFinal);
            });
        });
    }

    private void showResults(SupersonicProfile profile, int chip, String query,
                             List<ModrinthClient.Hit> hits, String error) {
        int installedCount = 0;
        for (ModrinthClient.Installed item : mInstalled) if (!item.disabled) installedCount++;
        showSummary(String.format(Locale.US, "Installed: %d  •  %s %s", installedCount,
                profile.loader, profile.mcVersion), "Update All", v -> updateAll());

        List<RowAdapter.Row> rows = new ArrayList<>();
        if (chip == CHIP_INSTALLED) {
            for (ModrinthClient.Installed item : mInstalled) {
                if (!query.isEmpty() && !item.title.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) continue;
                RowAdapter.Row row = new RowAdapter.Row();
                row.id = item.projectId != null ? item.projectId : item.file.getName();
                row.title = item.title;
                row.subtitle = item.file.getName();
                row.iconUrl = item.iconUrl;
                row.iconRes = R.drawable.ic_nav_addons;
                row.badge = item.disabled ? "Disabled" : "✓ Enabled";
                row.checked = !item.disabled;
                row.secondaryLabel = "Remove";
                row.tag = item;
                rows.add(row);
            }
            setRows(rows, "No addons installed in this instance yet.");
            return;
        }

        for (ModrinthClient.Hit hit : hits) {
            RowAdapter.Row row = new RowAdapter.Row();
            row.id = hit.projectId;
            row.title = hit.title;
            row.subtitle = (hit.author == null ? "" : "by " + hit.author + "  •  ")
                    + SupersonicUi.formatCount(hit.downloads) + " downloads";
            row.description = hit.description;
            row.iconUrl = hit.iconUrl;
            row.iconRes = R.drawable.ic_nav_addons;
            ModrinthClient.Installed installed = chip == CHIP_SHADERS ? null : findInstalled(hit.projectId);
            if (installed != null) {
                row.badge = "✓ Installed";
                row.checked = !installed.disabled;
                row.secondaryLabel = "Remove";
                row.tag = installed;
            } else {
                row.primaryLabel = getString(R.string.supersonic_install);
                row.tag = hit;
            }
            rows.add(row);
        }
        setRows(rows, error != null ? error : "Nothing found for this Minecraft version and loader.");
    }

    private void install(ModrinthClient.Hit hit) {
        final SupersonicProfile profile = mProfile;
        if (profile == null) return;
        final boolean shaders = mChip == CHIP_SHADERS;
        final Set<String> installedIds = ModrinthClient.projectIds(mInstalled);
        setBusy(true);
        runBg(() -> {
            String message;
            try {
                int count = ModrinthClient.install(hit.projectId, shaders, profile, installedIds, new HashSet<>(), 0);
                message = count > 0 ? hit.title + " installed" + (count > 1 ? " (+" + (count - 1) + " required)" : "")
                        : hit.title + " is already installed";
            } catch (IOException e) {
                message = "Install failed: " + e.getMessage();
            }
            final String finalMessage = message;
            ui(() -> {
                toast(finalMessage);
                reload();
            });
        });
    }

    private void updateAll() {
        final SupersonicProfile profile = mProfile;
        if (profile == null || !profile.supportsMods()) {
            toast("Select an instance with a mod loader first");
            return;
        }
        setBusy(true);
        final List<ModrinthClient.Installed> snapshot = new ArrayList<>(mInstalled);
        runBg(() -> {
            String message;
            try {
                int updated = ModrinthClient.updateAll(profile, snapshot);
                message = updated == 0 ? "Everything is up to date" : updated + " addon(s) updated";
            } catch (IOException e) {
                message = "Update failed: " + e.getMessage();
            }
            final String finalMessage = message;
            ui(() -> {
                toast(finalMessage);
                reload();
            });
        });
    }

    private void confirmRemove(ModrinthClient.Installed installed) {
        new AlertDialog.Builder(requireContext())
                .setTitle(installed.title)
                .setMessage("Remove this addon from the instance?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    //noinspection ResultOfMethodCallIgnored
                    installed.file.delete();
                    reload();
                })
                .show();
    }
}
