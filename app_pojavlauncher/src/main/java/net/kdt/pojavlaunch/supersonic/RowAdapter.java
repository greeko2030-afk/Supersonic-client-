package net.kdt.pojavlaunch.supersonic;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.ModIconCache;

import java.util.ArrayList;
import java.util.List;

/** One generic card adapter used by Addons, Servers, Instances, Worlds, Resource Packs and the Agent. */
public class RowAdapter extends RecyclerView.Adapter<RowAdapter.Holder> {

    public static class Row {
        public String id;
        public String title;
        public String subtitle;
        public String description;
        public String badge;
        public String iconUrl;
        public int iconRes;
        public String primaryLabel;
        public String secondaryLabel;
        public String tertiaryLabel;
        /** null = no switch, otherwise the switch state */
        public Boolean checked;
        public Object tag;
    }

    /** Override only what you need. */
    public static abstract class Listener {
        public void onClick(Row row) {}
        public void onPrimary(Row row) {}
        public void onSecondary(Row row) {}
        public void onTertiary(Row row) {}
        public void onToggle(Row row, boolean checked) {}
    }

    private final List<Row> mRows = new ArrayList<>();
    private final Listener mListener;
    private ModIconCache mIconCache;

    public RowAdapter(Listener listener) {
        mListener = listener;
    }

    public void setRows(List<Row> rows) {
        mRows.clear();
        mRows.addAll(rows);
        notifyDataSetChanged();
    }

    public List<Row> getRows() {
        return mRows;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_supersonic_row, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final Row row = mRows.get(position);
        holder.boundId = row.id;

        holder.title.setText(row.title);
        setText(holder.subtitle, row.subtitle);
        setText(holder.desc, row.description);
        setText(holder.badge, row.badge);

        // Icon: remote image (cached on disk) or a local resource
        if (row.iconUrl != null && !row.iconUrl.isEmpty()) {
            holder.icon.setImageResource(row.iconRes != 0 ? row.iconRes : R.drawable.ic_nav_addons);
            if (mIconCache == null) mIconCache = new ModIconCache();
            final String requestedId = row.id;
            mIconCache.getImage(bitmap -> {
                if (requestedId != null && requestedId.equals(holder.boundId)) holder.icon.setImageBitmap(bitmap);
            }, "ss_" + row.id, row.iconUrl);
        } else {
            holder.icon.setImageResource(row.iconRes != 0 ? row.iconRes : R.drawable.ic_nav_addons);
        }

        // Switch (listener removed first so recycling never fires a toggle)
        holder.toggle.setOnCheckedChangeListener(null);
        if (row.checked == null) {
            holder.toggle.setVisibility(View.GONE);
        } else {
            holder.toggle.setVisibility(View.VISIBLE);
            holder.toggle.setChecked(row.checked);
            holder.toggle.setOnCheckedChangeListener((button, isChecked) -> {
                row.checked = isChecked;
                mListener.onToggle(row, isChecked);
            });
        }

        bindButton(holder.primary, row.primaryLabel, () -> mListener.onPrimary(row));
        bindButton(holder.secondary, row.secondaryLabel, () -> mListener.onSecondary(row));
        bindButton(holder.tertiary, row.tertiaryLabel, () -> mListener.onTertiary(row));
        boolean anyButton = row.primaryLabel != null || row.secondaryLabel != null || row.tertiaryLabel != null;
        holder.buttons.setVisibility(anyButton ? View.VISIBLE : View.GONE);

        holder.itemView.setOnClickListener(v -> mListener.onClick(row));
    }

    private static void setText(TextView view, String text) {
        if (text == null || text.isEmpty()) {
            view.setVisibility(View.GONE);
        } else {
            view.setVisibility(View.VISIBLE);
            view.setText(text);
        }
    }

    private static void bindButton(Button button, String label, Runnable action) {
        if (label == null) {
            button.setVisibility(View.GONE);
            button.setOnClickListener(null);
        } else {
            button.setVisibility(View.VISIBLE);
            button.setText(label);
            button.setOnClickListener(v -> action.run());
        }
    }

    @Override
    public int getItemCount() {
        return mRows.size();
    }

    public static class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title, subtitle, desc, badge;
        final Switch toggle;
        final View buttons;
        final Button primary, secondary, tertiary;
        String boundId;

        Holder(@NonNull View view) {
            super(view);
            icon = view.findViewById(R.id.row_icon);
            title = view.findViewById(R.id.row_title);
            subtitle = view.findViewById(R.id.row_subtitle);
            desc = view.findViewById(R.id.row_desc);
            badge = view.findViewById(R.id.row_badge);
            toggle = view.findViewById(R.id.row_switch);
            buttons = view.findViewById(R.id.row_buttons);
            primary = view.findViewById(R.id.row_primary);
            secondary = view.findViewById(R.id.row_secondary);
            tertiary = view.findViewById(R.id.row_tertiary);
        }
    }
}
