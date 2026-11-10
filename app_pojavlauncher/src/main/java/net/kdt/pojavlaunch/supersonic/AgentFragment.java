package net.kdt.pojavlaunch.supersonic;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import net.kdt.pojavlaunch.R;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * SS Agent: the AI crash assistant.
 * <ul>
 *   <li>After a crash ({@link #ARG_CRASH}) it analyzes the log, detects missing mods, mods that do not fit
 *   the game version and bad JVM arguments, fixes what is safe (undoable) and explains the crash with AI.</li>
 *   <li>It can also scan on demand, explain the last crash, answer questions and clean / optimize / reset.</li>
 * </ul>
 */
public class AgentFragment extends BaseListFragment {
    public static final String TAG = "AgentFragment";
    public static final String ARG_SCAN = "scan";
    /** Set when the screen is opened from the "game exited" dialog after a crash. */
    public static final String ARG_CRASH = "crash";

    private static final String ACT_AI_CRASH = "act_ai_crash";
    private static final String ACT_AI_ASK = "act_ai_ask";
    private static final String ACT_AI_SETTINGS = "act_ai_settings";
    private static final String ACT_UNDO = "act_undo";
    private static final String ACT_FIX = "act_fix";
    private static final String ACT_OPTIMIZE = "act_optimize";
    private static final String ACT_CLEAN = "act_clean";
    private static final String ACT_RESET = "act_reset";

    private List<AgentEngine.Finding> mFindings = new ArrayList<>();
    private String mSummaryTextValue = "Run a scan to check your setup";
    /** Earlier questions and answers of the AI chat. Cleared when a new crash is analyzed. */
    private final List<GroqClient.Message> mHistory = new ArrayList<>();

    @Override
    protected void onViewReady() {
        setHeader(R.string.supersonic_agent_title, R.string.supersonic_agent_subtitle, R.drawable.ic_bolt);
        showRows();
        Bundle args = getArguments();
        if (args == null) return;
        if (args.getBoolean(ARG_CRASH, false)) {
            args.putBoolean(ARG_CRASH, false); // do not repeat after a rotation
            crashFlow();
        } else if (args.getBoolean(ARG_SCAN, false)) {
            scan(false);
        }
    }

    @Override
    protected RowAdapter.Listener createListener() {
        return new RowAdapter.Listener() {
            @Override
            public void onPrimary(RowAdapter.Row row) {
                if (row.id == null) return;
                switch (row.id) {
                    case ACT_AI_CRASH:
                        requireAi(() -> {
                            mHistory.clear();
                            runAi(AgentAi.CRASH_QUESTION);
                        });
                        break;
                    case ACT_AI_ASK:
                        requireAi(() -> showAskDialog());
                        break;
                    case ACT_AI_SETTINGS:
                        showSettingsDialog(null);
                        break;
                    case ACT_UNDO:
                        runAction(AgentEngine::undoLast, () -> scan(false));
                        break;
                    case ACT_FIX:
                        scan(true);
                        break;
                    case ACT_OPTIMIZE:
                        runAction(AgentEngine::optimizePerformance, null);
                        break;
                    case ACT_CLEAN:
                        runAction(AgentEngine::cleanJunk, null);
                        break;
                    case ACT_RESET:
                        new AlertDialog.Builder(requireContext())
                                .setTitle("Reset configurations")
                                .setMessage("Reset launcher settings (renderer, RAM, controls, Java arguments...) to defaults? "
                                        + "Accounts, instances and worlds are kept.")
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(android.R.string.ok, (d, w) ->
                                        runAction(AgentEngine::resetConfigurations, null))
                                .show();
                        break;
                    default:
                        if (row.tag instanceof AgentEngine.Finding) {
                            final AgentEngine.Finding finding = (AgentEngine.Finding) row.tag;
                            runAction(c -> AgentEngine.applyFix(c, finding), () -> scan(false));
                        }
                }
            }
        };
    }

    private void showRows() {
        final Context context = requireContext();
        List<RowAdapter.Row> rows = new ArrayList<>();
        int index = 0;
        for (AgentEngine.Finding finding : mFindings) {
            RowAdapter.Row row = new RowAdapter.Row();
            row.id = "finding_" + index++;
            row.title = finding.title;
            row.description = finding.detail;
            row.badge = AgentEngine.severityLabel(finding.severity);
            row.iconRes = R.drawable.ic_bolt;
            if (finding.fixId != null) row.primaryLabel = finding.fixLabel;
            row.tag = finding;
            rows.add(row);
        }
        rows.add(action(ACT_AI_CRASH, "Explain last crash (AI)",
                "SS Agent reads your latest game log and explains the cause in simple words.", "Analyze"));
        rows.add(action(ACT_AI_ASK, "Ask SS Agent",
                "Ask anything about crashes, mods or performance.", "Ask"));
        rows.add(action(ACT_AI_SETTINGS, "AI settings",
                AgentAi.hasKey(context)
                        ? "Groq key " + AgentAi.maskedKey(context) + " · model " + AgentAi.model(context)
                        : "Add your free Groq API key to turn on the AI.",
                AgentAi.hasKey(context) ? "Edit" : "Set up"));
        if (AgentEngine.hasUndo(context)) {
            rows.add(action(ACT_UNDO, "Undo SS Agent changes",
                    "Turn back on the mods and JVM arguments the Agent switched off, and remove mods it downloaded.", "Undo"));
        }
        rows.add(action(ACT_FIX, "Auto Fix Errors", "Detect and fix issues in one go.", "Run"));
        rows.add(action(ACT_OPTIMIZE, "Optimize Performance", "Pick RAM, resolution and boost settings that fit this phone.", "Run"));
        rows.add(action(ACT_CLEAN, "Clean Junk Files", "Remove cache, old logs and crash reports.", "Run"));
        rows.add(action(ACT_RESET, "Reset Configurations", "Put launcher settings back to defaults.", "Run"));
        showSummary(mSummaryTextValue, "Scan & Fix", v -> scan(true));
        setRows(rows, "");
    }

    private RowAdapter.Row action(String id, String title, String description, String label) {
        RowAdapter.Row row = new RowAdapter.Row();
        row.id = id;
        row.title = title;
        row.description = description;
        row.iconRes = R.drawable.ic_bolt;
        row.primaryLabel = label;
        return row;
    }

    // ------------------------------------------------------------------ scan

    private void scan(boolean applyFixes) {
        setBusy(true);
        final Context context = requireContext().getApplicationContext();
        runBg(() -> {
            List<AgentEngine.Finding> findings = AgentEngine.scan(context);
            final StringBuilder fixed = new StringBuilder();
            if (applyFixes) {
                for (AgentEngine.Finding finding : findings) {
                    if (finding.fixId != null && finding.severity >= AgentEngine.WARNING) {
                        fixed.append("• ").append(AgentEngine.applyFix(context, finding)).append('\n');
                    }
                }
                if (fixed.length() > 0) findings = AgentEngine.scan(context);
            }
            final List<AgentEngine.Finding> result = findings;
            ui(() -> {
                setBusy(false);
                mFindings = result;
                mSummaryTextValue = AgentEngine.summary(result);
                showRows();
                if (fixed.length() > 0) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Fixes applied")
                            .setMessage(fixed.toString().trim())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            });
        });
    }

    // ------------------------------------------------------------------ crash flow

    /**
     * Runs right after a crash: scan, fix what is safe, then (if the AI is set up and allowed) explain the crash.
     * The AI is only used when a key exists and the user has agreed to send data, never silently.
     */
    private void crashFlow() {
        setBusy(true);
        final Context context = requireContext().getApplicationContext();
        runBg(() -> {
            List<AgentEngine.Finding> before = AgentEngine.scan(context);
            List<String> fixes = new ArrayList<>();
            if (AgentAi.autoFix(context)) fixes = AgentEngine.applySafeFixes(context, before);
            final List<String> fixed = fixes;
            final List<AgentEngine.Finding> after = fixed.isEmpty() ? before : AgentEngine.scan(context);
            AgentAi.setActionsNote(bulletList(fixed));

            String aiAnswer = null;
            String aiNote = null;
            if (!AgentAi.hasKey(context)) {
                aiNote = "Add a Groq API key in AI settings to get an AI explanation of the crash.";
            } else if (!AgentAi.hasConsent(context)) {
                aiNote = "Tap \"Explain last crash (AI)\" to let the AI read the log. It asks for your OK once.";
            } else {
                try {
                    mHistory.clear();
                    aiAnswer = AgentAi.ask(context, mHistory, AgentAi.CRASH_QUESTION);
                } catch (IOException | RuntimeException e) {
                    aiNote = "The AI explanation failed: " + friendly(e);
                }
            }

            final String report = buildReport(after, fixed, aiAnswer, aiNote);
            final boolean canFollowUp = aiAnswer != null;
            ui(() -> {
                setBusy(false);
                mFindings = after;
                mSummaryTextValue = AgentEngine.summary(after);
                showRows();
                showAnswer("SS Agent · crash report", report, canFollowUp);
            });
        });
    }

    private static String buildReport(List<AgentEngine.Finding> after, List<String> fixed, String aiAnswer, String aiNote) {
        StringBuilder out = new StringBuilder();
        if (!fixed.isEmpty()) {
            out.append("Fixed automatically:\n").append(bulletList(fixed))
                    .append("Start the game again to check. You can undo these in SS Agent → Undo.\n");
        }
        StringBuilder open = new StringBuilder();
        for (AgentEngine.Finding finding : after) {
            if (finding.fixId == null && finding.severity >= AgentEngine.WARNING) {
                open.append("• ").append(finding.title).append(": ").append(finding.detail).append('\n');
            }
        }
        if (open.length() > 0) {
            if (out.length() > 0) out.append('\n');
            out.append("Needs your attention:\n").append(open);
        }
        if (out.length() == 0) {
            out.append("No known crash pattern was found in the log.\n");
        }
        if (aiAnswer != null) {
            out.append("\nAI analysis:\n").append(AgentAi.plain(aiAnswer));
        } else if (aiNote != null) {
            out.append('\n').append(aiNote);
        }
        return out.toString().trim();
    }

    private static String bulletList(List<String> items) {
        StringBuilder out = new StringBuilder();
        for (String item : items) out.append("• ").append(item).append('\n');
        return out.toString();
    }

    // ------------------------------------------------------------------ AI

    /** Makes sure there is a key and the user has agreed to send data, then runs {@code onReady}. */
    private void requireAi(Runnable onReady) {
        final Context context = requireContext();
        if (!AgentAi.hasKey(context)) {
            showSettingsDialog(onReady);
            return;
        }
        if (!AgentAi.hasConsent(context)) {
            new AlertDialog.Builder(context)
                    .setTitle("Send data to Groq?")
                    .setMessage("SS Agent (AI) sends this to Groq (api.groq.com) to analyze a problem:\n\n"
                            + "• phone model, Android version and RAM\n"
                            + "• game version, mod loader and mod file names\n"
                            + "• the last part of the game log\n\n"
                            + "Access tokens, user names, e-mails and IP addresses are removed from the log first. "
                            + "Nothing is sent until you tap Analyze or Ask.")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Allow", (d, w) -> {
                        AgentAi.setConsent(context, true);
                        onReady.run();
                    })
                    .show();
            return;
        }
        onReady.run();
    }

    private void runAi(String question) {
        setBusy(true);
        final Context context = requireContext().getApplicationContext();
        runBg(() -> {
            String answer = null;
            String error = null;
            boolean authError = false;
            try {
                answer = AgentAi.ask(context, mHistory, question);
            } catch (GroqClient.GroqException e) {
                error = e.getMessage();
                authError = e.httpCode == 401;
            } catch (IOException | RuntimeException e) {
                error = friendly(e);
            }
            final String finalAnswer = answer;
            final String finalError = error;
            final boolean finalAuth = authError;
            ui(() -> {
                setBusy(false);
                if (finalError != null) showError(finalError, finalAuth);
                else showAnswer("SS Agent", AgentAi.plain(finalAnswer), true);
            });
        });
    }

    private static String friendly(Exception e) {
        if (e instanceof UnknownHostException || e instanceof SocketException || e instanceof SocketTimeoutException) {
            return "No internet connection, or Groq cannot be reached right now.";
        }
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private void showAskDialog() {
        final Context context = requireContext();
        final EditText input = new EditText(context);
        input.setHint("e.g. Why does my game close at the loading screen?");
        input.setMinLines(2);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new AlertDialog.Builder(context)
                .setTitle(mHistory.isEmpty() ? "Ask SS Agent" : "Follow-up question")
                .setView(padded(context, input))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Ask", (d, w) -> {
                    String question = input.getText().toString().trim();
                    if (!question.isEmpty() && isAdded()) runAi(question);
                })
                .show();
    }

    private void showAnswer(String title, String text, boolean canFollowUp) {
        final Context context = requireContext();
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextIsSelectable(true);
        view.setTextSize(15);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(view);
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(padded(context, scroll))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(android.R.string.copy, (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("SS Agent", text));
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show();
                    }
                });
        if (canFollowUp) {
            builder.setNegativeButton("Ask follow-up", (d, w) -> {
                if (isAdded()) showAskDialog();
            });
        }
        builder.show();
    }

    private void showError(String message, boolean offerSettings) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
                .setTitle("SS Agent")
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null);
        if (offerSettings) {
            builder.setNeutralButton("AI settings", (d, w) -> {
                if (isAdded()) showSettingsDialog(null);
            });
        }
        builder.show();
    }

    private void showSettingsDialog(Runnable afterSave) {
        final Context context = requireContext();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView info = new TextView(context);
        String current;
        if (!AgentAi.userKey(context).isEmpty()) current = "Saved key: " + AgentAi.maskedKey(context);
        else if (AgentAi.hasKey(context)) current = "Using the key built into this app.";
        else current = "No key yet.";
        info.setText("SS Agent (AI) runs on Groq. Get a free key at console.groq.com → API Keys. "
                + "The key is stored only on this phone.\n\n" + current);
        box.addView(info);

        final EditText key = new EditText(context);
        key.setHint("Paste your gsk_… key (empty keeps the current one)");
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(key);

        final EditText model = new EditText(context);
        model.setHint(GroqClient.DEFAULT_MODEL);
        model.setText(AgentAi.model(context));
        model.setSingleLine(true);
        box.addView(model);

        final CheckBox autoFix = new CheckBox(context);
        autoFix.setText("After a crash, fix safe problems automatically (undoable)");
        autoFix.setChecked(AgentAi.autoFix(context));
        box.addView(autoFix);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle("AI settings")
                .setView(padded(context, box))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Save", (d, w) -> {
                    String typed = key.getText().toString().trim();
                    if (!typed.isEmpty()) AgentAi.saveKey(context, typed);
                    AgentAi.saveModel(context, model.getText().toString());
                    AgentAi.setAutoFix(context, autoFix.isChecked());
                    if (!isAdded()) return;
                    showRows();
                    if (afterSave != null && AgentAi.hasKey(context)) requireAi(afterSave);
                });
        if (!AgentAi.userKey(context).isEmpty()) {
            builder.setNeutralButton("Remove key", (d, w) -> {
                AgentAi.saveKey(context, "");
                if (isAdded()) showRows();
            });
        }
        builder.show();
    }

    private static View padded(Context context, View child) {
        FrameLayout frame = new FrameLayout(context);
        int side = SupersonicUi.dp(context, 20);
        frame.setPadding(side, SupersonicUi.dp(context, 8), side, 0);
        frame.addView(child);
        return frame;
    }

    // ------------------------------------------------------------------ actions

    private interface Action {
        String run(Context context);
    }

    private void runAction(Action action, Runnable afterwards) {
        setBusy(true);
        final Context context = requireContext().getApplicationContext();
        runBg(() -> {
            String message = action.run(context);
            ui(() -> {
                setBusy(false);
                new AlertDialog.Builder(requireContext())
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                if (afterwards != null) afterwards.run();
            });
        });
    }
}
