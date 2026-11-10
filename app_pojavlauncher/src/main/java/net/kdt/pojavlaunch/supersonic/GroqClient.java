package net.kdt.pojavlaunch.supersonic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Minimal client for the Groq chat completions API (OpenAI compatible).
 * Blocking: call it from a background thread only.
 */
public final class GroqClient {
    private GroqClient() {}

    public static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
    /** Groq "production" model: good reasoning, fast. Can be changed in the SS Agent AI settings. */
    public static final String DEFAULT_MODEL = "llama-3.3-70b-versatile";

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    public static final class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public static Message system(String content) { return new Message("system", content); }
        public static Message user(String content) { return new Message("user", content); }
        public static Message assistant(String content) { return new Message("assistant", content); }
    }

    /** The message is written for the user and is safe to display. */
    public static final class GroqException extends IOException {
        private static final long serialVersionUID = 1L;
        public final int httpCode;

        GroqException(int httpCode, String message) {
            super(message);
            this.httpCode = httpCode;
        }
    }

    /** @return the text of the first reply choice */
    public static String chat(String apiKey, String model, List<Message> messages) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.3);
        body.addProperty("max_tokens", 1024);
        JsonArray array = new JsonArray();
        for (Message message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role);
            item.addProperty("content", message.content);
            array.add(item);
        }
        body.add("messages", array);

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "SupersonicClient/1.0 (Android)");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = stream == null ? "" : readAll(stream);
            if (code >= 400) throw new GroqException(code, describeError(code, text));
            return extractContent(text);
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > MAX_RESPONSE_BYTES) throw new IOException("The reply was too large");
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String extractContent(String json) throws IOException {
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                JsonElement content = message == null ? null : message.get("content");
                if (content != null && !content.isJsonNull() && !content.getAsString().trim().isEmpty()) {
                    return content.getAsString().trim();
                }
            }
        } catch (JsonParseException | IllegalStateException | ClassCastException ignored) {
            // falls through to the error below
        }
        throw new IOException("Groq returned an empty or unreadable reply");
    }

    private static String describeError(int code, String json) {
        String detail = null;
        try {
            JsonObject error = new JsonParser().parse(json).getAsJsonObject().getAsJsonObject("error");
            if (error != null && error.has("message")) detail = error.get("message").getAsString();
        } catch (JsonParseException | IllegalStateException | ClassCastException ignored) { }

        switch (code) {
            case 401:
                return "Groq rejected the API key (401). Check it in AI settings.";
            case 403:
                return "Groq refused the request (403). The key may be disabled or blocked in your region.";
            case 404:
                return "Groq does not know this model (404). Pick another model in AI settings."
                        + (detail == null ? "" : "\n" + detail);
            case 413:
                return "The log was too large for this model (413). Try again after cleaning junk files.";
            case 429:
                return "Groq rate limit reached (429). Wait a minute and try again."
                        + (detail == null ? "" : "\n" + detail);
            default:
                return "Groq error " + code + (detail == null ? "" : ": " + detail);
        }
    }
}
