package net.kdt.pojavlaunch.supersonic;

import android.app.AlertDialog;
import android.os.Bundle;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

/** Supersonic Agent: scan the setup, fix problems, optimize, clean up and reset. */
public class AgentFragment extends BaseListFragment {
    public static final String TAG = "AgentFragment";
    public static final String ARG_SCAN = "scan";

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
                            runAction(c -> AgentEngine.applyFix(c, finding.fixId), () -> scan(false));
                        }
                }
            }
        };
    }

    private void showRows() {
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
        rows.add(action(ACT_FIX, "Auto Fix Errors", "Detect and fix issues in one go."));
        rows.add(action(ACT_OPTIMIZE, "Optimize Performance", "Pick RAM, resolution and boost settings that fit this phone."));
        rows.add(action(ACT_CLEAN, "Clean Junk Files", "Remove cache, old logs and crash reports."));
        rows.add(action(ACT_RESET, "Reset Configurations", "Put launcher settings back to defaults."));
        showSummary(mSummaryTextValue, "Scan & Fix", v -> scan(true));
        setRows(rows, "");
    }

    private RowAdapter.Row action(String id, String title, String description) {
        RowAdapter.Row row = new RowAdapter.Row();
        row.id = id;
        row.title = title;
        row.description = description;
        row.iconRes = R.drawable.ic_bolt;
        row.primaryLabel = "Run";
        return row;
    }

    private void scan(boolean applyFixes) {
        setBusy(true);
        final android.content.Context context = requireContext().getApplicationContext();
        runBg(() -> {
            List<AgentEngine.Finding> findings = AgentEngine.scan(context);
            final StringBuilder fixed = new StringBuilder();
            if (applyFixes) {
                for (AgentEngine.Finding finding : findings) {
                    if (finding.fixId != null && finding.severity >= AgentEngine.WARNING) {
                        fixed.append("• ").append(AgentEngine.applyFix(context, finding.fixId)).append('\n');
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

    private interface Action {
        String run(android.content.Context context);
    }

    private void runAction(Action action, Runnable afterwards) {
        setBusy(true);
        final android.content.Context context = requireContext().getApplicationContext();
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
