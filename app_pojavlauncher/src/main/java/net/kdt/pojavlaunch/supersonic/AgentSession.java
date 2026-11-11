package net.kdt.pojavlaunch.supersonic;

import android.content.Context;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One chat with SS Agent. It lives as long as the app process, so the conversation survives leaving the
 * screen. The agent loop: send the conversation to the AI, run the tools it asks for, send the results back,
 * and repeat until the AI answers without asking for a tool.
 */
public final class AgentSession {

    public static final int USER = 0, ASSISTANT = 1, TOOL = 2, NOTE = 3, ERROR = 4;

    public static final int DENY = 0, ALLOW = 1, ALLOW_ALL = 2;

    /** One line of what the user sees in the chat. */
    public static final class Entry {
        public final int kind;
        public final String text;

        Entry(int kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    /** The screen that shows the chat. All methods are called from a background thread. */
    public interface Listener {
        void onEntry(Entry entry);

        void onState(boolean running);

        /** Blocks until the user decides. @return {@link #DENY}, {@link #ALLOW} or {@link #ALLOW_ALL} */
        int approve(String title, String detail);
    }

    private static final int MAX_STEPS = 16;
    private static final int MAX_HISTORY = 40;
    private static final int OLD_RESULT_CHARS = 500;

    private static final AgentSession INSTANCE = new AgentSession();

    public static AgentSession get() {
        return INSTANCE;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SSAgent");
        thread.setDaemon(true);
        return thread;
    });
    private final List<AiChat.Msg> history = new ArrayList<>();
    private final List<Entry> transcript = new ArrayList<>();
    private volatile Listener listener;
    private volatile boolean running;
    private volatile boolean cancelled;
    private volatile boolean allowAll;

    private AgentSession() {}

    // ------------------------------------------------------------------ state for the screen

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public List<Entry> transcript() {
        synchronized (transcript) {
            return new ArrayList<>(transcript);
        }
    }

    /** Adds a line to the chat that is not part of the conversation with the AI. */
    public void note(String text) {
        add(NOTE, text);
    }

    public void cancel() {
        cancelled = true;
    }

    /** Starts a new chat. Does nothing while the agent is still working (it is asked to stop instead). */
    public boolean reset() {
        if (running) {
            cancelled = true;
            return false;
        }
        synchronized (history) {
            history.clear();
        }
        synchronized (transcript) {
            transcript.clear();
        }
        allowAll = false;
        return true;
    }

    // ------------------------------------------------------------------ running

    /** Sends the user's message and runs the agent loop in the background. */
    public void send(Context context, String userText) {
        if (running) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        add(USER, userText);
        synchronized (history) {
            history.add(AiChat.Msg.user(userText));
        }
        cancelled = false;
        setRunning(true);
        executor.execute(() -> runLoop(app));
    }

    private void runLoop(Context app) {
        try {
            AiProvider provider = AiConfig.provider(app);
            AgentTools tools = new AgentTools(app, (title, detail) -> askUser(title, detail) != DENY);
            List<AiChat.ToolSpec> specs = AgentTools.specs();

            for (int step = 0; step < MAX_STEPS && !cancelled; step++) {
                List<AiChat.Msg> snapshot = prepareHistory();
                AiChat.Reply reply = AiClient.chat(app, provider, AgentAi.systemPrompt(app), snapshot, specs);
                if (cancelled) break;

                synchronized (history) {
                    history.add(AiChat.Msg.assistant(reply.text, reply.toolCalls));
                }
                if (!reply.text.isEmpty()) add(ASSISTANT, AgentAi.plain(reply.text));
                if (reply.toolCalls.isEmpty()) break;

                for (AiChat.ToolCall call : reply.toolCalls) {
                    String result;
                    if (cancelled) {
                        result = "Cancelled by the user.";
                    } else {
                        result = tools.execute(call);
                        add(TOOL, AgentTools.describeCall(call) + "  →  " + oneLine(result, 140));
                    }
                    synchronized (history) {
                        history.add(AiChat.Msg.toolResult(call.id, call.name, result));
                    }
                }
                if (step == MAX_STEPS - 1) {
                    add(NOTE, "I stopped after " + MAX_STEPS + " steps. Say \"continue\" if there is more to do.");
                }
            }
            if (cancelled) add(NOTE, "Stopped.");
        } catch (IOException | RuntimeException e) {
            add(ERROR, friendly(e));
        } finally {
            setRunning(false);
        }
    }

