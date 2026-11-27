package net.kdt.pojavlaunch;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
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
 * Plays a full screen video (res/raw/intro_video.mp4). A small text
 * "Optimizing your mobile" and a counter (1 -> 100) are shown at the bottom.
 * The counter takes exactly 16 seconds, then the normal launcher flow
 * (TestStorageActivity) is opened.
 *
 * NOTE: the counter uses a Handler + SystemClock instead of ValueAnimator,
 * because ValueAnimator finishes instantly when the phone has animations
 * turned off (Animator duration scale = off, battery saver, etc).
 */
public class IntroActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = "IntroActivity";

    /** Shows a small yellow debug box at the top left. Set to false when everything works. */
    private static final boolean SHOW_DEBUG = true;

    private static final long INTRO_DURATION_MS = 16000L; // 16 seconds
    private static final long TICK_MS = 33L;              // ~30 updates per second
    private static final int COUNT_FROM = 1;
    private static final int COUNT_TO = 100;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private FrameLayout mRoot;
    private TextureView mTextureView;
    private View mPoster;
    private TextView mCounterText;
    private TextView mDebugText;
    private ProgressBar mProgressBar;

    private Surface mSurface;
    private MediaPlayer mPlayer;
    private boolean mPlayerPrepared = false;
    private boolean mFirstFrameShown = false;

    // Counter state
    private boolean mCounterStarted = false;
    private boolean mCounterRunning = false;
    private long mCounterElapsedMs = 0;
    private long mLastTickTime = 0;
    private boolean mFinished = false;

    private final StringBuilder mDebugLog = new StringBuilder();

    private final Runnable mStartCounterFallback = this::startCounterIfNeeded;

    private final Runnable mCounterTick = new Runnable() {
        @Override
        public void run() {
            if (!mCounterRunning || mFinished) return;
            long now = SystemClock.uptimeMillis();
            mCounterElapsedMs += now - mLastTickTime;
            mLastTickTime = now;

            if (mCounterElapsedMs >= INTRO_DURATION_MS) {
                updateCounter(COUNT_TO);
                debug("counter finished");
                goToLauncher();
                return;
            }
            int value = COUNT_FROM + (int) ((COUNT_TO - COUNT_FROM) * mCounterElapsedMs / INTRO_DURATION_MS);
            updateCounter(value);
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
        mDebugText = findViewById(R.id.intro_debug);
        if (SHOW_DEBUG) mDebugText.setVisibility(View.VISIBLE);

        debug("IntroActivity v5 started");
        debug("device: " + Build.MODEL + ", Android API " + Build.VERSION.SDK_INT);
        debug("H.264 decoder: " + findAvcDecoder());
        debug("animator scale: " + getAnimatorScale() + " (0 = animations off)");

        mTextureView.setSurfaceTextureListener(this);

        // Safety: if the video is slow or broken, the counter still starts after 4 seconds.
        mHandler.postDelayed(mStartCounterFallback, 4000);
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
            });
            mPlayer.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    mFirstFrameShown = true;
                    debug("first frame shown");
                    mPoster.setVisibility(View.GONE); // real video is visible now
                    startCounterIfNeeded();           // counter starts together with the video
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

    // -------------------------------------------------------------- counter

    private void updateCounter(int value) {
        mCounterText.setText(getString(R.string.intro_percent_format, value));
        mProgressBar.setProgress(value);
    }

    /** Starts the 1 -> 100 counter (only once). */
    private void startCounterIfNeeded() {
        if (mCounterStarted || mFinished) return;
        mCounterStarted = true;
        mCounterRunning = true;
        mCounterElapsedMs = 0;
        mLastTickTime = SystemClock.uptimeMillis();
        updateCounter(COUNT_FROM);
        mHandler.post(mCounterTick);
        debug("counter started");
    }

    private void pauseCounter() {
        if (mCounterRunning) {
            mCounterRunning = false;
            mHandler.removeCallbacks(mCounterTick);
        }
    }

    private void resumeCounter() {
        if (mCounterStarted && !mCounterRunning && !mFinished) {
            mCounterRunning = true;
            mLastTickTime = SystemClock.uptimeMillis();
            mHandler.post(mCounterTick);
        }
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
        pauseCounter();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        try {
            if (mPlayer != null && mPlayerPrepared && !mFinished) mPlayer.start();
        } catch (IllegalStateException ignored) { }
        resumeCounter();
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

    // ----------------------------------------------------------------- debug

    private float getAnimatorScale() {
        try {
            return Settings.Global.getFloat(getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Throwable t) {
            return -1f;
        }
    }

    /** Returns the name of the phone's H.264 decoder, or "NONE". Only used for debug info. */
    private String findAvcDecoder() {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase("video/avc")) return info.getName();
                }
            }
        } catch (Throwable t) {
            return "check failed: " + t;
        }
        return "NONE";
    }

    private void debug(String message) {
        Log.d(TAG, message);
        if (!SHOW_DEBUG || mDebugText == null) return;
        mDebugLog.append(message).append('\n');
        mDebugText.setText(mDebugLog.toString().trim());
    }
}
