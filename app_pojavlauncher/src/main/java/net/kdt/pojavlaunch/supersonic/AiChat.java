package net.kdt.pojavlaunch.supersonic;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Provider-neutral chat types shared by {@link AiClient}, {@link AgentSession} and {@link AgentTools}. */
public final class AiChat {
    private AiChat() {}

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
    public static final String TOOL = "tool";

    /** A function the model may call. {@code parameters} is a JSON Schema object. */
    public static final class ToolSpec {
        public final String name;
        public final String description;
        public final JsonObject parameters;

        public ToolSpec(String name, String description, JsonObject parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }
    }

    /** A function call requested by the model. */
    public static final class ToolCall {
        public final String id;
        public final String name;
        public final JsonObject args;
        /** Provider specific original part (Gemini needs its thought signatures sent back), may be null. */
        public final JsonObject raw;

        public ToolCall(String id, String name, JsonObject args, JsonObject raw) {
            this.id = id;
            this.name = name;
            this.args = args == null ? new JsonObject() : args;
            this.raw = raw;
        }
    }

    /** One message of the conversation. Roles: {@link #USER}, {@link #ASSISTANT}, {@link #TOOL}. */
    public static final class Msg {
        public final String role;
        public String text;
        public final List<ToolCall> toolCalls;
        /** Only for {@link #TOOL}: which call this answers. */
        public final String toolCallId;
        public final String toolName;

        private Msg(String role, String text, List<ToolCall> toolCalls, String toolCallId, String toolName) {
            this.role = role;
            this.text = text == null ? "" : text;
            this.toolCalls = toolCalls == null ? Collections.<ToolCall>emptyList() : toolCalls;
            this.toolCallId = toolCallId;
            this.toolName = toolName;
        }

        public static Msg user(String text) {
            return new Msg(USER, text, null, null, null);
        }

        public static Msg assistant(String text, List<ToolCall> calls) {
            return new Msg(ASSISTANT, text, calls == null ? null : new ArrayList<>(calls), null, null);
        }

        public static Msg toolResult(String callId, String toolName, String text) {
            return new Msg(TOOL, text, null, callId, toolName);
        }
    }

    /** What the model answered: some text, some tool calls, or both. */
    public static final class Reply {
        public final String text;
        public final List<ToolCall> toolCalls;

        public Reply(String text, List<ToolCall> toolCalls) {
            this.text = text == null ? "" : text.trim();
            this.toolCalls = toolCalls == null ? Collections.<ToolCall>emptyList() : toolCalls;
        }
    }
}
