package net.kdt.pojavlaunch.supersonic;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Tools screen of SS Agent: scan and fix, performance, cleaning, undo and the way into the AI chat
 * ({@link AgentChatFragment}).
 */
public class AgentFragment extends BaseListFragment {
    public static final String TAG = "AgentFragment";
    public static final String ARG_SCAN = "scan";

    private static final String ACT_CHAT = "act_chat";
    private static final String ACT_AI_CRASH = "act_ai_crash";
    private static final String ACT_AI_SETTINGS = "act_ai_settings";
    private static final String ACT_UNDO = "act_undo";
    private static final String ACT_FIX = "act_fix";
    private static final String ACT_OPTIMIZE = "act_optimize";
    private static final String ACT_CLEAN = "act_clean";
    private static final String ACT_RESET = "act_reset";

    private List<AgentEngine.Finding> mFindings = new ArrayList<>();
    private String mSummaryTextValue = "Run a scan to check your setup";

    @Override
    protected void onViewReady() {
        setHeader(R.string.supersonic_agent_title, R.string.supersonic_agent_subtitle, R.drawable.ic_bolt);
        showRows();
        Bundle args = getArguments();
        if (args != null && args.getBoolean(ARG_SCAN, false)) scan(false);
    }

    @Override
    protected RowAdapter.Listener createListener() {
        return new RowAdapter.Listener() {
            @Override
            public void onPrimary(RowAdapter.Row row) {
                if (row.id == null) return;
                switch (row.id) {
                    case ACT_CHAT:
                        Tools.swapFragment(requireActivity(), AgentChatFragment.class, AgentChatFragment.TAG, null);
                        break;
                    case ACT_AI_CRASH: {
                        Bundle args = new Bundle();
                        args.putBoolean(AgentChatFragment.ARG_CRASH, true);
                        Tools.swapFragment(requireActivity(), AgentChatFragment.class, AgentChatFragment.TAG, args);
                        break;
                    }
                    case ACT_AI_SETTINGS:
                        AgentSettingsDialog.show(requireContext(), () -> {
                            if (isAdded()) showRows();
                        });
                        break;
                    case ACT_UNDO:
                        runAction(AgentUndo::undoEverything, () -> scan(false));
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
        AiProvider provider = AiConfig.provider(context);
        rows.add(action(ACT_CHAT, "Chat with SS Agent",
                "Ask anything in any language. The AI can read your logs, mods and configs and fix problems for you.",
                "Open"));
        rows.add(action(ACT_AI_CRASH, "Fix last crash with AI",
                "Analyzes the last crash, fixes what it can and explains it.", "Fix"));
        rows.add(action(ACT_AI_SETTINGS, "AI settings",
                provider.label + " · " + AiConfig.model(context, provider)
                        + (AiConfig.isReady(context, provider) ? "" : " · needs a key"),
                "Edit"));
        if (AgentUndo.count(context) > 0 || AgentEngine.hasUndo(context)) {
            rows.add(action(ACT_UNDO, "Undo SS Agent changes",
                    "Put back every file, mod and setting that SS Agent changed.", "Undo"));
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
