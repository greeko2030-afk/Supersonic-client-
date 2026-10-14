package net.kdt.pojavlaunch.supersonic;

import java.io.File;

public class ResourcePacksFragment extends FolderManagerFragment {
    public static final String TAG = "ResourcePacksFragment";

    @Override
    protected File folder(SupersonicProfile profile) {
        return profile.resourcePacksDir();
    }

    @Override
    protected boolean isWorlds() {
        return false;
    }
}
