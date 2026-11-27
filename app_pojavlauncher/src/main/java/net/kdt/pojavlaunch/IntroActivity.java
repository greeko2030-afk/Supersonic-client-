package net.kdt.pojavlaunch;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

/**
 * Intro screen shown every time the app is opened.
 * Plays a full screen video. A small text "Optimizing your mobile" and a
 * counter (1 -> 100) are shown at the bottom. The counter takes exactly 16 seconds.
 * After that the normal launcher flow (TestStorageActivity) is opened.
 */
public class IntroActivity extends Activity {
    private static final String TAG = "IntroActivity";
    private static final long INTRO_DURATION_MS = 16000L; // 16 seconds
    private static final int COUNT_FROM = 1;
    private static final int COUNT_TO = 100;

    private FrameLayout mRoot;
    private VideoView mVideoView;
    private TextView mCounterText;
    private ProgressBar mProgressBar;

    private ValueAnimator mCounterAnimator;
    private boolean mFinished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        setContentView(R.layout.activity_intro);
        hideSystemUi();

        mRoot = findViewById(R.id.intro_root);
        mVideoView = findViewById(R.id.intro_video);
        mCounterText = findViewById(R.id.intro_counter);
        mProgressBar = findViewById(R.id.intro_progress);

        setupCounter();
        setupVideo();
    }

    // ---------------------------------------------------------------- video

    private void setupVideo() {
        Uri uri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.intro_video);

        mVideoView.setOnPreparedListener(mp -> {
            mp.setLooping(true); // keeps playing if the video is shorter than 16 seconds
            fitVideoToScreen(mp.getVideoWidth(), mp.getVideoHeight());
            startCounterIfNeeded();
        });

        mVideoView.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "Video error what=" + what + " extra=" + extra);
            Toast.makeText(this, "Intro video error: " + what + " / " + extra,
                    Toast.LENGTH_LONG).show();
            startCounterIfNeeded(); // do not block the user if the video fails
            return true;
        });

        mVideoView.setVideoURI(uri);
        mVideoView.start();
    }

    /** Scales the video up (keeping the aspect ratio) so it covers the whole screen. */
    private void fitVideoToScreen(final int videoW, final int videoH) {
        if (videoW <= 0 || videoH <= 0) return;
        mRoot.post(() -> {
            int screenW = mRoot.getWidth();
            int screenH = mRoot.getHeight();
            if (screenW <= 0 || screenH <= 0) return;

            float scale = Math.max((float) screenW / videoW, (float) screenH / videoH);
            int targetW = Math.round(videoW * scale);
            int targetH = Math.round(videoH * scale);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(targetW, targetH, Gravity.CENTER);
            mVideoView.setLayoutParams(lp);
        });
    }

    // -------------------------------------------------------------- counter

    /** Counter 1 -> 100 over INTRO_DURATION_MS (linear, so it is smooth and steady). */
    private void setupCounter() {
        mCounterAnimator = ValueAnimator.ofInt(COUNT_FROM, COUNT_TO);
        mCounterAnimator.setDuration(INTRO_DURATION_MS);
        mCounterAnimator.setInterpolator(new LinearInterpolator());
        mCounterAnimator.addUpdateListener(animation -> {
            int value = (int) animation.getAnimatedValue();
            mCounterText.setText(getString(R.string.intro_percent_format, value));
            mProgressBar.setProgress(value);
        });
        mCounterAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Continue only when the animation really finished (not when cancelled).
                if (mCounterAnimator != null && mCounterAnimator.getAnimatedFraction() >= 1f) {
                    goToLauncher();
                }
            }
        });
    }

    private void startCounterIfNeeded() {
        if (mCounterAnimator != null && !mCounterAnimator.isStarted() && !mFinished) {
            mCounterAnimator.start();
        }
    }

    // ------------------------------------------------------------ navigation

    private void goToLauncher() {
        if (mFinished) return;
        mFinished = true;
        startActivity(new Intent(this, TestStorageActivity.class));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onPause() {
        super.onPause();
        if (mVideoView != null && mVideoView.isPlaying()) mVideoView.pause();
        if (mCounterAnimator != null && mCounterAnimator.isRunning()) mCounterAnimator.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (mVideoView != null && !mFinished) mVideoView.start();
        if (mCounterAnimator != null && mCounterAnimator.isPaused()) mCounterAnimator.resume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCounterAnimator != null) {
            mCounterAnimator.removeAllListeners();
            mCounterAnimator.cancel();
        }
        if (mVideoView != null) mVideoView.stopPlayback();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
