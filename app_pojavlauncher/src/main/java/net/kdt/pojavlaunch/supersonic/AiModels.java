package net.kdt.pojavlaunch.supersonic;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Asks an AI provider which models the user's key can use, so AI settings can offer them as a list
 * (Pro, Flash, Llama, DeepSeek...) instead of making the user type a model name.
 * Blocking network calls: use a background thread.
 */
public final class AiModels {
    private AiModels() {}

    /** One selectable model. */
    public static final class ModelInfo {
        /** The name to send to the API. */
        public final String id;
        /** A friendlier name if the provider gives one, otherwise empty. */
        public final String label;
        /** True if the provider says it costs nothing (OpenRouter). */
        public final boolean free;

        ModelInfo(String id, String label, boolean free) {
            this.id = id;
            this.label = label == null ? "" : label;
            this.free = free;
        }

        /** Text for a list row. */
        public String display() {
            StringBuilder out = new StringBuilder(id);
            if (!label.isEmpty() && !label.equals(id)) out.append("  ·  ").append(label);
            if (free) out.append("  ·  free");
            return out.toString();
        }
    }

    /** Words in a model id that mean it is not a chat model (speech, images, embeddings...). */
    private static final String[] NOT_CHAT = {
            "whisper", "tts", "embed", "guard", "moderation", "transcribe", "speech", "audio", "realtime",
            "dall-e", "image", "imagen", "veo", "lyria", "aqa", "rerank", "babbage", "davinci", "orpheus",
            "playai", "robotics"
    };

    /** Uses the typed key / URL if given, otherwise the saved settings. */
    public static List<ModelInfo> fetch(Context context, AiProvider provider, String typedKey, String typedUrl)
            throws IOException {
        String key = typedKey == null || typedKey.trim().isEmpty() ? AiConfig.apiKey(context, provider) : typedKey.trim();
        String url = typedUrl == null || typedUrl.trim().isEmpty() ? AiConfig.baseUrl(context, provider) : typedUrl.trim();
        return fetch(provider, url, key);
    }

    /** @param chatUrl the same endpoint URL that is used for chatting */
    public static List<ModelInfo> fetch(AiProvider provider, String chatUrl, String key) throws IOException {
        if (chatUrl == null || chatUrl.trim().isEmpty()) throw new IOException("No endpoint URL");
        List<ModelInfo> models = new ArrayList<>();

        switch (provider.protocol) {
            case GEMINI: {
                String base = geminiModelsUrl(chatUrl);
                String token = null;
                for (int page = 0; page < 5; page++) {
                    String url = base + "?pageSize=200" + (token == null ? "" : "&pageToken=" + encode(token));
                    token = parseGemini(AiClient.get(url, new String[][]{{"x-goog-api-key", key}}), models);
                    if (token == null) break;
                }
                break;
            }
            case ANTHROPIC:
                parseOpenAiStyle(AiClient.get(anthropicModelsUrl(chatUrl),
                        new String[][]{{"x-api-key", key}, {"anthropic-version", "2023-06-01"}}), models);
                break;
            default:
                parseOpenAiStyle(AiClient.get(openAiModelsUrl(chatUrl),
                        key.isEmpty() ? new String[0][] : new String[][]{{"Authorization", "Bearer " + key}}), models);
        }

        Collections.sort(models, (a, b) -> {
            if (a.free != b.free) return a.free ? -1 : 1;
            return a.id.compareToIgnoreCase(b.id);
        });
        if (models.isEmpty()) throw new IOException("The provider returned no usable models");
        return models;
    }

    // ------------------------------------------------------------------ urls

    static String openAiModelsUrl(String chatUrl) {
        String url = chatUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/chat/completions")) url = url.substring(0, url.length() - "/chat/completions".length());
        return url + "/models";
    }

    static String anthropicModelsUrl(String chatUrl) {
        String url = chatUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/messages")) url = url.substring(0, url.length() - "/messages".length());
        return url + "/models?limit=100";
    }

    static String geminiModelsUrl(String chatUrl) {
        String url = chatUrl.trim();
        int index = url.indexOf("/models");
        if (index >= 0) return url.substring(0, index) + "/models";
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url + "/models";
    }

    private static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return text;
        }
    }

    // ------------------------------------------------------------------ parsing

    private static boolean isChatModel(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        for (String word : NOT_CHAT) if (lower.contains(word)) return false;
        return true;
    }

    /** Groq, OpenAI, OpenRouter, Anthropic and custom servers: {"data":[{"id":...}]}. */
    static void parseOpenAiStyle(String json, List<ModelInfo> out) throws IOException {
        Set<String> seen = new HashSet<>();
        for (ModelInfo existing : out) seen.add(existing.id);
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            if (!root.has("data") || !root.get("data").isJsonArray()) throw new IOException("Unexpected model list");
            for (JsonElement element : root.getAsJsonArray("data")) {
                if (!element.isJsonObject()) continue;
                JsonObject model = element.getAsJsonObject();
                String id = text(model, "id");
                if (id.isEmpty() || !seen.add(id) || !isChatModel(id)) continue;
                if (model.has("active") && model.get("active").isJsonPrimitive() && !model.get("active").getAsBoolean()) continue;
                // OpenRouter lists what each model supports; SS Agent needs tool calling
                if (model.has("supported_parameters") && model.get("supported_parameters").isJsonArray()
                        && !contains(model.getAsJsonArray("supported_parameters"), "tools")) continue;

                String label = text(model, "display_name");
                if (label.isEmpty()) label = text(model, "name");
                out.add(new ModelInfo(id, label, isFree(model, id)));
            }
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new IOException("The provider sent an unreadable model list");
        }
    }

    /** Google: {"models":[{"name":"models/x","supportedGenerationMethods":[...]}],"nextPageToken":...}. */
    static String parseGemini(String json, List<ModelInfo> out) throws IOException {
        Set<String> seen = new HashSet<>();
        for (ModelInfo existing : out) seen.add(existing.id);
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            if (root.has("models") && root.get("models").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("models")) {
                    if (!element.isJsonObject()) continue;
                    JsonObject model = element.getAsJsonObject();
                    String id = text(model, "name");
                    if (id.startsWith("models/")) id = id.substring("models/".length());
                    if (id.isEmpty() || !seen.add(id) || !isChatModel(id)) continue;
                    if (!model.has("supportedGenerationMethods") || !model.get("supportedGenerationMethods").isJsonArray()
                            || !contains(model.getAsJsonArray("supportedGenerationMethods"), "generateContent")) continue;
                    out.add(new ModelInfo(id, text(model, "displayName"), false));
                }
            }
            String next = text(root, "nextPageToken");
            return next.isEmpty() ? null : next;
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new IOException("The provider sent an unreadable model list");
        }
    }

    private static boolean isFree(JsonObject model, String id) {
        if (id.endsWith(":free")) return true;
        if (!model.has("pricing") || !model.get("pricing").isJsonObject()) return false;
        JsonObject pricing = model.getAsJsonObject("pricing");
        return isZero(pricing, "prompt") && isZero(pricing, "completion");
    }

    private static boolean isZero(JsonObject pricing, String key) {
        String value = text(pricing, key);
        if (value.isEmpty()) return false;
        try {
            return Double.parseDouble(value) == 0.0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean contains(JsonArray array, String wanted) {
        for (JsonElement element : array) {
            if (element.isJsonPrimitive() && wanted.equals(element.getAsString())) return true;
        }
        return false;
    }

    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }
}
