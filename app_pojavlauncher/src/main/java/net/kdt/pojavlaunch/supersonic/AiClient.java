package net.kdt.pojavlaunch.supersonic;

import android.content.Context;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blocking chat client with tool calling for the three request formats SS Agent supports:
 * OpenAI compatible (Groq, ChatGPT, OpenRouter, custom), Anthropic (Claude) and Google Gemini.
 * Call from a background thread only.
 */
public final class AiClient {
    private AiClient() {}

    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final AtomicInteger CALL_COUNTER = new AtomicInteger();

    /** Told when a request has to wait because of a rate limit. */
    public interface RateLimitListener {
        /** @return false to give up instead of waiting */
        boolean onWait(int seconds);
    }

    private static final int MAX_RATE_RETRIES = 4;
    private static final int MAX_WAIT_SECONDS = 75;

    /** The message is written for the user and is safe to display. */
    public static final class AiException extends IOException {
        private static final long serialVersionUID = 1L;
        public final int httpCode;

        public AiException(int httpCode, String message) {
            super(message);
            this.httpCode = httpCode;
        }
    }

    /** Uses the provider, key, model and URL from AI settings. */
    public static AiChat.Reply chat(Context context, AiProvider provider, String system,
                                    List<AiChat.Msg> history, List<AiChat.ToolSpec> tools) throws IOException {
        return chat(context, provider, system, history, tools, null);
    }

    public static AiChat.Reply chat(Context context, AiProvider provider, String system,
                                    List<AiChat.Msg> history, List<AiChat.ToolSpec> tools,
                                    RateLimitListener listener) throws IOException {
        String key = AiConfig.apiKey(context, provider);
        if (key.isEmpty() && !provider.keyOptional()) {
            throw new AiException(401, "No API key for " + provider.label + ". Add one in AI settings.");
        }
        String url = AiConfig.baseUrl(context, provider);
        if (url.isEmpty()) {
            throw new AiException(0, "No endpoint URL for " + provider.label + ". Set it in AI settings.");
        }
        return chat(provider, url, key, AiConfig.model(context, provider), system, history, tools, listener);
    }

    public static AiChat.Reply chat(AiProvider provider, String url, String key, String model, String system,
                                    List<AiChat.Msg> history, List<AiChat.ToolSpec> tools) throws IOException {
        return chat(provider, url, key, model, system, history, tools, null);
    }

    public static AiChat.Reply chat(AiProvider provider, String url, String key, String model, String system,
                                    List<AiChat.Msg> history, List<AiChat.ToolSpec> tools,
                                    RateLimitListener listener) throws IOException {
        switch (provider.protocol) {
            case ANTHROPIC:
                return anthropic(provider, url, key, model, system, history, tools, listener);
            case GEMINI:
                return gemini(url, key, model, system, history, tools, listener);
            default:
                return openAi(provider, url, key, model, system, history, tools, listener);
        }
    }

    // ------------------------------------------------------------------ OpenAI compatible

