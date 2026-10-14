package net.kdt.pojavlaunch.supersonic;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import java.util.List;

/** Scaffold (header, optional search, chips, summary bar, card list) shared by the Supersonic screens. */
public abstract class BaseListFragment extends Fragment {
    protected ImageView mIcon;
    protected TextView mTitle, mSubtitle, mEmpty, mSummaryText;
    protected EditText mSearch;
    protected View mChipsScroll, mSummary;
    protected LinearLayout mChips;
    protected Button mSummaryAction;
    protected RecyclerView mList;
    protected ProgressBar mProgress;
    protected RowAdapter mAdapter;

    public BaseListFragment() {
        super(R.layout.fragment_supersonic_list);
    }

    /** Number of grid columns. */
    protected int spanCount() {
        return 1;
    }

    /** Called once the views exist. */
    protected abstract void onViewReady();

    protected abstract RowAdapter.Listener createListener();

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mIcon = view.findViewById(R.id.sl_icon);
        mTitle = view.findViewById(R.id.sl_title);
        mSubtitle = view.findViewById(R.id.sl_subtitle);
        mSearch = view.findViewById(R.id.sl_search);
        mChipsScroll = view.findViewById(R.id.sl_chips_scroll);
        mChips = view.findViewById(R.id.sl_chips);
        mSummary = view.findViewById(R.id.sl_summary);
        mSummaryText = view.findViewById(R.id.sl_summary_text);
        mSummaryAction = view.findViewById(R.id.sl_summary_action);
        mProgress = view.findViewById(R.id.sl_progress);
        mList = view.findViewById(R.id.sl_list);
        mEmpty = view.findViewById(R.id.sl_empty);

        mAdapter = new RowAdapter(createListener());
        mList.setLayoutManager(new GridLayoutManager(requireContext(), spanCount()));
        mList.setAdapter(mAdapter);
        onViewReady();
    }

    protected void setHeader(@StringRes int title, @StringRes int subtitle, @DrawableRes int icon) {
        mTitle.setText(title);
        mSubtitle.setText(subtitle);
        mIcon.setImageResource(icon);
    }

    protected void showSummary(String text, String actionLabel, View.OnClickListener action) {
        mSummary.setVisibility(View.VISIBLE);
        mSummaryText.setText(text);
        if (actionLabel == null) {
            mSummaryAction.setVisibility(View.GONE);
        } else {
            mSummaryAction.setVisibility(View.VISIBLE);
            mSummaryAction.setText(actionLabel);
            mSummaryAction.setOnClickListener(action);
        }
    }

    protected void setBusy(boolean busy) {
        mProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    protected void setRows(List<RowAdapter.Row> rows, String emptyText) {
        mAdapter.setRows(rows);
        mEmpty.setText(emptyText);
        mEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    protected void runBg(Runnable runnable) {
        PojavApplication.sExecutorService.execute(runnable);
    }

    /** Runs on the UI thread only while the fragment is still showing. */
    protected void ui(Runnable runnable) {
        Tools.runOnUiThread(() -> {
            if (isAdded() && getView() != null) runnable.run();
        });
    }
}
