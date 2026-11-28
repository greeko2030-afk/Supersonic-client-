package net.kdt.pojavlaunch;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Intro screen shown every time the app is opened.
 *
 * Plays a full screen video (res/raw/intro_video.mp4). A small text
 * "Optimizing your mobile" and a counter (1 -> 100) are shown at the bottom.
 *
 * The counter follows the video position, so it reaches 100 exactly when the
 * video ends. Right after that the normal launcher flow (TestStorageActivity)
 * is opened.
 *
 * If the video cannot be played, a 16 second timer is used instead.
 * The timer uses SystemClock (not ValueAnimator), so it also works when the
 * phone has animations turned off.
 */
public class IntroActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = "IntroActivity";

    private static final long FALLBACK_DURATION_MS = 16000L; // only used if the video fails
    private static final long VIDEO_PREPARE_TIMEOUT_MS = 4000L;
    private static final long TICK_MS = 33L;                 // ~30 updates per second
    private static final int COUNT_FROM = 1;
    private static final int COUNT_TO = 100;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private FrameLayout mRoot;
    private TextureView mTextureView;
    private View mPoster;
    private TextView mCounterText;
    private ProgressBar mProgressBar;

    private Surface mSurface;
    private MediaPlayer mPlayer;
    private boolean mPlayerPrepared = false;
    private int mVideoDurationMs = 0;

    // Counter state
    private boolean mCounterStarted = false;
    private boolean mCounterRunning = false;
    private boolean mFollowVideo = false;   // true: counter follows the video, false: fallback timer
    private long mFallbackElapsedMs = 0;
    private long mLastTickTime = 0;
    private boolean mFinished = false;

    /** Used when the video does not start in time. */
    private final Runnable mFallbackStart = () -> {
        if (!mCounterStarted) {
            Log.w(TAG, "Video did not start in time, using the fallback timer");
            startCounter(false);
        }
    };

    private final Runnable mCounterTick = new Runnable() {
        @Override
        public void run() {
            if (!mCounterRunning || mFinished) return;

            float progress;
            if (mFollowVideo) {
                progress = getVideoProgress();
                if (progress > 0.02f) mPoster.setVisibility(View.GONE); // real video is visible
            } else {
                long now = SystemClock.uptimeMillis();
                mFallbackElapsedMs += now - mLastTickTime;
                mLastTickTime = now;
                progress = (float) mFallbackElapsedMs / FALLBACK_DURATION_MS;
                if (progress >= 1f) {
                    updateCounter(COUNT_TO);
                    goToLauncher();
                    return;
                }
            }
            updateCounter(COUNT_FROM + (int) ((COUNT_TO - COUNT_FROM) * Math.min(progress, 1f)));
            mHandler.postDelayed(this, TICK_MS);
        }
    };

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
        mPoster = findViewById(R.id.intro_poster);
        mCounterText = findViewById(R.id.intro_counter);
        mProgressBar = findViewById(R.id.intro_progress);

        mTextureView.setSurfaceTextureListener(this);
        mHandler.postDelayed(mFallbackStart, VIDEO_PREPARE_TIMEOUT_MS);
    }

    // ---------------------------------------------------------------- video

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
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
            mPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();

            mPlayer.setSurface(mSurface);
            mPlayer.setLooping(false); // play once, the intro ends when the video ends

            mPlayer.setOnVideoSizeChangedListener((mp, w, h) -> fitVideoToScreen(w, h));
            mPlayer.setOnPreparedListener(mp -> {
                mPlayerPrepared = true;
                mVideoDurationMs = mp.getDuration();
                fitVideoToScreen(mp.getVideoWidth(), mp.getVideoHeight());
                mp.start();
                startCounter(true); // counter follows the video
            });
            mPlayer.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    mPoster.setVisibility(View.GONE);
                }
                return false;
            });
            mPlayer.setOnCompletionListener(mp -> {
                // Video finished: counter is 100 and the app opens right now.
                if (mFollowVideo) {
                    updateCounter(COUNT_TO);
                    goToLauncher();
                }
            });
            mPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Video error what=" + what + " extra=" + extra);
                if (!mCounterStarted) startCounter(false); // video failed, use the timer
                return true;
            });
            mPlayer.prepareAsync();
        } catch (Throwable t) {
            Log.e(TAG, "Could not play intro video", t);
            if (!mCounterStarted) startCounter(false);
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

    /** Video progress from 0.0 to 1.0. */
    private float getVideoProgress() {
        try {
            if (mPlayer != null && mPlayerPrepared && mVideoDurationMs > 0) {
                return (float) mPlayer.getCurrentPosition() / mVideoDurationMs;
            }
        } catch (IllegalStateException ignored) { }
        return 0f;
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

    // -------------------------------------------------------------- counter

    private void updateCounter(int value) {
        mCounterText.setText(getString(R.string.intro_percent_format, value));
        mProgressBar.setProgress(value);
    }

    /** Starts the 1 -> 100 counter (only once). */
    private void startCounter(boolean followVideo) {
        if (mCounterStarted || mFinished) return;
        mHandler.removeCallbacks(mFallbackStart);
        mCounterStarted = true;
        mCounterRunning = true;
        mFollowVideo = followVideo;
        mFallbackElapsedMs = 0;
        mLastTickTime = SystemClock.uptimeMillis();
        updateCounter(COUNT_FROM);
        mHandler.post(mCounterTick);
    }

    // ------------------------------------------------------------ navigation

    private void goToLauncher() {
        if (mFinished) return;
        mFinished = true;
        mCounterRunning = false;
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
        if (mCounterRunning) {
            mCounterRunning = false;
            mHandler.removeCallbacks(mCounterTick);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        // Check GitHub releases in the background while the intro is playing.
        AppUpdater.checkInBackground(this);
        try {
            if (mPlayer != null && mPlayerPrepared && !mFinished) mPlayer.start();
        } catch (IllegalStateException ignored) { }
        if (mCounterStarted && !mCounterRunning && !mFinished) {
            mCounterRunning = true;
            mLastTickTime = SystemClock.uptimeMillis();
            mHandler.post(mCounterTick);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
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