    private static AiChat.Reply openAi(AiProvider provider, String url, String key, String model, String system,
                                       List<AiChat.Msg> history, List<AiChat.ToolSpec> tools,
                                       RateLimitListener listener) throws IOException {
        String endpoint = url.trim();
        while (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        if (!endpoint.endsWith("/chat/completions")) endpoint += "/chat/completions";

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray messages = new JsonArray();
        if (system != null && !system.isEmpty()) messages.add(message("system", system));
        for (AiChat.Msg m : history) {
            switch (m.role) {
                case AiChat.USER:
                    messages.add(message("user", m.text));
                    break;
                case AiChat.ASSISTANT: {
                    JsonObject o = new JsonObject();
                    o.addProperty("role", "assistant");
                    if (!m.text.isEmpty() || m.toolCalls.isEmpty()) o.addProperty("content", m.text);
                    if (!m.toolCalls.isEmpty()) {
                        JsonArray calls = new JsonArray();
                        for (AiChat.ToolCall call : m.toolCalls) {
                            JsonObject function = new JsonObject();
                            function.addProperty("name", call.name);
                            function.addProperty("arguments", call.args.toString());
                            JsonObject c = new JsonObject();
                            c.addProperty("id", call.id);
                            c.addProperty("type", "function");
                            c.add("function", function);
                            calls.add(c);
                        }
                        o.add("tool_calls", calls);
                    }
                    messages.add(o);
                    break;
                }
                default: {
                    JsonObject o = new JsonObject();
                    o.addProperty("role", "tool");
                    o.addProperty("tool_call_id", m.toolCallId);
                    o.addProperty("content", m.text);
                    messages.add(o);
                }
            }
        }
        body.add("messages", messages);

        if (!tools.isEmpty()) {
            JsonArray array = new JsonArray();
            for (AiChat.ToolSpec tool : tools) {
                JsonObject function = new JsonObject();
                function.addProperty("name", tool.name);
                function.addProperty("description", tool.description);
                function.add("parameters", tool.parameters);
                JsonObject t = new JsonObject();
                t.addProperty("type", "function");
                t.add("function", function);
                array.add(t);
            }
            body.add("tools", array);
        }
        if (provider == AiProvider.OPENAI) {
            body.addProperty("max_completion_tokens", 4096); // newer OpenAI models reject max_tokens
        } else {
            body.addProperty("max_tokens", 1500);
            body.addProperty("temperature", 0.3);
        }

        String[][] headers = key.isEmpty()
                ? new String[0][]
                : new String[][]{{"Authorization", "Bearer " + key}};
        JsonObject root = parseObject(post(endpoint, headers, body, listener));

        JsonArray choices = root.has("choices") && root.get("choices").isJsonArray() ? root.getAsJsonArray("choices") : null;
        if (choices == null || choices.size() == 0 || !choices.get(0).isJsonObject()) {
            throw new IOException("The AI returned no answer");
        }
        JsonObject first = choices.get(0).getAsJsonObject();
        JsonObject message = first.has("message") && first.get("message").isJsonObject()
                ? first.getAsJsonObject("message") : new JsonObject();

        StringBuilder text = new StringBuilder();
        JsonElement content = message.get("content");
        if (content != null && content.isJsonPrimitive()) {
            text.append(content.getAsString());
        } else if (content != null && content.isJsonArray()) {
            for (JsonElement part : content.getAsJsonArray()) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                    text.append(part.getAsJsonObject().get("text").getAsString());
                }
            }
        }

