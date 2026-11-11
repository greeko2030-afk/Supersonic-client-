package net.kdt.pojavlaunch.supersonic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chat with SS Agent. Type in any language: the AI answers in the same language. The agent can look at and
 * change the launcher and Minecraft with tools; changes ask for approval and can be undone.
 * Opened from the "game exited" dialog with {@link #ARG_CRASH}, it analyzes the crash by itself.
 */
public class AgentChatFragment extends Fragment implements AgentSession.Listener {
    public static final String TAG = "AgentChatFragment";
    /** Set when the screen is opened after a crash. */
    public static final String ARG_CRASH = "crash";

    private final AgentSession mSession = AgentSession.get();

    private LinearLayout mMessages;
    private ScrollView mScroll;
    private EditText mInput;
    private Button mSend;
    private Button mUndo;
    private TextView mStatus;
    private TextView mModelLabel;
    private LinearLayout mChips;
    private int mRendered = 0;

    private volatile CountDownLatch mPendingLatch;
    private final AtomicInteger mPendingAnswer = new AtomicInteger(AgentSession.DENY);
    private volatile AlertDialog mPendingDialog;

    // ------------------------------------------------------------------ lifecycle

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return buildUi(inflater.getContext());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mRendered = 0;
        mSession.setListener(this);
        refreshHeader();
        sync();
        updateRunning(mSession.isRunning());

        Bundle args = getArguments();
        if (args != null && args.getBoolean(ARG_CRASH, false)) {
            args.putBoolean(ARG_CRASH, false); // do not repeat after a rotation
            startCrashAnalysis();
        }
    }

    @Override
    public void onDestroyView() {
        mSession.setListener(null);
        // a dialog waiting for an answer can no longer be answered: deny it so that the agent can go on
        AlertDialog dialog = mPendingDialog;
        if (dialog != null) dialog.dismiss();
        CountDownLatch latch = mPendingLatch;
        if (latch != null) {
            mPendingAnswer.set(AgentSession.DENY);
            latch.countDown();
        }
        super.onDestroyView();
    }

    // ------------------------------------------------------------------ building the screen

    private View buildUi(Context context) {
        int accent = ContextCompat.getColor(context, R.color.supersonic_accent);
        int idle = ContextCompat.getColor(context, R.color.supersonic_nav_idle);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 12);
        root.setPadding(pad, pad, pad, pad);

        // header: title, current AI, buttons
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText("SS Agent");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFFFFFF);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mModelLabel = new TextView(context);
        mModelLabel.setTextSize(12);
        mModelLabel.setTextColor(idle);
        mModelLabel.setOnClickListener(v -> openSettings());
        header.addView(mModelLabel);
        root.addView(header);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        mUndo = smallButton(context, "Undo changes", v -> confirmUndo());
        buttons.addView(mUndo);
        buttons.addView(smallButton(context, "New chat", v -> {
            if (!mSession.reset()) Toast.makeText(requireContext(), "Stopping… tap New chat again in a moment", Toast.LENGTH_SHORT).show();
            else sync();
        }));
        buttons.addView(smallButton(context, "AI settings", v -> openSettings()));
        root.addView(buttons);

        // messages
        mScroll = new ScrollView(context);
        mMessages = new LinearLayout(context);
        mMessages.setOrientation(LinearLayout.VERTICAL);
        mScroll.addView(mMessages);
        root.addView(mScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // quick starts (shown while the chat is empty)
        mChips = new LinearLayout(context);
        mChips.setOrientation(LinearLayout.VERTICAL);
        for (String text : new String[]{
                "Check why my game crashed and fix it",
                "Make Minecraft run faster on this phone",
                "Check my mods for problems"}) {
            mChips.addView(smallButton(context, text, v -> submit(text)));
        }
        root.addView(mChips);

        mStatus = new TextView(context);
        mStatus.setTextSize(12);
        mStatus.setTextColor(accent);
        mStatus.setVisibility(View.GONE);
        mStatus.setText("SS Agent is working…");
        root.addView(mStatus);

        // input
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        mInput = new EditText(context);
        mInput.setHint("Ask or tell SS Agent what to do (any language)");
        mInput.setHintTextColor(idle);
        mInput.setTextColor(0xFFFFFFFF);
        mInput.setMaxLines(4);
        mInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        inputRow.addView(mInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mSend = new Button(context);
        mSend.setText("Send");
        mSend.setAllCaps(false);
        mSend.setOnClickListener(v -> {
            if (mSession.isRunning()) {
                mSession.cancel();
                return;
            }
            String text = mInput.getText().toString().trim();
            if (!text.isEmpty()) submit(text);
        });
        inputRow.addView(mSend);
        root.addView(inputRow);
        return root;
    }

    private Button smallButton(Context context, String text, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setOnClickListener(listener);
        return button;
    }

    private static int dp(Context context, float value) {
        return SupersonicUi.dp(context, value);
    }

    // ------------------------------------------------------------------ transcript

    /** Draws every chat line that is not on the screen yet. UI thread only. */
    private void sync() {
        if (mMessages == null) return;
        List<AgentSession.Entry> all = mSession.transcript();
        if (all.size() < mRendered) {
            mMessages.removeAllViews();
            mRendered = 0;
        }
        for (int i = mRendered; i < all.size(); i++) addBubble(all.get(i));
        mRendered = all.size();
        mChips.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        refreshUndo();
        mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void addBubble(AgentSession.Entry entry) {
        Context context = mMessages.getContext();
        int accent = ContextCompat.getColor(context, R.color.supersonic_accent);
        int card = ContextCompat.getColor(context, R.color.supersonic_card);
        int stroke = ContextCompat.getColor(context, R.color.supersonic_card_stroke);
        int idle = ContextCompat.getColor(context, R.color.supersonic_nav_idle);

        TextView view = new TextView(context);
        view.setText(entry.text);
        view.setTextIsSelectable(true);
        view.setTextSize(15);
        view.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 6);
        int inner = dp(context, 10);

        switch (entry.kind) {
            case AgentSession.USER:
                view.setBackground(bubble(accent, accent, context));
                view.setPadding(inner, inner, inner, inner);
                params.gravity = Gravity.END;
                params.leftMargin = dp(context, 48);
                break;
            case AgentSession.TOOL:
                view.setText("⚙ " + entry.text);
                view.setTextSize(12);
                view.setTypeface(Typeface.MONOSPACE);
                view.setTextColor(idle);
                break;
            case AgentSession.ERROR:
                view.setBackground(bubble(0x33FF5A5A, 0xFFFF5A5A, context));
                view.setPadding(inner, inner, inner, inner);
                break;
            case AgentSession.NOTE:
                view.setBackground(bubble(card, accent, context));
                view.setPadding(inner, inner, inner, inner);
                break;
            default: // assistant
                view.setBackground(bubble(card, stroke, context));
                view.setPadding(inner, inner, inner, inner);
                params.rightMargin = dp(context, 24);
        }
        mMessages.addView(view, params);
    }

    private static GradientDrawable bubble(int fill, int stroke, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, 14));
        drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    private void refreshHeader() {
        Context context = requireContext();
        AiProvider provider = AiConfig.provider(context);
        mModelLabel.setText(provider.label + " · " + AiConfig.model(context, provider));
    }

    private void refreshUndo() {
        Context context = requireContext();
        int count = AgentUndo.count(context);
        boolean any = count > 0 || AgentEngine.hasUndo(context);
        mUndo.setVisibility(any ? View.VISIBLE : View.GONE);
        mUndo.setEnabled(!mSession.isRunning());
    }

    private void updateRunning(boolean running) {
        if (mSend == null) return;
        mSend.setText(running ? "Stop" : "Send");
        mStatus.setVisibility(running ? View.VISIBLE : View.GONE);
        mInput.setEnabled(!running);
        refreshUndo();
    }

    // ------------------------------------------------------------------ AgentSession.Listener (background thread)

    @Override
    public void onEntry(AgentSession.Entry entry) {
        runOnUi(this::sync);
    }

    @Override
    public void onState(boolean running) {
        runOnUi(() -> updateRunning(running));
    }

    @Override
    public int approve(String title, String detail) {
        final Activity activity = getActivity();
        if (activity == null) return AgentSession.DENY;
        final CountDownLatch latch = new CountDownLatch(1);
        mPendingAnswer.set(AgentSession.DENY);
        mPendingLatch = latch;
        activity.runOnUiThread(() -> {
            if (!isAdded()) {
                latch.countDown();
                return;
            }
            mPendingDialog = new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(detail)
                    .setCancelable(false)
                    .setPositiveButton("Allow", (d, w) -> {
                        mPendingAnswer.set(AgentSession.ALLOW);
                        latch.countDown();
                    })
                    .setNegativeButton("Deny", (d, w) -> {
                        mPendingAnswer.set(AgentSession.DENY);
                        latch.countDown();
                    })
                    .setNeutralButton("Allow all in this chat", (d, w) -> {
                        mPendingAnswer.set(AgentSession.ALLOW_ALL);
                        latch.countDown();
                    })
                    .show();
        });
        try {
            if (!latch.await(10, TimeUnit.MINUTES)) mPendingAnswer.set(AgentSession.DENY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mPendingAnswer.set(AgentSession.DENY);
        }
        mPendingLatch = null;
        final AlertDialog dialog = mPendingDialog;
        mPendingDialog = null;
        if (dialog != null) activity.runOnUiThread(dialog::dismiss);
        return mPendingAnswer.get();
    }

    private void runOnUi(Runnable action) {
        Activity activity = getActivity();
        if (activity != null) activity.runOnUiThread(() -> {
            if (isAdded() && mMessages != null) action.run();
        });
    }

    // ------------------------------------------------------------------ actions

    private void submit(String text) {
        final Context context = requireContext();
        ensureReady(() -> {
            mInput.setText("");
            mSession.send(context, text);
        }, null);
    }

    /** Makes sure the AI is set up and the user agreed to send data. */
    private void ensureReady(Runnable onReady, Runnable onCancel) {
        final Context context = requireContext();
        AiProvider provider = AiConfig.provider(context);
        if (!AiConfig.isReady(context, provider)) {
            AgentSettingsDialog.show(context, () -> {
                refreshHeader();
                if (AiConfig.isReady(context, AiConfig.provider(context))) ensureReady(onReady, onCancel);
                else if (onCancel != null) onCancel.run();
            });
            return;
        }
        if (!AiConfig.hasConsent(context)) {
            new AlertDialog.Builder(context)
                    .setTitle("Send data to " + provider.label + "?")
                    .setMessage("SS Agent sends your messages, and what it reads to help you, to " + provider.label + ":\n\n"
                            + "• phone model, Android version and RAM\n"
                            + "• game version, mod loader and mod file names\n"
                            + "• log text and files it opens (access tokens, e-mails and IP addresses are removed from logs)\n\n"
                            + "Account data and API keys can never be read by the AI. Every change it wants to make "
                            + "asks for your approval first, and can be undone.")
                    .setNegativeButton(android.R.string.cancel, (d, w) -> {
                        if (onCancel != null) onCancel.run();
                    })
                    .setPositiveButton("Allow", (d, w) -> {
                        AiConfig.setConsent(context, true);
                        onReady.run();
                    })
                    .show();
            return;
        }
        onReady.run();
    }

    private void startCrashAnalysis() {
        final Context context = requireContext().getApplicationContext();
        mSession.reset();
        mStatus.setText("Checking the crash…");
        mStatus.setVisibility(View.VISIBLE);
        new Thread(() -> {
            final AgentSession.CrashReport report = AgentSession.localCrashReport(context);
            runOnUi(() -> {
                mStatus.setText("SS Agent is working…");
                mStatus.setVisibility(mSession.isRunning() ? View.VISIBLE : View.GONE);
                mSession.note("The game exited with an error.\n\n" + report.text);
                sync();
                String request = AgentAi.CRASH_REQUEST;
                if (!report.fixed.isEmpty()) {
                    request += "\n\nSS Agent already fixed these automatically, do not repeat them:\n" + report.fixed;
                }
                final String finalRequest = request;
                ensureReady(() -> mSession.send(requireContext(), finalRequest),
                        () -> mSession.note("The AI was not used. You can still ask SS Agent anything below."));
            });
        }, "SSAgentCrash").start();
    }

    private void confirmUndo() {
        final Context context = requireContext();
        new AlertDialog.Builder(context)
                .setTitle("Undo SS Agent changes?")
                .setMessage("This puts back every file, mod and setting that SS Agent changed in this and earlier chats.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Undo", (d, w) -> {
                    final Context app = context.getApplicationContext();
                    new Thread(() -> {
                        String result = AgentUndo.undoEverything(app);
                        runOnUi(() -> {
                            mSession.note(result);
                            sync();
                        });
                    }, "SSAgentUndo").start();
                })
                .show();
    }

    private void openSettings() {
        AgentSettingsDialog.show(requireContext(), this::refreshHeader);
    }
}
