package net.kdt.pojavlaunch.supersonic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.Tools;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Minecraft "Server List Ping": asks a server for its status and measures the real latency. */
public final class ServerPinger {
    private ServerPinger() {}

    public static class Result {
        public boolean online;
        public int players, maxPlayers;
        public long latencyMs;
        public String version = "";
        public String motd = "";
    }

    public static Result ping(String host, int port) {
        Result result = new Result();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3500);
            socket.setSoTimeout(3500);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Handshake (next state = status)
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(handshake);
            writeVarInt(h, 0x00);
            writeVarInt(h, 765);
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(h, hostBytes.length);
            h.write(hostBytes);
            h.writeShort(port);
            writeVarInt(h, 1);
            writeVarInt(out, handshake.size());
            out.write(handshake.toByteArray());

            // Status request
            writeVarInt(out, 1);
            writeVarInt(out, 0x00);
            out.flush();

            readVarInt(in); // packet length
            int id = readVarInt(in);
            if (id != 0x00) throw new IOException("Unexpected packet " + id);
            int jsonLength = readVarInt(in);
            byte[] json = new byte[jsonLength];
            in.readFully(json);
            parseStatus(new String(json, StandardCharsets.UTF_8), result);

            // Ping / pong for the latency
            long sent = System.currentTimeMillis();
            writeVarInt(out, 9);
            writeVarInt(out, 0x01);
            out.writeLong(sent);
            out.flush();
            readVarInt(in);
            readVarInt(in);
            in.readLong();
            result.latencyMs = System.currentTimeMillis() - sent;
            result.online = true;
        } catch (Exception e) {
            result.online = false;
        }
        return result;
    }

    private static void parseStatus(String json, Result result) {
        JsonObject root = Tools.GLOBAL_GSON.fromJson(json, JsonObject.class);
        JsonObject players = root.has("players") ? root.getAsJsonObject("players") : null;
        if (players != null) {
            result.players = players.has("online") ? players.get("online").getAsInt() : 0;
            result.maxPlayers = players.has("max") ? players.get("max").getAsInt() : 0;
        }
        JsonObject version = root.has("version") ? root.getAsJsonObject("version") : null;
        if (version != null && version.has("name")) result.version = version.get("name").getAsString();
        if (root.has("description")) {
            result.motd = text(root.get("description")).replaceAll("§.", "").replaceAll("\\s+", " ").trim();
        }
    }

    private static String text(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) return element.getAsString();
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            StringBuilder builder = new StringBuilder();
            if (object.has("text")) builder.append(object.get("text").getAsString());
            if (object.has("extra") && object.get("extra").isJsonArray()) {
                for (JsonElement extra : object.getAsJsonArray("extra")) builder.append(text(extra));
            }
            return builder.toString();
        }
        return "";
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int current = in.readByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) break;
            position += 7;
            if (position >= 35) throw new IOException("VarInt too big");
        }
        return value;
    }
}
