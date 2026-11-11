package net.kdt.pojavlaunch.supersonic;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

/**
 * SS Agent AI settings, kept in the launcher preferences.
 * Groq is the default provider and works out of the box with the key compiled into this build
 * (GROQ_API_KEY / groq_key.txt). Every other provider needs the user's own key.
 */
public final class AiConfig {
    private AiConfig() {}

    private static final String PREF_PROVIDER = "ss_ai_provider";
    private static final String PREF_KEY = "ss_ai_key_";
    private static final String PREF_MODEL = "ss_ai_model_";
    private static final String PREF_URL = "ss_ai_url_";
    private static final String PREF_CONSENT = "ss_ai_consent";
    private static final String PREF_AUTOFIX = "ss_ai_autofix";
    private static final String PREF_AUTO_APPROVE = "ss_ai_auto_approve";

    private static SharedPreferences prefs(Context context) {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        return prefs != null ? prefs : PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static AiProvider provider(Context context) {
        return AiProvider.byId(prefs(context).getString(PREF_PROVIDER, AiProvider.GROQ.id));
    }

    public static void setProvider(Context context, AiProvider provider) {
        prefs(context).edit().putString(PREF_PROVIDER, provider.id).apply();
    }

    /** The key the user typed for this provider (empty if none). */
    public static String userKey(Context context, AiProvider provider) {
        return prefs(context).getString(PREF_KEY + provider.id, "").trim();
    }

    /** The key compiled into this build. Only Groq has one. */
    static String builtInKey(Context context, AiProvider provider) {
        if (provider != AiProvider.GROQ) return "";
        try {
            String built = context.getString(R.string.groq_api_key).trim();
            return "DUMMY".equals(built) ? "" : built;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The user's key first, then the key compiled into this build. */
    public static String apiKey(Context context, AiProvider provider) {
        String user = userKey(context, provider);
        return user.isEmpty() ? builtInKey(context, provider) : user;
    }

    public static boolean usesBuiltInKey(Context context, AiProvider provider) {
        return userKey(context, provider).isEmpty() && !builtInKey(context, provider).isEmpty();
    }

    /** True when the provider can be used right now. */
    public static boolean isReady(Context context, AiProvider provider) {
        if (provider == AiProvider.CUSTOM && baseUrl(context, provider).isEmpty()) return false;
        return provider.keyOptional() || !apiKey(context, provider).isEmpty();
    }

    public static void saveKey(Context context, AiProvider provider, String key) {
        prefs(context).edit().putString(PREF_KEY + provider.id, key == null ? "" : key.trim()).apply();
    }

    public static String model(Context context, AiProvider provider) {
        String model = prefs(context).getString(PREF_MODEL + provider.id, "").trim();
        return model.isEmpty() ? provider.defaultModel : model;
    }

    public static void saveModel(Context context, AiProvider provider, String model) {
        prefs(context).edit().putString(PREF_MODEL + provider.id, model == null ? "" : model.trim()).apply();
    }

    /** Full endpoint URL (OpenAI style: .../chat/completions). Empty for a custom provider without one. */
    public static String baseUrl(Context context, AiProvider provider) {
        String url = prefs(context).getString(PREF_URL + provider.id, "").trim();
        return url.isEmpty() ? provider.defaultUrl : url;
    }

    public static void saveBaseUrl(Context context, AiProvider provider, String url) {
        prefs(context).edit().putString(PREF_URL + provider.id, url == null ? "" : url.trim()).apply();
    }

    public static boolean hasConsent(Context context) {
        return prefs(context).getBoolean(PREF_CONSENT, false);
    }

    public static void setConsent(Context context, boolean consent) {
        prefs(context).edit().putBoolean(PREF_CONSENT, consent).apply();
    }

    /** Fix safe problems automatically after a crash. On by default; every fix can be undone. */
    public static boolean autoFix(Context context) {
        return prefs(context).getBoolean(PREF_AUTOFIX, true);
    }

    public static void setAutoFix(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_AUTOFIX, enabled).apply();
    }

    /** Full auto mode: the agent changes things without asking first. Off by default. */
    public static boolean autoApprove(Context context) {
        return prefs(context).getBoolean(PREF_AUTO_APPROVE, false);
    }

    public static void setAutoApprove(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_AUTO_APPROVE, enabled).apply();
    }

    /** "gsk_…abcd" style text that is safe to show on screen. */
    public static String maskedKey(Context context, AiProvider provider) {
        String key = apiKey(context, provider);
        if (key.length() <= 8) return key.isEmpty() ? "none" : "••••";
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }
}
