package com.gallery.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

/** Appearance, language, gallery and player options, access and updates. */
public class SettingsActivity extends BaseActivity {
    private LinearLayout content;
    private NavBar nav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        ((TextView) findViewById(R.id.title)).setText(R.string.settings);
        ((TextView) findViewById(R.id.subtitle)).setText(R.string.settings_sub);
        findViewById(R.id.btnBack).setVisibility(View.GONE);
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        content = findViewById(R.id.content);
        Ui.autoGroup(this, content);
        nav = NavBar.attach(this, NavBar.SETTINGS, this::goTab);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goTab(NavBar.PHOTOS);
            }
        });
    }

    private void goTab(int idx) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("tab", idx);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
        overridePendingTransition(R.anim.tab_in, R.anim.tab_out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private String languageName() {
        String v = Store.language(this);
        if ("ar".equals(v)) return getString(R.string.lang_ar);
        if ("en".equals(v)) return getString(R.string.lang_en);
        return getString(R.string.lang_system);
    }

    private String engineName() {
        String e = Store.str(this, "engine", "auto");
        if ("exo".equals(e)) return getString(R.string.eng_exo);
        if ("vlc".equals(e)) return getString(R.string.eng_vlc);
        return getString(R.string.eng_auto);
    }

    private void render() {
        content.removeAllViews();

        content.addView(Ui.sectionTitle(this, getString(R.string.theme_title)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_palette, true,
                getString(R.string.theme_title),
                getString(Appearance.modeName(Store.themeMode(this))) + " · "
                        + getString(Appearance.current(this).name), false, true),
                v -> startActivity(new Intent(this, ThemeActivity.class))));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_general)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_language, false,
                getString(R.string.set_language), languageName(), false, true), v -> pickLanguage()));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_player)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_video, false,
                getString(R.string.set_engine), engineName(), false, true), v -> pickEngine()));
        Ui.Toggle hw = Ui.toggle(this, content, R.string.set_hw, R.string.set_hw_sub, Store.bool(this, "hw", true));
        hw.onChange((b, on) -> Store.setBool(this, "hw", on));
        content.addView(hw.view);
        Ui.Toggle resume = Ui.toggle(this, content, R.string.set_resume, R.string.set_resume_sub, Store.bool(this, "resume", true));
        resume.onChange((b, on) -> Store.setBool(this, "resume", on));
        content.addView(resume.view);
        Ui.Toggle next = Ui.toggle(this, content, R.string.set_autonext, R.string.set_autonext_sub, Store.bool(this, "autonext", true));
        next.onChange((b, on) -> Store.setBool(this, "autonext", on));
        content.addView(next.view);
        Ui.Toggle land = Ui.toggle(this, content, R.string.set_autorotate, R.string.set_autorotate_sub, Store.bool(this, "autorotate", true));
        land.onChange((b, on) -> Store.setBool(this, "autorotate", on));
        content.addView(land.view);
        Ui.Toggle pip = Ui.toggle(this, content, R.string.set_pip, R.string.set_pip_sub, Store.bool(this, "pip", true));
        pip.onChange((b, on) -> Store.setBool(this, "pip", on));
        content.addView(pip.view);

        content.addView(Ui.sectionTitle(this, getString(R.string.set_access)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_shield, false,
                getString(R.string.set_media_access),
                getString(Perms.hasMedia(this) ? (Perms.isPartial(this) ? R.string.access_partial : R.string.access_granted)
                        : R.string.access_denied), false, true), v -> Perms.openAppSettings(this)));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_updates)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_download, false,
                getString(R.string.upd_check), getString(R.string.set_version, BuildConfig.VERSION_NAME),
                false, true), v -> startActivity(new Intent(this, UpdateActivity.class))));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_about)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_info, false,
                getString(R.string.app_name), getString(R.string.set_version, BuildConfig.VERSION_NAME),
                false, false), null));
        content.addView(Ui.noteCard(this, getString(R.string.about_formats), R.color.text_secondary));
    }

    private void pickLanguage() {
        final String[] codes = {"system", "ar", "en"};
        String cur = Store.language(this);
        String[] names = {getString(R.string.lang_system), getString(R.string.lang_ar), getString(R.string.lang_en)};
        for (int i = 0; i < names.length; i++) names[i] = (codes[i].equals(cur) ? "● " : "○ ") + names[i];
        new Dlg(this).setTitle(R.string.set_language).setItems(names, (d, which) -> {
            Store.setLanguage(this, codes[which]);
            Lang.apply(this);   // recreates the open screens in the new language
        }).show();
    }

    private void pickEngine() {
        final String[] codes = {"auto", "exo", "vlc"};
        String cur = Store.str(this, "engine", "auto");
        String[] names = {getString(R.string.eng_auto), getString(R.string.eng_exo), getString(R.string.eng_vlc)};
        for (int i = 0; i < names.length; i++) names[i] = (codes[i].equals(cur) ? "● " : "○ ") + names[i];
        new Dlg(this).setTitle(R.string.set_engine).setMessage(R.string.set_engine_sub).setItems(names, (d, which) -> {
            Store.setStr(this, "engine", codes[which]);
            render();
        }).show();
    }
}
