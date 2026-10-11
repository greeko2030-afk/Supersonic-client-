package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;

/**
 * Placeholder for Supersonic tabs whose feature is not implemented yet (Addons, Servers).
 * Arguments: ARG_TAB (SupersonicNavBar tab id), ARG_TITLE, ARG_ICON, ARG_DESCRIPTION (resource ids).
 */
public class ComingSoonFragment extends Fragment {
    public static final String TAG = "ComingSoonFragment";
    public static final String ARG_TAB = "supersonic_tab";
    public static final String ARG_TITLE = "supersonic_title";
    public static final String ARG_ICON = "supersonic_icon";
    public static final String ARG_DESCRIPTION = "supersonic_description";

    public ComingSoonFragment() {
        super(R.layout.fragment_coming_soon);
    }

    /** Builds the arguments bundle for Tools.swapFragment(...). */
    public static Bundle args(int tab, int titleRes, int iconRes, int descriptionRes) {
        Bundle bundle = new Bundle();
        bundle.putInt(ARG_TAB, tab);
        bundle.putInt(ARG_TITLE, titleRes);
        bundle.putInt(ARG_ICON, iconRes);
        bundle.putInt(ARG_DESCRIPTION, descriptionRes);
        return bundle;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle bundle = getArguments();
        if (bundle == null) return;
        ((TextView) view.findViewById(R.id.coming_soon_title)).setText(bundle.getInt(ARG_TITLE));
        ((TextView) view.findViewById(R.id.coming_soon_description)).setText(bundle.getInt(ARG_DESCRIPTION));
        ((ImageView) view.findViewById(R.id.coming_soon_icon)).setImageResource(bundle.getInt(ARG_ICON));
    }
}
