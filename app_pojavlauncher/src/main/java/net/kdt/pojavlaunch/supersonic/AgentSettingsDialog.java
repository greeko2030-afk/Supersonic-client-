package net.kdt.pojavlaunch.supersonic;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/** Choose the AI service, key and model of SS Agent, and how much it may do on its own. */
public final class AgentSettingsDialog {
    private AgentSettingsDialog() {}

    /** @param onSaved runs after Save or Remove key, on the UI thread; may be null */
    public static void show(Context context, Runnable onSaved) {
        final AiProvider[] providers = AiProvider.values();
        final AiProvider[] selected = {AiConfig.provider(context)};

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView intro = new TextView(context);
        intro.setText("Pick the AI that powers SS Agent. Groq works out of the box. For ChatGPT, Claude, Gemini and "
                + "others, paste your own API key. Keys are stored only on this phone.");
        box.addView(intro);

        String[] labels = new String[providers.length];
        int current = 0;
        for (int i = 0; i < providers.length; i++) {
            labels[i] = providers[i].label;
            if (providers[i] == selected[0]) current = i;
        }
        final Spinner spinner = new Spinner(context);
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(current);
        box.addView(spinner);

        final TextView keyInfo = new TextView(context);
        box.addView(keyInfo);
        final EditText key = new EditText(context);
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(key);

        final EditText model = new EditText(context);
        model.setSingleLine(true);
        box.addView(model);

        final EditText url = new EditText(context);
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(url);

        final TextView link = new TextView(context);
        link.setTextIsSelectable(true);
        box.addView(link);

        final CheckBox autoFix = new CheckBox(context);
        autoFix.setText("After a crash, fix safe problems automatically (undoable)");
        autoFix.setChecked(AiConfig.autoFix(context));
        box.addView(autoFix);

        final CheckBox autoApprove = new CheckBox(context);
        autoApprove.setText("Full auto: let SS Agent change files, mods and settings without asking (undoable)");
        autoApprove.setChecked(AiConfig.autoApprove(context));
        box.addView(autoApprove);

        final Context ctx = context;
        final Runnable bind = () -> {
            AiProvider provider = selected[0];
            String info;
            if (!AiConfig.userKey(ctx, provider).isEmpty()) info = "Saved key: " + AiConfig.maskedKey(ctx, provider);
            else if (AiConfig.usesBuiltInKey(ctx, provider)) info = "Using the key built into this app.";
            else if (provider.keyOptional()) info = "A key is optional for local servers.";
            else info = "No key yet.";
            keyInfo.setText(info);
            key.setText("");
            key.setHint("Paste API key (empty keeps the current one)");
            model.setText(AiConfig.model(ctx, provider));
            model.setHint(provider.defaultModel.isEmpty() ? "Model name" : provider.defaultModel);
            boolean custom = provider == AiProvider.CUSTOM;
            url.setVisibility(custom ? View.VISIBLE : View.GONE);
            url.setHint("Endpoint, e.g. https://host/v1/chat/completions");
            url.setText(custom ? AiConfig.baseUrl(ctx, provider) : "");
            link.setText(provider.keyUrl.isEmpty() ? "Model names change over time. If you get a 404, enter the current name."
                    : "Get a key: " + provider.keyUrl + "\nModel names change over time. If you get a 404, enter the current name.");
        };
        bind.run();
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (providers[position] == selected[0]) return;
                selected[0] = providers[position];
                bind.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);
        FrameLayout frame = new FrameLayout(context);
        int side = SupersonicUi.dp(context, 20);
        frame.setPadding(side, SupersonicUi.dp(context, 8), side, 0);
        frame.addView(scroll);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle("SS Agent AI settings")
                .setView(frame)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Save", (d, w) -> {
                    AiProvider provider = selected[0];
                    AiConfig.setProvider(ctx, provider);
                    String typed = key.getText().toString().trim();
                    if (!typed.isEmpty()) AiConfig.saveKey(ctx, provider, typed);
                    AiConfig.saveModel(ctx, provider, model.getText().toString());
                    if (provider == AiProvider.CUSTOM) AiConfig.saveBaseUrl(ctx, provider, url.getText().toString());
                    AiConfig.setAutoFix(ctx, autoFix.isChecked());
                    AiConfig.setAutoApprove(ctx, autoApprove.isChecked());
                    if (onSaved != null) onSaved.run();
                });
        final AiProvider initial = selected[0];
        if (!AiConfig.userKey(context, initial).isEmpty()) {
            builder.setNeutralButton("Remove key", (d, w) -> {
                AiConfig.saveKey(ctx, initial, "");
                if (onSaved != null) onSaved.run();
            });
        }
        builder.show();
    }
}
