package net.kdt.pojavlaunch.supersonic;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import java.util.Locale;

/** Small UI helpers shared by the Supersonic screens. */
public final class SupersonicUi {
    private SupersonicUi() {}

    public interface OnIndex {
        void onIndex(int index);
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Fills a horizontal container with selectable pill chips. */
    public static void buildChips(LinearLayout container, String[] labels, int selected, OnIndex listener) {
        container.removeAllViews();
        Context context = container.getContext();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView chip = new TextView(context);
            chip.setText(labels[i]);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setTextColor(ContextCompat.getColorStateList(context, R.color.chip_text));
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
            chip.setSelected(i == selected);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(dp(context, 8));
            chip.setLayoutParams(params);
            chip.setOnClickListener(v -> {
                selectChip(container, index);
                listener.onIndex(index);
            });
            container.addView(chip);
        }
    }

    public static void selectChip(LinearLayout container, int index) {
        for (int i = 0; i < container.getChildCount(); i++) {
            container.getChildAt(i).setSelected(i == index);
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    public static String formatCount(long value) {
        if (value >= 1_000_000) return String.format(Locale.US, "%.1fM", value / 1_000_000.0);
        if (value >= 1_000) return String.format(Locale.US, "%.1fK", value / 1_000.0);
        return String.valueOf(value);
    }
}
