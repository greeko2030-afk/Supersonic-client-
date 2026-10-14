package net.kdt.pojavlaunch.supersonic;

import androidx.annotation.Keep;

import com.google.gson.reflect.TypeToken;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Saved servers, stored as JSON next to the game folder. */
public final class ServerStore {
    private ServerStore() {}

    @Keep
    public static class Server {
        public String name;
        public String host;
        public int port = 25565;

        public String address() {
            return port == 25565 ? host : host + ":" + port;
        }
    }

    private static File file() {
        return new File(Tools.DIR_GAME_HOME, "supersonic_servers.json");
    }

    public static List<Server> load() {
        File file = file();
        if (!file.isFile()) return new ArrayList<>();
        try {
            List<Server> servers = Tools.GLOBAL_GSON.fromJson(Tools.read(file.getAbsolutePath()),
                    new TypeToken<List<Server>>() {}.getType());
            return servers == null ? new ArrayList<>() : servers;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static void save(List<Server> servers) {
        try {
            Tools.write(file().getAbsolutePath(), Tools.GLOBAL_GSON.toJson(servers));
        } catch (IOException ignored) { }
    }

    /** Parses "host", "host:port" into a server. Returns null when the host is empty. */
    public static Server parse(String name, String address) {
        String text = address == null ? "" : address.trim();
        if (text.isEmpty()) return null;
        Server server = new Server();
        int colon = text.lastIndexOf(':');
        if (colon > 0 && colon < text.length() - 1 && text.substring(colon + 1).matches("\\d{1,5}")) {
            server.host = text.substring(0, colon);
            server.port = Integer.parseInt(text.substring(colon + 1));
        } else {
            server.host = text;
        }
        server.name = name == null || name.trim().isEmpty() ? server.host : name.trim();
        return server;
    }
}
