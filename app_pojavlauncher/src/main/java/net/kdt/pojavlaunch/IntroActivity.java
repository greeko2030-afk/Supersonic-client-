package net.kdt.pojavlaunch;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Intro screen shown every time the app is opened.
 * Plays a full screen video (res/raw/intro_video.mp4). A small text
 * "Optimizing your mobile" and a counter (1 -> 100) are shown at the bottom.
 * The counter takes exactly 16 seconds, then the normal launcher flow
 * (TestStorageActivity) is opened.
 */
public class IntroActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = "IntroActivity";

    /** Shows a small yellow debug text at the top left. Set to false when the video works. */
    private static final boolean SHOW_DEBUG = true;

    private static final long INTRO_DURATION_MS = 16000L; // 16 seconds
    private static final int COUNT_FROM = 1;
    private static final int COUNT_TO = 100;

    private FrameLayout mRoot;
    private TextureView mTextureView;
    private TextView mCounterText;
    private TextView mDebugText;
    private ProgressBar mProgressBar;

    private Surface mSurface;
    private MediaPlayer mPlayer;
    private boolean mPlayerPrepared = false;

    private ValueAnimator mCounterAnimator;
    private boolean mFinished = false;
    private final StringBuilder mDebugLog = new StringBuilder();

    private final Runnable mStartCounterFallback = this::startCounterIfNeeded;

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
        mTextureView = findViewById(R.id.intro_texture);
        mCounterText = findViewById(R.id.intro_counter);
        mProgressBar = findViewById(R.id.intro_progress);
        mDebugText = findViewById(R.id.intro_debug);
        if (SHOW_DEBUG) mDebugText.setVisibility(View.VISIBLE);

        setupCounter();
        mTextureView.setSurfaceTextureListener(this);

        // Safety: if the video is slow or broken, the counter still starts after 2.5 seconds.
        mRoot.postDelayed(mStartCounterFallback, 2500);
    }

    // ---------------------------------------------------------------- video

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        debug("surface ready " + width + "x" + height);
        mSurface = new Surface(st);
        startPlayer();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) { }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        releasePlayer();
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    private void startPlayer() {
        releasePlayer();
        try {
            mPlayer = new MediaPlayer();

            AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.intro_video);
            debug("video file size: " + afd.getLength() + " bytes");
            mPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();

            mPlayer.setSurface(mSurface);
            mPlayer.setLooping(true); // keeps playing if the video is shorter than 16 seconds

            mPlayer.setOnVideoSizeChangedListener((mp, w, h) -> {
                debug("video size " + w + "x" + h);
                fitVideoToScreen(w, h);
            });
            mPlayer.setOnPreparedListener(mp -> {
                mPlayerPrepared = true;
                debug("prepared, duration " + mp.getDuration() + " ms");
                fitVideoToScreen(mp.getVideoWidth(), mp.getVideoHeight());
                mp.start();
                startCounterIfNeeded();
            });
            mPlayer.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    debug("first frame shown");
                }
                return false;
            });
            mPlayer.setOnErrorListener((mp, what, extra) -> {
                debug("ERROR what=" + what + " extra=" + extra);
                Log.e(TAG, "Video error what=" + what + " extra=" + extra);
                startCounterIfNeeded(); // do not block the user if the video fails
                return true;
            });
            mPlayer.prepareAsync();
        } catch (Throwable t) {
            debug("EXCEPTION " + t);
            Log.e(TAG, "Could not play intro video", t);
            startCounterIfNeeded();
        }
    }

    private void releasePlayer() {
        mPlayerPrepared = false;
        if (mPlayer != null) {
            try {
                mPlayer.release();
            } catch (Throwable ignored) { }
            mPlayer = null;
        }
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

            mTextureView.setLayoutParams(
                    new FrameLayout.LayoutParams(targetW, targetH, Gravity.CENTER));
        });
    }

    private void debug(String message) {
        Log.d(TAG, message);
        if (!SHOW_DEBUG || mDebugText == null) return;
        mDebugLog.append(message).append('\n');
        mDebugText.setText(mDebugLog.toString().trim());
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
        try {
            if (mPlayer != null && mPlayerPrepared && mPlayer.isPlaying()) mPlayer.pause();
        } catch (IllegalStateException ignored) { }
        if (mCounterAnimator != null && mCounterAnimator.isRunning()) mCounterAnimator.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        try {
            if (mPlayer != null && mPlayerPrepared && !mFinished) mPlayer.start();
        } catch (IllegalStateException ignored) { }
        if (mCounterAnimator != null && mCounterAnimator.isPaused()) mCounterAnimator.resume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mRoot != null) mRoot.removeCallbacks(mStartCounterFallback);
        if (mCounterAnimator != null) {
            mCounterAnimator.removeAllListeners();
            mCounterAnimator.cancel();
        }
        releasePlayer();
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
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
