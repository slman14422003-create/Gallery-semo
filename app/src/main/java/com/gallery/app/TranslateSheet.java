package com.gallery.app;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TranslateSheet {
    private static final String[] LANGS = {"ar", "en", "fr", "es", "de", "tr", "ru", "it", "pt", "fa", "ur", "hi", "zh", "ja", "ko", "id"};

    private final BaseActivity a;
    private final String text;
    private final List<TextView> chips = new ArrayList<>();
    private TextView result;
    private ProgressBar bar;
    private String target;
    private String source;
    private String output = "";
    private int gen;

    private TranslateSheet(BaseActivity a, String text) {
        this.a = a;
        this.text = text;
        this.target = Store.str(a, "ocr_target", Lang.isAr() ? "ar" : "en");
    }

    static void show(BaseActivity a, String text) {
        if (text == null || text.trim().isEmpty()) return;
        new TranslateSheet(a, text).open();
    }

    private static String name(String code) {
        Locale ui = Lang.isAr() ? new Locale("ar") : Locale.ENGLISH;
        String n = Locale.forLanguageTag(code).getDisplayLanguage(ui);
        if (n.isEmpty()) return code;
        return n.substring(0, 1).toUpperCase(ui) + n.substring(1);
    }

    private static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '؀' && c <= 'ۿ') return true;
        }
        return false;
    }

    private void open() {
        LinearLayout box = Ui.box(a);
        TextView orig = new TextView(a);
        orig.setText(text);
        orig.setMaxLines(4);
        orig.setEllipsize(TextUtils.TruncateAt.END);
        orig.setTextSize(14);
        orig.setTextColor(Ui.color(a, R.color.text_secondary));
        orig.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        box.addView(orig);
        box.addView(Ui.label(a, a.getString(R.string.tr_to)));

        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (final String code : LANGS) {
            final TextView ch = Ui.chip(a, name(code), code.equals(target));
            ch.setTag(code);
            ch.setOnClickListener(v -> {
                target = code;
                Store.setStr(a, "ocr_target", code);
                refreshChips();
                translate();
            });
            chips.add(ch);
            row.addView(ch);
        }
        hs.addView(row);
        box.addView(hs);

        bar = new ProgressBar(a);
        Ui.tint(a, bar);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(a, 28), Ui.dp(a, 28));
        bp.gravity = Gravity.CENTER_HORIZONTAL;
        bp.topMargin = Ui.dp(a, 16);
        box.addView(bar, bp);

        result = new TextView(a);
        result.setTextSize(17);
        result.setLineSpacing(0, 1.15f);
        result.setTextColor(Ui.color(a, R.color.text_primary));
        result.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        result.setTextIsSelectable(true);
        result.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 8));
        box.addView(result);

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new Dlg(a).setTitle(R.string.tr_title).setView(sv)
                .setPositiveButton(R.string.done, null)
                .setNeutralButton(R.string.ocr_copy, (d, w) -> {
                    if (!output.isEmpty()) LiveActions.copy(a, output);
                })
                .setOnDismissListener(d -> gen++)
                .show();
        translate();
    }

    private void refreshChips() {
        for (TextView t : chips) Ui.setChip(a, t, target.equals(t.getTag()));
    }

    private void detect(final java.util.function.Consumer<String> done) {
        if (source != null) {
            done.accept(source);
            return;
        }
        final String guess = hasArabic(text) ? "ar" : "en";
        LanguageIdentification.getClient().identifyLanguage(text)
                .addOnSuccessListener(code -> {
                    source = "und".equals(code) ? guess : code;
                    done.accept(source);
                })
                .addOnFailureListener(e -> {
                    source = guess;
                    done.accept(source);
                });
    }

    private void translate() {
        final int my = ++gen;
        bar.setVisibility(View.VISIBLE);
        result.setText(R.string.tr_working);
        output = "";
        detect(code -> {
            if (my != gen) return;
            if (code.equals(target) && Store.str(a, "ocr_target", "").isEmpty()) {
                target = "en".equals(code) ? "ar" : "en";
                refreshChips();
            }
            String s = TranslateLanguage.fromLanguageTag(code);
            String t = TranslateLanguage.fromLanguageTag(target);
            if (s == null || t == null) {
                fail(R.string.tr_unsupported);
                return;
            }
            if (s.equals(t)) {
                finish(text);
                return;
            }
            final Translator tr = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(s).setTargetLanguage(t).build());
            tr.downloadModelIfNeeded()
                    .addOnSuccessListener(v -> tr.translate(text)
                            .addOnSuccessListener(out -> {
                                if (my == gen) finish(out);
                            })
                            .addOnFailureListener(e -> {
                                if (my == gen) fail(R.string.tr_failed);
                            })
                            .addOnCompleteListener(x -> tr.close()))
                    .addOnFailureListener(e -> {
                        if (my == gen) fail(R.string.tr_failed);
                        tr.close();
                    });
        });
    }

    private void finish(String s) {
        output = s;
        bar.setVisibility(View.GONE);
        result.setText(s);
    }

    private void fail(int msg) {
        bar.setVisibility(View.GONE);
        result.setText(msg);
    }
}
