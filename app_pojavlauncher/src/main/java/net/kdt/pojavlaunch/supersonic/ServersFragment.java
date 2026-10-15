package net.kdt.pojavlaunch.supersonic;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Servers tab: save servers, see their real status (online, latency, players, version),
 * edit or remove them, and join one with a single tap.
 *
 * Tap a card to ping that server again.
 */
public class ServersFragment extends BaseListFragment {
    public static final String TAG = "ServersFragment";

    /** Keeps two quick saves from writing the servers file at the same time. */
    private static final Object SAVE_LOCK = new Object();

    private final List<ServerStore.Server> mServers = new ArrayList<>();
    /** Last ping result of each server, by address. A missing entry means "still pinging". */
    private final Map<String, ServerPinger.Result> mResults = new HashMap<>();
    /** Results of an older round of pings are ignored. */
    private int mRequestId = 0;

    @Override
    protected void onViewReady() {
        setHeader(R.string.supersonic_servers_title, R.string.supersonic_servers_subtitle, R.drawable.ic_nav_servers);
        load();
    }

    @Override
    protected RowAdapter.Listener createListener() {
        return new RowAdapter.Listener() {
            @Override
            public void onClick(RowAdapter.Row row) {
                if (row.tag instanceof ServerStore.Server) refreshOne((ServerStore.Server) row.tag);
            }

            @Override
            public void onPrimary(RowAdapter.Row row) {
                if (row.tag instanceof ServerStore.Server) join((ServerStore.Server) row.tag);
            }

            @Override
            public void onSecondary(RowAdapter.Row row) {
                if (row.tag instanceof ServerStore.Server) showEditDialog((ServerStore.Server) row.tag);
            }

            @Override
            public void onTertiary(RowAdapter.Row row) {
                if (row.tag instanceof ServerStore.Server) confirmRemove((ServerStore.Server) row.tag);
            }
        };
    }

    private void toast(String message) {
        if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ loading and saving

    private void load() {
        setBusy(true);
        runBg(() -> {
            final List<ServerStore.Server> loaded = ServerStore.load();
            ui(() -> {
                setBusy(false);
                mServers.clear();
                mServers.addAll(loaded);
                render();
                pingAll();
            });
        });
    }

    private void persist() {
        final List<ServerStore.Server> copy = new ArrayList<>(mServers);
        runBg(() -> {
            synchronized (SAVE_LOCK) {
                ServerStore.save(copy);
            }
        });
    }

    // ------------------------------------------------------------------ ping

    private void pingAll() {
        final int request = ++mRequestId;
        mResults.clear();
        render();
        for (ServerStore.Server server : new ArrayList<>(mServers)) pingOne(server, request);
    }

    private void refreshOne(ServerStore.Server server) {
        mResults.remove(server.address());
        render();
        pingOne(server, mRequestId);
    }

    private void pingOne(ServerStore.Server server, final int request) {
        final String key = server.address();
        final String host = server.host;
        final int port = server.port;
        runBg(() -> {
            final ServerPinger.Result result = ServerPinger.ping(host, port);
            ui(() -> {
                if (request != mRequestId) return;
                mResults.put(key, result);
                render();
            });
        });
    }

    // ------------------------------------------------------------------ list

    private void render() {
        if (!isAdded() || getView() == null) return;

        List<RowAdapter.Row> rows = new ArrayList<>();
        for (ServerStore.Server server : mServers) rows.add(toRow(server));

        int count = mServers.size();
        showSummary(count + (count == 1 ? " server" : " servers"), "Add server", v -> showEditDialog(null));
        setRows(rows, "No servers yet. Tap \"Add server\" to save your first one.");
    }

    private RowAdapter.Row toRow(ServerStore.Server server) {
        RowAdapter.Row row = new RowAdapter.Row();
        row.id = server.address();
        row.title = server.name;
        row.subtitle = server.address();
        row.iconRes = R.drawable.ic_nav_servers;
        row.primaryLabel = "Join";
        row.secondaryLabel = "Edit";
        row.tertiaryLabel = "Remove";
        row.tag = server;

        ServerPinger.Result result = mResults.get(server.address());
        if (result == null) {
            row.badge = "Pinging…";
        } else if (!result.online) {
            row.badge = "Offline";
            row.description = "No answer from this server. Check the address and your connection.";
        } else {
            row.badge = "Online • " + result.latencyMs + " ms";
            StringBuilder description = new StringBuilder();
            if (!result.motd.isEmpty()) description.append(result.motd).append('\n');
            description.append(result.players).append('/').append(result.maxPlayers).append(" players");
            if (!result.version.isEmpty()) description.append("  •  ").append(result.version);
            row.description = description.toString();
        }
        return row;
    }

    // ------------------------------------------------------------------ add / edit / remove

    private void showEditDialog(@Nullable final ServerStore.Server existing) {
        Context context = requireContext();
        int padding = SupersonicUi.dp(context, 20);

        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(padding, padding / 2, padding, 0);

        final EditText name = new EditText(context);
        name.setHint("Name (optional)");
        name.setInputType(InputType.TYPE_CLASS_TEXT);

        final EditText address = new EditText(context);
        address.setHint("Address, e.g. play.example.com");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        if (existing != null) {
            name.setText(existing.name);
            address.setText(existing.address());
        }
        form.addView(name);
        form.addView(address);

        new AlertDialog.Builder(context)
                .setTitle(existing == null ? "Add server" : "Edit server")
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        saveServer(existing, name.getText().toString(), address.getText().toString()))
                .show();
    }

    private void saveServer(@Nullable ServerStore.Server existing, String name, String address) {
        ServerStore.Server parsed = ServerStore.parse(name, address);
        if (parsed == null) {
            toast("Enter a server address");
            return;
        }
        if (parsed.host.matches(".*\\s.*")) {
            toast("The address cannot contain spaces");
            return;
        }
        if (parsed.port < 1 || parsed.port > 65535) {
            toast("The port must be between 1 and 65535");
            return;
        }
        for (ServerStore.Server other : mServers) {
            if (other != existing && other.address().equalsIgnoreCase(parsed.address())) {
                toast("This server is already in your list");
                return;
            }
        }

        final ServerStore.Server target;
        if (existing == null) {
            target = parsed;
            mServers.add(target);
        } else {
            target = existing;
            mResults.remove(existing.address());
            existing.name = parsed.name;
            existing.host = parsed.host;
            existing.port = parsed.port;
        }
        persist();
        render();
        pingOne(target, mRequestId);
    }

    private void confirmRemove(final ServerStore.Server server) {
        new AlertDialog.Builder(requireContext())
                .setTitle(server.name)
                .setMessage("Remove this server from your list?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    mResults.remove(server.address());
                    mServers.remove(server);
                    persist();
                    render();
                })
                .show();
    }

    // ------------------------------------------------------------------ join

    /** Starts the game of the selected instance and connects straight to the server. */
    private void join(ServerStore.Server server) {
        SupersonicProfile profile = SupersonicProfile.current();
        if (profile == null) {
            toast("Select an instance first (Library → Instances)");
            return;
        }
        if (!profile.supportsQuickPlay()) {
            toast(profile.displayName() + " uses Minecraft " + profile.mcVersion
                    + ", which cannot join a server directly. Use Minecraft 1.20 or newer, "
                    + "or add the server inside the game.");
            return;
        }
        // The argument is added to the next launch only
        Tools.setPendingGameArgs("--quickPlayMultiplayer " + server.address());
        toast("Joining " + server.name + "…");
        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
    }
}
