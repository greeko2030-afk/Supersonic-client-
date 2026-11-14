package net.kdt.pojavlaunch.supersonic;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Choose the AI service, key and model of SS Agent, and how much it may do on its own.
 * When the provider changes (or a key is entered), the models that the key can use are detected live and
 * offered in a searchable list, so nobody has to know model names.
 */
public final class AgentSettingsDialog {
    private AgentSettingsDialog() {}

    private interface Picked {
        void onPick(String modelId);
    }

    /** @param onSaved runs after Save or Remove key, on the UI thread; may be null */
    public static void show(Context context, Runnable onSaved) {
        final AiProvider[] providers = AiProvider.values();
        final AiProvider[] selected = {AiConfig.provider(context)};
        final Context ctx = context;

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

        final EditText url = new EditText(context);
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(url);

        final EditText model = new EditText(context);
        model.setSingleLine(true);
        box.addView(model);

        final Button pick = new Button(context);
        pick.setAllCaps(false);
        box.addView(pick);
        final TextView modelStatus = new TextView(context);
        modelStatus.setTextSize(12);
        box.addView(modelStatus);

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

        // models found so far for each provider, and the key they were found with
        final Map<AiProvider, List<AiModels.ModelInfo>> found = new HashMap<>();
        final Map<AiProvider, String> foundWithKey = new HashMap<>();

        // ---- detecting models
        final Runnable[] detect = new Runnable[1];
        final Runnable showModelStatus = () -> {
            AiProvider provider = selected[0];
            List<AiModels.ModelInfo> list = found.get(provider);
            if (list == null) return;
            pick.setText("Choose model (" + list.size() + " found)");
            pick.setEnabled(true);
            String typed = model.getText().toString().trim();
            boolean known = false;
            for (AiModels.ModelInfo m : list) if (m.id.equals(typed)) known = true;
            modelStatus.setText(typed.isEmpty() || known
                    ? list.size() + " models available for your key. Tap the button to choose."
                    : "\"" + typed + "\" is not in the list of " + list.size() + " models. Tap the button to choose one.");
        };

        detect[0] = () -> {
            final AiProvider provider = selected[0];
            final String typedKey = key.getText().toString().trim();
            final String effectiveKey = typedKey.isEmpty() ? AiConfig.apiKey(ctx, provider) : typedKey;
            final String endpoint = provider == AiProvider.CUSTOM
                    ? url.getText().toString().trim() : AiConfig.baseUrl(ctx, provider);
            if (effectiveKey.isEmpty() && !provider.keyOptional()) {
                pick.setText("Choose model");
                pick.setEnabled(false);
                modelStatus.setText("Add an API key to see the models you can use. You can also type a model name above.");
                return;
            }
            if (endpoint.isEmpty()) {
                pick.setText("Choose model");
                pick.setEnabled(false);
                modelStatus.setText("Enter the endpoint URL to see the models of this server.");
                return;
            }
            pick.setText("Detecting models…");
            pick.setEnabled(false);
            modelStatus.setText("Asking " + provider.label + " which models your key can use…");
            new Thread(() -> {
                List<AiModels.ModelInfo> result = null;
                String error = null;
                try {
                    result = AiModels.fetch(provider, endpoint, effectiveKey);
                } catch (IOException | RuntimeException e) {
                    error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final List<AiModels.ModelInfo> finalResult = result;
                final String finalError = error;
                modelStatus.post(() -> {
                    if (selected[0] != provider) return; // the user switched provider meanwhile
                    if (finalResult != null) {
                        found.put(provider, finalResult);
                        foundWithKey.put(provider, effectiveKey);
                        showModelStatus.run();
                    } else {
                        pick.setText("Retry loading models");
                        pick.setEnabled(true);
                        modelStatus.setText("Could not load the model list: " + finalError
                                + "\nYou can still type a model name above.");
                    }
                });
            }, "SSAgentModels").start();
        };

        pick.setOnClickListener(v -> {
            AiProvider provider = selected[0];
            List<AiModels.ModelInfo> list = found.get(provider);
            String typedKey = key.getText().toString().trim();
            boolean keyChanged = !typedKey.isEmpty() && !typedKey.equals(foundWithKey.get(provider));
            if (list == null || list.isEmpty() || keyChanged) {
                detect[0].run();
                return;
            }
            showPicker(ctx, list, model.getText().toString().trim(), id -> {
                model.setText(id);
                showModelStatus.run();
            });
        });

        // ---- filling the fields for the selected provider
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
            link.setText(provider.keyUrl.isEmpty() ? "" : "Get a key: " + provider.keyUrl);
            if (found.containsKey(provider)) showModelStatus.run();
            else detect[0].run();
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

    /** A searchable list of models. */
    private static void showPicker(Context context, List<AiModels.ModelInfo> models, String current, Picked picked) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);

        final EditText filter = new EditText(context);
        filter.setSingleLine(true);
        filter.setHint("Search, e.g. pro, flash, llama");
        box.addView(filter);

        final List<AiModels.ModelInfo> shown = new ArrayList<>(models);
        final List<String> rows = new ArrayList<>();
        for (AiModels.ModelInfo m : shown) rows.add(rowText(m, current));
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, rows);
        final ListView list = new ListView(context);
        list.setAdapter(adapter);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SupersonicUi.dp(context, 360)));

        final AlertDialog[] dialog = new AlertDialog[1];
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= shown.size()) return;
            picked.onPick(shown.get(position).id);
            if (dialog[0] != null) dialog[0].dismiss();
        });

        filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                String query = s.toString().trim().toLowerCase(Locale.ROOT);
                shown.clear();
                rows.clear();
                for (AiModels.ModelInfo m : models) {
                    if (query.isEmpty() || m.id.toLowerCase(Locale.ROOT).contains(query)
                            || m.label.toLowerCase(Locale.ROOT).contains(query)) {
                        shown.add(m);
                        rows.add(rowText(m, current));
                    }
                }
                adapter.notifyDataSetChanged();
            }
        });

        dialog[0] = new AlertDialog.Builder(context)
                .setTitle("Choose a model (" + models.size() + ")")
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String rowText(AiModels.ModelInfo model, String current) {
        return (model.id.equals(current) ? "✓ " : "") + model.display();
    }
}