        List<AiChat.ToolCall> calls = new ArrayList<>();
        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            for (JsonElement element : message.getAsJsonArray("tool_calls")) {
                if (!element.isJsonObject()) continue;
                JsonObject call = element.getAsJsonObject();
                JsonObject function = call.has("function") && call.get("function").isJsonObject()
                        ? call.getAsJsonObject("function") : null;
                if (function == null || !function.has("name")) continue;
                String id = call.has("id") && call.get("id").isJsonPrimitive()
                        ? call.get("id").getAsString() : nextCallId();
                calls.add(new AiChat.ToolCall(id, function.get("name").getAsString(),
                        parseArgs(function.get("arguments")), null));
            }
        }
        return finish(text.toString(), calls);
    }

    private static JsonObject message(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    // ------------------------------------------------------------------ Anthropic (Claude)

    private static AiChat.Reply anthropic(AiProvider provider, String url, String key, String model, String system,
                                          List<AiChat.Msg> history, List<AiChat.ToolSpec> tools,
                                          RateLimitListener listener) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", 4096);
        if (system != null && !system.isEmpty()) body.addProperty("system", system);

        JsonArray messages = new JsonArray();
        JsonArray pendingResults = null;
        for (AiChat.Msg m : history) {
            if (AiChat.TOOL.equals(m.role)) {
                if (pendingResults == null) pendingResults = new JsonArray();
                JsonObject result = new JsonObject();
                result.addProperty("type", "tool_result");
                result.addProperty("tool_use_id", m.toolCallId);
                result.addProperty("content", m.text);
                pendingResults.add(result);
                continue;
            }
            if (pendingResults != null) {
                messages.add(roleWithBlocks("user", pendingResults));
                pendingResults = null;
            }
            if (AiChat.USER.equals(m.role)) {
                messages.add(message("user", m.text));
            } else {
                JsonArray blocks = new JsonArray();
                if (!m.text.isEmpty()) blocks.add(textBlock(m.text));
                for (AiChat.ToolCall call : m.toolCalls) {
                    JsonObject use = new JsonObject();
                    use.addProperty("type", "tool_use");
                    use.addProperty("id", call.id);
                    use.addProperty("name", call.name);
                    use.add("input", call.args);
                    blocks.add(use);
                }
                if (blocks.size() == 0) blocks.add(textBlock("(no text)"));
                messages.add(roleWithBlocks("assistant", blocks));
            }
        }
        if (pendingResults != null) messages.add(roleWithBlocks("user", pendingResults));
        body.add("messages", messages);

        if (!tools.isEmpty()) {
            JsonArray array = new JsonArray();
            for (AiChat.ToolSpec tool : tools) {
                JsonObject t = new JsonObject();
                t.addProperty("name", tool.name);
                t.addProperty("description", tool.description);
                t.add("input_schema", tool.parameters);
                array.add(t);
            }
            body.add("tools", array);
        }

        String[][] headers = {{"x-api-key", key}, {"anthropic-version", "2023-06-01"}};
        JsonObject root = parseObject(post(url.trim(), headers, body, listener));

        StringBuilder text = new StringBuilder();
        List<AiChat.ToolCall> calls = new ArrayList<>();
        if (root.has("content") && root.get("content").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("content")) {
                if (!element.isJsonObject()) continue;
                JsonObject block = element.getAsJsonObject();
                String type = block.has("type") ? block.get("type").getAsString() : "";
                if ("text".equals(type) && block.has("text")) {
                    text.append(block.get("text").getAsString());
                } else if ("tool_use".equals(type) && block.has("name")) {
                    String id = block.has("id") ? block.get("id").getAsString() : nextCallId();
                    calls.add(new AiChat.ToolCall(id, block.get("name").getAsString(), parseArgs(block.get("input")), null));
                }
            }
        }
        return finish(text.toString(), calls);
    }

    private static JsonObject roleWithBlocks(String role, JsonArray blocks) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.add("content", blocks);
        return o;
    }

    private static JsonObject textBlock(String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "text");
        o.addProperty("text", text);
        return o;
    }

    // ------------------------------------------------------------------ Google Gemini

    private static AiChat.Reply gemini(String url, String key, String model, String system,
                                       List<AiChat.Msg> history, List<AiChat.ToolSpec> tools,
                                       RateLimitListener listener) throws IOException {
        String endpoint = url.trim();
        if (!endpoint.contains(":generateContent")) {
            if (!endpoint.endsWith("/")) endpoint += "/";
            if (!endpoint.endsWith("models/")) endpoint += "models/";
            endpoint += (model.startsWith("models/") ? model.substring("models/".length()) : model) + ":generateContent";
        }

        JsonObject body = new JsonObject();
        if (system != null && !system.isEmpty()) {
            JsonObject instruction = new JsonObject();
            JsonArray parts = new JsonArray();
            parts.add(geminiText(system));
            instruction.add("parts", parts);
            body.add("systemInstruction", instruction);
        }

        JsonArray contents = new JsonArray();
        JsonArray pendingResults = null;
        for (AiChat.Msg m : history) {
            if (AiChat.TOOL.equals(m.role)) {
                if (pendingResults == null) pendingResults = new JsonArray();
                JsonObject response = new JsonObject();
                response.addProperty("result", m.text);
                JsonObject function = new JsonObject();
                function.addProperty("name", m.toolName);
                function.add("response", response);
                JsonObject part = new JsonObject();
                part.add("functionResponse", function);
                pendingResults.add(part);
                continue;
            }
            if (pendingResults != null) {
                contents.add(geminiContent("user", pendingResults));
                pendingResults = null;
            }
            JsonArray parts = new JsonArray();
            if (AiChat.USER.equals(m.role)) {
                parts.add(geminiText(m.text));
                contents.add(geminiContent("user", parts));
            } else {
                if (!m.text.isEmpty()) parts.add(geminiText(m.text));
                for (AiChat.ToolCall call : m.toolCalls) {
                    if (call.raw != null) {
                        parts.add(call.raw);
                    } else {
                        JsonObject function = new JsonObject();
                        function.addProperty("name", call.name);
                        function.add("args", call.args);
                        JsonObject part = new JsonObject();
                        part.add("functionCall", function);
                        parts.add(part);
                    }
                }
                if (parts.size() == 0) parts.add(geminiText("(no text)"));
                contents.add(geminiContent("model", parts));
            }
        }
        if (pendingResults != null) contents.add(geminiContent("user", pendingResults));
        body.add("contents", contents);

        if (!tools.isEmpty()) {
            JsonArray declarations = new JsonArray();
            for (AiChat.ToolSpec tool : tools) {
                JsonObject d = new JsonObject();
                d.addProperty("name", tool.name);
                d.addProperty("description", tool.description);
                d.add("parameters", tool.parameters);
                declarations.add(d);
            }
            JsonObject wrapper = new JsonObject();
            wrapper.add("functionDeclarations", declarations);
            JsonArray array = new JsonArray();
            array.add(wrapper);
            body.add("tools", array);
        }
        JsonObject config = new JsonObject();
        config.addProperty("maxOutputTokens", 4096);
        body.add("generationConfig", config);

        JsonObject root = parseObject(post(endpoint, new String[][]{{"x-goog-api-key", key}}, body, listener));

        JsonArray candidates = root.has("candidates") && root.get("candidates").isJsonArray()
                ? root.getAsJsonArray("candidates") : null;
        if (candidates == null || candidates.size() == 0) {
            String reason = "";
            if (root.has("promptFeedback") && root.get("promptFeedback").isJsonObject()
                    && root.getAsJsonObject("promptFeedback").has("blockReason")) {
                reason = " (" + root.getAsJsonObject("promptFeedback").get("blockReason").getAsString() + ")";
            }
            throw new AiException(400, "Gemini blocked or did not answer the request" + reason);
        }
        JsonObject candidate = candidates.get(0).getAsJsonObject();
        StringBuilder text = new StringBuilder();
        List<AiChat.ToolCall> calls = new ArrayList<>();
        if (candidate.has("content") && candidate.get("content").isJsonObject()
                && candidate.getAsJsonObject("content").has("parts")
                && candidate.getAsJsonObject("content").get("parts").isJsonArray()) {
            for (JsonElement element : candidate.getAsJsonObject("content").getAsJsonArray("parts")) {
                if (!element.isJsonObject()) continue;
                JsonObject part = element.getAsJsonObject();
                if (part.has("functionCall") && part.get("functionCall").isJsonObject()) {
                    JsonObject function = part.getAsJsonObject("functionCall");
                    if (!function.has("name")) continue;
                    calls.add(new AiChat.ToolCall(nextCallId(), function.get("name").getAsString(),
                            parseArgs(function.get("args")), part));
                } else if (part.has("text") && !(part.has("thought") && part.get("thought").getAsBoolean())) {
                    text.append(part.get("text").getAsString());
                }
            }
        }
        return finish(text.toString(), calls);
    }

    private static JsonObject geminiText(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        return part;
    }

    private static JsonObject geminiContent(String role, JsonArray parts) {
        JsonObject content = new JsonObject();
        content.addProperty("role", role);
        content.add("parts", parts);
        return content;
    }

    // ------------------------------------------------------------------ shared

    private static AiChat.Reply finish(String text, List<AiChat.ToolCall> calls) throws IOException {
        if (text.trim().isEmpty() && calls.isEmpty()) throw new IOException("The AI returned an empty reply");
        return new AiChat.Reply(text, calls);
    }

    private static String nextCallId() {
        return "call_" + CALL_COUNTER.incrementAndGet();
    }

    private static JsonObject parseArgs(JsonElement element) {
        try {
            if (element == null || element.isJsonNull()) return new JsonObject();
            if (element.isJsonObject()) return element.getAsJsonObject();
            if (element.isJsonPrimitive()) {
                String text = element.getAsString().trim();
                if (text.isEmpty()) return new JsonObject();
                JsonElement parsed = new JsonParser().parse(text);
                if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            }
        } catch (JsonParseException | IllegalStateException ignored) { }
        return new JsonObject();
    }

    private static JsonObject parseObject(String json) throws IOException {
        try {
            JsonElement element = new JsonParser().parse(json);
            if (element.isJsonObject()) return element.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException ignored) { }
        throw new IOException("The AI service sent an unreadable reply");
    }

    private static String post(String url, String[][] headers, JsonObject body, RateLimitListener listener)
            throws IOException {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        int rateRetries = 0;
        int serverRetries = 0;
        while (true) {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            int wait = 0;
            try {
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(120_000);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "SupersonicClient/1.0 (Android)");
                for (String[] header : headers) connection.setRequestProperty(header[0], header[1]);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
                int code = connection.getResponseCode();
                InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String text = stream == null ? "" : readAll(stream);
                if (code < 400) return text;

                if (code == 429 && rateRetries < MAX_RATE_RETRIES) {
                    int seconds = rateLimitWaitSeconds(connection, text);
                    if (seconds > MAX_WAIT_SECONDS) {
                        String detail = detailOf(text);
                        throw new AiException(429, "The limit of this AI plan is used up for now. It resets in about "
                                + Math.max(1, (seconds + 59) / 60) + " min. Wait, or switch to another model or provider in AI settings."
                                + (detail.isEmpty() ? "" : "\n" + detail));
                    }
                    wait = seconds > 0 ? seconds : 5 * (rateRetries + 1);
                    rateRetries++;
                    if (listener != null && !listener.onWait(wait)) throw new IOException("Stopped");
                } else if ((code == 502 || code == 503 || code == 529) && serverRetries < 2) {
                    serverRetries++;
                    wait = 2 * serverRetries;
                } else {
                    throw new AiException(code, describeError(code, text));
                }
            } finally {
                connection.disconnect();
            }
            try {
                Thread.sleep(wait * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted");
            }
        }
    }

    /**
     * How long to wait before the request may be sent again, in seconds (with a small safety margin).
     * Reads the Retry-After header, or the "try again in 1m5.2s" sentence of Groq / OpenAI style errors.
     * 0 if unknown.
     */
    static int rateLimitWaitSeconds(HttpURLConnection connection, String json) {
        int header = connection == null ? 0 : retryAfterSeconds(connection);
        if (header > 0) return header + 1;
        String message = detailOf(json);
        int index = message.toLowerCase(java.util.Locale.ROOT).indexOf("try again in ");
        if (index < 0) return 0;
        Matcher matcher = Pattern.compile("\\G(\\d+(?:\\.\\d+)?)(ms|h|m|s)")
                .matcher(message).region(index + "try again in ".length(), message.length());
        double millis = 0;
        boolean found = false;
        while (matcher.find()) {
            found = true;
            double value = Double.parseDouble(matcher.group(1));
            switch (matcher.group(2)) {
                case "h": millis += value * 3_600_000; break;
                case "m": millis += value * 60_000; break;
                case "s": millis += value * 1000; break;
                default: millis += value;
            }
        }
        return found ? (int) Math.ceil(millis / 1000.0) + 1 : 0;
    }

    private static String detailOf(String json) {
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            if (root.has("error")) {
                JsonElement error = root.get("error");
                if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
                    return error.getAsJsonObject().get("message").getAsString();
                }
                if (error.isJsonPrimitive()) return error.getAsString();
            }
        } catch (JsonParseException | IllegalStateException | ClassCastException ignored) { }
        return "";
    }

    private static int retryAfterSeconds(HttpURLConnection connection) {
        String value = connection.getHeaderField("retry-after");
        if (value == null) return 0;
        try {
            return (int) Math.ceil(Double.parseDouble(value.trim()));
        } catch (NumberFormatException e) {
            return 0;
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

    static String describeError(int code, String json) {
        String detail = null;
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            if (root.has("error")) {
                JsonElement error = root.get("error");
                if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
                    detail = error.getAsJsonObject().get("message").getAsString();
                } else if (error.isJsonPrimitive()) {
                    detail = error.getAsString();
                }
            }
        } catch (JsonParseException | IllegalStateException | ClassCastException ignored) { }
        String suffix = detail == null ? "" : "\n" + detail;

        switch (code) {
            case 401:
                return "The API key was rejected (401). Check it in AI settings." + suffix;
            case 403:
                return "The request was refused (403). The key may be disabled, or not allowed in your region." + suffix;
            case 404:
                return "The model or endpoint was not found (404). Change the model in AI settings." + suffix;
            case 413:
                return "The request was too large for this model (413). Start a new chat." + suffix;
            case 429:
                return "Rate limit or quota reached (429). Wait a minute, or switch provider in AI settings." + suffix;
            default:
                return "AI service error " + code + suffix;
        }
    }
}
