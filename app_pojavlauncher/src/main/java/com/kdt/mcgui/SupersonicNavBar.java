package com.kdt.mcgui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import net.kdt.pojavlaunch.R;

/**
 * Navigation used by the Supersonic Client launcher.
 * Horizontal orientation = bottom bar (portrait), vertical orientation = side rail (landscape).
 * The bar only reports taps; the host activity decides which fragment to show.
 */
public class SupersonicNavBar extends LinearLayout {
    public static final int TAB_HOME = 0;
    public static final int TAB_MODPACKS = 1;
    public static final int TAB_ADDONS = 2;
    public static final int TAB_SERVERS = 3;
    public static final int TAB_SETTINGS = 4;
    public static final int TAB_COUNT = 5;

    public interface OnTabSelectedListener {
        void onTabSelected(int tab);
    }

    @DrawableRes
    private static final int[] ICONS = {
            R.drawable.ic_menu_home,
            R.drawable.ic_nav_modpacks,
            R.drawable.ic_nav_addons,
            R.drawable.ic_nav_servers,
            R.drawable.ic_menu_settings
    };

    @StringRes
    private static final int[] LABELS = {
            R.string.supersonic_nav_home,
            R.string.supersonic_nav_modpacks,
            R.string.supersonic_nav_addons,
            R.string.supersonic_nav_servers,
            R.string.supersonic_nav_settings
    };

    private final View[] mItems = new View[TAB_COUNT];
    private OnTabSelectedListener mListener;
    private int mSelectedTab = -1;

    public SupersonicNavBar(@NonNull Context context) {
        this(context, null);
    }

    public SupersonicNavBar(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        buildItems();
    }

    private void buildItems() {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        boolean vertical = getOrientation() == VERTICAL;
        for (int i = 0; i < TAB_COUNT; i++) {
            final int tab = i;
            View item = inflater.inflate(R.layout.view_supersonic_nav_item, this, false);
            ((ImageView) item.findViewById(R.id.nav_item_icon)).setImageResource(ICONS[i]);
            ((TextView) item.findViewById(R.id.nav_item_label)).setText(LABELS[i]);
            item.setOnClickListener(v -> {
                if (mListener != null) mListener.onTabSelected(tab);
            });

            LayoutParams params = vertical
                    ? new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
                    : new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            addView(item, params);
            mItems[i] = item;
        }
    }

    public void setOnTabSelectedListener(@Nullable OnTabSelectedListener listener) {
        mListener = listener;
    }

    public int getSelectedTab() {
        return mSelectedTab;
    }

    /** Only changes the highlight, never calls the listener. */
    public void setSelectedTab(int tab) {
        mSelectedTab = tab;
        for (int i = 0; i < TAB_COUNT; i++) {
            mItems[i].setSelected(i == tab);
        }
    }
}
