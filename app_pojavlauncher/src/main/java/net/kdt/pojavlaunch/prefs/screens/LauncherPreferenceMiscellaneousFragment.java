package net.kdt.pojavlaunch.prefs.screens;

import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.supersonic.SupersonicRepoMods;
import net.kdt.pojavlaunch.utils.GLInfoUtils;

public class LauncherPreferenceMiscellaneousFragment extends LauncherPreferenceFragment {
    @Override
    public void onCreatePreferences(Bundle b, String str) {
        addPreferencesFromResource(R.xml.pref_misc);
        Preference driverPreference = requirePreference("zinkPreferSystemDriver");
        PackageManager packageManager = driverPreference.getContext().getPackageManager();
        boolean supportsTurnip = Tools.checkVulkanSupport(packageManager) && GLInfoUtils.getGlInfo().isAdreno();
        driverPreference.setVisible(supportsTurnip);
        setupRepoModsCheck();
    }

    /** The button checks the mod repository now and shows what happened with every mod. */
    private void setupRepoModsCheck() {
        Preference check = requirePreference("supersonic_repo_mods_check");
        final CharSequence idleSummary = check.getSummary();
        check.setOnPreferenceClickListener(preference -> {
            preference.setEnabled(false);
            preference.setSummary("Checking...");
            SupersonicRepoMods.checkNow(report -> Tools.runOnUiThread(() -> {
                preference.setEnabled(true);
                preference.setSummary(idleSummary);
                if(!isAdded()) return;
                new AlertDialog.Builder(requireContext())
                        .setTitle("Supersonic mods")
                        .setMessage(report)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }));
            return true;
        });
    }
}
