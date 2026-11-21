package net.kdt.pojavlaunch.supersonic;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.TextView;

import net.kdt.pojavlaunch.PojavApplication;

import java.io.IOException;
import java.util.List;

/** The settings dialog of native Vulkan (VulkanMod): status, install / repair, remove. */
public final class NativeVulkanDialog {
    private NativeVulkanDialog() {}

    private static final String EXPLANATION =
            "VulkanMod draws the game with Vulkan directly: nothing is translated from OpenGL while you play. "
                    + "It replaces Sodium-style rendering, so Sodium, Iris and shader packs do not work with it.\n\n"
                    + "Installing downloads VulkanMod from Modrinth and prepares it for Android once. "
                    + "This is experimental: if the game does not start, open this dialog again and tap Remove.";

    /** @param onChanged runs on the UI thread after an install or removal, so the screen can refresh */
    public static void show(final Activity activity, final Runnable onChanged) {
        final SupersonicProfile profile = SupersonicProfile.current();
        final boolean present = NativeVulkan.findJarDeep(profile) != null;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("Native Vulkan (VulkanMod)")
                .setMessage(NativeVulkan.status(activity) + "\n\n" + EXPLANATION)
                .setNegativeButton("Close", null)
                .setPositiveButton(present ? "Repair" : "Install", (d, w) -> confirmAndInstall(activity, profile, onChanged));
        if (present) {
            builder.setNeutralButton("Remove", (d, w) -> {
                String message = NativeVulkan.remove(profile);
                done(activity, "Native Vulkan", message, onChanged);
            });
        }
        builder.show();
    }

    private static void confirmAndInstall(final Activity activity, final SupersonicProfile profile, final Runnable onChanged) {
        final List<String> conflicts = NativeVulkan.conflicts(profile);
        if (conflicts.isEmpty()) {
            runInstall(activity, profile, onChanged);
            return;
        }
        StringBuilder names = new StringBuilder();
        for (String name : conflicts) names.append("\n• ").append(name);
        new AlertDialog.Builder(activity)
                .setTitle("These mods conflict with VulkanMod")
                .setMessage("VulkanMod and these mods change the same part of the game, so the game may crash:" + names
                        + "\n\nRemove them in Addons first, or install anyway.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Install anyway", (d, w) -> runInstall(activity, profile, onChanged))
                .show();
    }

    private static void runInstall(final Activity activity, final SupersonicProfile profile, final Runnable onChanged) {
        final TextView text = new TextView(activity);
        text.setPadding(60, 40, 60, 20);
        text.setText("Starting…");
        final AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle("Native Vulkan")
                .setView(text)
                .setCancelable(false)
                .show();
        PojavApplication.sExecutorService.execute(() -> {
            String result;
            try {
                result = NativeVulkan.install(activity, profile, step -> activity.runOnUiThread(() -> text.setText(step)));
            } catch (IOException | RuntimeException e) {
                result = "Could not install native Vulkan:\n" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final String finalResult = result;
            activity.runOnUiThread(() -> {
                progress.dismiss();
                done(activity, "Native Vulkan", finalResult, onChanged);
            });
        });
    }

    private static void done(Activity activity, String title, String message, Runnable onChanged) {
        new AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton(android.R.string.ok, null).show();
        if (onChanged != null) onChanged.run();
    }
}
