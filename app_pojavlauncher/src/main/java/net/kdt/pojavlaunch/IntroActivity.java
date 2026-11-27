package net.kdt.pojavlaunch;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Intro screen shown every time the app is opened.
 * Plays a full screen video with the text "Optimizing your mobile"
 * and a counter that goes from 1 to 100 in exactly 16 seconds.
 * After that it opens the normal launcher flow (TestStorageActivity).
 */
public class IntroActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "IntroActivity";
    private static final long INTRO_DURATION_MS = 16000L; // 16 seconds
    private static final int COUNT_FROM = 1;
    private static final int COUNT_TO = 100;

    private SurfaceView mSurfaceView;
    private TextView mCounterText;
    private ProgressBar mProgressBar;

    private MediaPlayer mMediaPlayer;
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

        mSurfaceView = findViewById(R.id.intro_surface);
        mCounterText = findViewById(R.id.intro_counter);
        mProgressBar = findViewById(R.id.intro_progress);

        mSurfaceView.getHolder().addCallback(this);
        setupCounter();
    }

    /** Counter 1 -> 100 over INTRO_DURATION_MS. */
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
                // Only continue when the animation really finished (not when cancelled).
                if (mCounterAnimator != null && mCounterAnimator.getAnimatedFraction() >= 1f) {
                    goToLauncher();
                }
            }
        });
    }

    // ---------------------------------------------------------------- video

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startVideo(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releasePlayer();
    }

    private void startVideo(SurfaceHolder holder) {
        releasePlayer();
        try {
            mMediaPlayer = new MediaPlayer();
            AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.intro_video);
            mMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mMediaPlayer.setDisplay(holder);
            // Fill the whole screen (crop the edges instead of black bars)
            mMediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mMediaPlayer.setLooping(true); // safety: keeps playing if the video is shorter than 16s
            mMediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
                startCounterIfNeeded();
            });
            mMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Video error what=" + what + " extra=" + extra);
                startCounterIfNeeded(); // do not block the user if the video fails
                return true;
            });
            mMediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Could not play intro video", e);
            startCounterIfNeeded();
        }
    }

    private void startCounterIfNeeded() {
        if (mCounterAnimator != null && !mCounterAnimator.isStarted() && !mFinished) {
            mCounterAnimator.start();
        }
    }

    private void releasePlayer() {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.release();
            } catch (Exception ignored) { }
            mMediaPlayer = null;
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
        if (mMediaPlayer != null && mMediaPlayer.isPlaying()) mMediaPlayer.pause();
        if (mCounterAnimator != null && mCounterAnimator.isRunning()) mCounterAnimator.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (mMediaPlayer != null && !mMediaPlayer.isPlaying()) {
            try { mMediaPlayer.start(); } catch (IllegalStateException ignored) { }
        }
        if (mCounterAnimator != null && mCounterAnimator.isPaused()) mCounterAnimator.resume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCounterAnimator != null) {
            mCounterAnimator.removeAllListeners();
            mCounterAnimator.cancel();
        }
        releasePlayer();
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
