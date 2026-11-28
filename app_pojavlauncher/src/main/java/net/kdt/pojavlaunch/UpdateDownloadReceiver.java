package net.kdt.pojavlaunch;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives "download finished" from the system DownloadManager and starts the installation. */
public class UpdateDownloadReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        final long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        final Context app = context.getApplicationContext();

        // Copying a big APK can take a while, so it is done on a thread and the receiver ends now.
        // If the process is killed meanwhile, the next app start installs the downloaded file.
        new Thread(() -> AppUpdater.onDownloadFinished(app, id), "AppUpdater-install").start();
    }
}
