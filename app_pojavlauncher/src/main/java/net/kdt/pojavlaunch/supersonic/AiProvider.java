package net.kdt.pojavlaunch.supersonic;

/** The AI services SS Agent can talk to. Model names change often, so every model can be edited in AI settings. */
public enum AiProvider {
    GROQ("groq", "Groq", Protocol.OPENAI,
            "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile",
            "https://console.groq.com/keys"),
    OPENAI("openai", "ChatGPT (OpenAI)", Protocol.OPENAI,
            "https://api.openai.com/v1/chat/completions", "gpt-5.6-terra",
            "https://platform.openai.com/api-keys"),
    ANTHROPIC("anthropic", "Claude (Anthropic)", Protocol.ANTHROPIC,
            "https://api.anthropic.com/v1/messages", "claude-sonnet-5-5",
            "https://console.anthropic.com/settings/keys"),
    GEMINI("gemini", "Gemini (Google)", Protocol.GEMINI,
            "https://generativelanguage.googleapis.com/v1beta/models/", "gemini-3.6-flash",
            "https://aistudio.google.com/apikey"),
    OPENROUTER("openrouter", "OpenRouter (many models)", Protocol.OPENAI,
            "https://openrouter.ai/api/v1/chat/completions", "openrouter/auto",
            "https://openrouter.ai/keys"),
    CUSTOM("custom", "Custom (OpenAI compatible)", Protocol.OPENAI,
            "", "",
            "");

    /** The request format a provider speaks. */
    public enum Protocol { OPENAI, ANTHROPIC, GEMINI }

    public final String id;
    public final String label;
    public final Protocol protocol;
    public final String defaultUrl;
    public final String defaultModel;
    public final String keyUrl;

    AiProvider(String id, String label, Protocol protocol, String defaultUrl, String defaultModel, String keyUrl) {
        this.id = id;
        this.label = label;
        this.protocol = protocol;
        this.defaultUrl = defaultUrl;
        this.defaultModel = defaultModel;
        this.keyUrl = keyUrl;
    }

    public static AiProvider byId(String id) {
        for (AiProvider provider : values()) {
            if (provider.id.equals(id)) return provider;
        }
        return GROQ;
    }

    /** Custom endpoints (local servers, proxies) may need no key at all. */
    public boolean keyOptional() {
        return this == CUSTOM;
    }
}
