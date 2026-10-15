package net.kdt.pojavlaunch.supersonic;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.util.Locale;

/**
 * Performance presets of the Supersonic launcher: Battery saver, Balanced and Ultra.
 *
 * A preset changes real launcher settings, nothing is simulated:
 * game RAM, resolution scale, sustained performance and big core affinity.
 * The selected preset is stored as a String ("0", "1" or "2") under {@link #PREF_KEY},
 * which is the key of the list preference in pref_video.xml.
 */
public final class PerformanceModes {
    private PerformanceModes() {}

    public static final int BATTERY = 0;
    public static final int BALANCED = 1;
    public static final int ULTRA = 2;

    /** Key of the "Performance mode" list preference (see pref_video.xml). */
    public static final String PREF_KEY = "supersonic_performance_mode";

    /** The Agent warns when the game gets more than this share of the device RAM. */
    private static final double MAX_RAM_SHARE = 0.6;
    /** A 32-bit process cannot address much memory, so it is limited like the stock launcher does. */
    private static final int MAX_RAM_32_BIT = 696;

    private static boolean isValid(int mode) {
        return mode == BATTERY || mode == BALANCED || mode == ULTRA;
    }

    /** @return the total RAM of this device in MB (0 if it cannot be read) */
    public static int totalRamMb(Context context) {
        return Tools.getTotalDeviceMemory(context);
    }

    /** @return the selected preset, {@link #BALANCED} when nothing valid is stored */
    public static int current(Context context) {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null) return BALANCED;
        try {
            int mode = Integer.parseInt(prefs.getString(PREF_KEY, String.valueOf(BALANCED)));
            return isValid(mode) ? mode : BALANCED;
        } catch (ClassCastException | NumberFormatException e) {
            return BALANCED;
        }
    }

    /** Display name of a preset. */
    public static String name(int mode) {
        switch (mode) {
            case BATTERY: return "Battery saver";
            case ULTRA: return "Ultra";
            default: return "Balanced";
        }
    }

    /** Display name of the selected preset. */
    public static String currentName(Context context) {
        return name(current(context));
    }

    /**
     * Applies a preset: writes the real settings and reloads the launcher preferences.
     *
     * @return a short human readable summary of what was changed
     */
    public static String apply(Context context, int mode) {
        if (!isValid(mode)) mode = BALANCED;

        int ram = ramFor(context, mode, totalRamMb(context));
        int resolution = resolutionFor(context, mode);
        // Battery saver and Balanced keep the clocks stable, Ultra lets the CPU run at full speed
        boolean sustained = mode != ULTRA;
        boolean bigCores = mode == ULTRA;

        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs != null) {
            prefs.edit()
                    .putString(PREF_KEY, String.valueOf(mode))
                    .putInt("allocation", ram)
                    .putInt("resolutionRatio", resolution)
                    .putBoolean("sustainedPerformance", sustained)
                    .putBoolean("bigCoreAffinity", bigCores)
                    .apply();
            LauncherPreferences.loadPreferences(context);
        }

        return String.format(Locale.US, "%s: %d MB RAM, %d%% resolution%s%s",
                name(mode), ram, resolution,
                sustained ? ", sustained performance" : "",
                bigCores ? ", big cores" : "");
    }

    /** Game RAM in MB for a preset, always a valid value of the RAM slider. */
    private static int ramFor(Context context, int mode, int totalMb) {
        double share;
        int floor;
        int ceiling;
        switch (mode) {
            case BATTERY:
                share = 0.25;
                floor = 512;
                ceiling = 1536;
                break;
            case ULTRA:
                share = 0.50;
                floor = 1536;
                ceiling = 4096;
                break;
            default:
                share = 0.40;
                floor = 1024;
                ceiling = 3072;
                break;
        }

        int ram = (int) (totalMb * share);
        ram = Math.max(floor, Math.min(ceiling, ram));
        // Android needs RAM of its own, never give the game more than the safe share
        if (totalMb > 0) ram = Math.min(ram, (int) (totalMb * MAX_RAM_SHARE));
        if (Architecture.is32BitsDevice()) ram = Math.min(ram, MAX_RAM_32_BIT);

        int min = context.getResources().getInteger(R.integer.memory_seekbar_min);
        int step = context.getResources().getInteger(R.integer.memory_seekbar_increment);
        ram = Math.max(min, ram);
        if (step > 0) ram -= ram % step;
        return Math.max(min, ram);
    }

    /** Resolution scale in percent for a preset, always a valid value of the resolution slider. */
    private static int resolutionFor(Context context, int mode) {
        if (mode == ULTRA) return 100;

        int targetSide = mode == BATTERY ? 720 : 1080;
        int min = context.getResources().getInteger(R.integer.resolution_seekbar_min);
        int step = context.getResources().getInteger(R.integer.resolution_seekbar_increment);

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int minSide = Math.min(metrics.widthPixels, metrics.heightPixels);

        int percent = 100;
        if (minSide > targetSide && step > 0) {
            // Scale the shortest side of the screen down to the target, rounded up to the slider step
            percent = (int) (Math.ceil(100.0 * targetSide / minSide / step) * step);
        }
        // Battery saver always renders a little below the native size
        if (mode == BATTERY) percent = Math.min(percent, 75);
        return Math.max(min, Math.min(100, percent));
    }
}