    private int askUser(String title, String detail) {
        if (allowAll) return ALLOW;
        Listener current = listener;
        if (current == null) return DENY;
        int answer = current.approve(title, detail);
        if (answer == ALLOW_ALL) allowAll = true;
        return answer;
    }

    /** Keeps the conversation small: old tool results are shortened and the oldest messages are dropped. */
    private List<AiChat.Msg> prepareHistory() {
        synchronized (history) {
            int keepFrom = Math.max(0, history.size() - 8);
            for (int i = 0; i < keepFrom; i++) {
                AiChat.Msg m = history.get(i);
                if (AiChat.TOOL.equals(m.role) && m.text.length() > OLD_RESULT_CHARS) {
                    m.text = m.text.substring(0, OLD_RESULT_CHARS) + "\n[older result shortened]";
                }
            }
            while (history.size() > MAX_HISTORY) history.remove(0);
            // a conversation must start with a user message
            while (!history.isEmpty() && !AiChat.USER.equals(history.get(0).role)) history.remove(0);
            return new ArrayList<>(history);
        }
    }

    private void add(int kind, String text) {
        Entry entry = new Entry(kind, text);
        synchronized (transcript) {
            transcript.add(entry);
        }
        Listener current = listener;
        if (current != null) current.onEntry(entry);
    }

    private void setRunning(boolean value) {
        running = value;
        Listener current = listener;
        if (current != null) current.onState(value);
    }

    private static String oneLine(String text, int max) {
        String flat = text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    static String friendly(Exception e) {
        if (e instanceof UnknownHostException || e instanceof SocketException || e instanceof SocketTimeoutException) {
            return "No internet connection, or the AI service cannot be reached right now.";
        }
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    // ------------------------------------------------------------------ offline crash analysis

    /** Result of the offline part of a crash analysis. */
    public static final class CrashReport {
        /** What to show in the chat. */
        public final String text;
        /** What was fixed automatically; empty if nothing. The AI is told so that it does not repeat it. */
        public final String fixed;

        CrashReport(String text, String fixed) {
            this.text = text;
            this.fixed = fixed;
        }
    }

    /**
     * Runs right after a crash, without internet: scan, fix what is safe (if auto fix is on) and describe it.
     * Blocking, call from a background thread.
     */
    public static CrashReport localCrashReport(Context context) {
        List<AgentEngine.Finding> before = AgentEngine.scan(context);
        List<String> fixes = AiConfig.autoFix(context) ? AgentEngine.applySafeFixes(context, before) : new ArrayList<String>();
        List<AgentEngine.Finding> after = fixes.isEmpty() ? before : AgentEngine.scan(context);

        StringBuilder fixed = new StringBuilder();
        for (String fix : fixes) fixed.append("• ").append(fix).append('\n');

        StringBuilder out = new StringBuilder();
        if (fixed.length() > 0) {
            out.append("Fixed automatically:\n").append(fixed)
                    .append("Start the game again to check. You can undo this with the Undo button.\n");
        }
        StringBuilder open = new StringBuilder();
        for (AgentEngine.Finding finding : after) {
            if (finding.fixId == null && finding.severity >= AgentEngine.WARNING) {
                open.append("• ").append(finding.title).append(": ").append(finding.detail).append('\n');
            }
        }
        if (open.length() > 0) {
            if (out.length() > 0) out.append('\n');
            out.append("Needs your attention:\n").append(open);
        }
        if (out.length() == 0) out.append("No known crash pattern was found in the log.");
        return new CrashReport(out.toString().trim(), fixed.toString().trim());
    }
}
