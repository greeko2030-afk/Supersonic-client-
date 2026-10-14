package net.kdt.pojavlaunch.supersonic;

import java.io.File;

public class WorldsFragment extends FolderManagerFragment {
    public static final String TAG = "WorldsFragment";

    @Override
    protected File folder(SupersonicProfile profile) {
        return profile.savesDir();
    }

    @Override
    protected boolean isWorlds() {
        return true;
    }
}
