package net.kdt.pojavlaunch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives the result of the PackageInstaller session (confirm needed / success / failure). */
public class UpdateInstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        AppUpdater.onInstallStatus(context.getApplicationContext(), intent);
    }
}
