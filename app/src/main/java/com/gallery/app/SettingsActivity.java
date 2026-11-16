package com.gallery.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;


/** Appearance, language, gallery and player options, access and updates. */
public class SettingsActivity extends BaseActivity {
    private LinearLayout content;
    private NavBar nav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        useGroupedBackground();
        ((TextView) findViewById(R.id.title)).setText(R.string.settings);
        ((TextView) findViewById(R.id.subtitle)).setText(R.string.settings_sub);
        findViewById(R.id.btnBack).setVisibility(View.GONE);
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        content = findViewById(R.id.content);
        Ui.autoGroup(this, content);
        nav = NavBar.attach(this, NavBar.SETTINGS, this::goTab);
        Motion.install(this, R.anim.tab_in, R.anim.tab_out, R.anim.tab_in, R.anim.tab_out);
    }

    private void goTab(int idx) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("tab", idx);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
        Motion.legacy(this, R.anim.tab_in, R.anim.tab_out);
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

    private static final int BLUE = 0xFF007AFF, INDIGO = 0xFF5856D6, PURPLE = 0xFFAF52DE, GREEN = 0xFF34C759,
            ORANGE = 0xFFFF9500, RED = 0xFFFF3B30, GRAY = 0xFF8E8E93, TEAL = 0xFF30B0C7, PINK = 0xFFFF2D55;

    private void render() {
        content.removeAllViews();

        content.addView(Ui.hero(this, getString(R.string.app_name), getString(R.string.set_version, BuildConfig.VERSION_NAME)));

        content.addView(Ui.sectionTitle(this, getString(R.string.theme_title)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_palette, true,
                getString(R.string.theme_title),
                getString(Appearance.modeName(Store.themeMode(this))), false, true).tint(INDIGO),
                v -> pickMode()));
        content.addView(Ui.footer(this, getString(R.string.theme_mode_note)));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_general)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_language, false,
                getString(R.string.set_language), languageName(), false, true).tint(BLUE), v -> pickLanguage()));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_player)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_video, false,
                getString(R.string.set_engine), engineName(), false, true).tint(RED), v -> pickEngine()));
        Ui.Toggle hw = Ui.toggle(this, content, R.string.set_hw, R.string.set_hw_sub, Store.bool(this, "hw", true), R.drawable.ic_speed, ORANGE);
        hw.onChange((b, on) -> Store.setBool(this, "hw", on));
        content.addView(hw.view);
        Ui.Toggle resume = Ui.toggle(this, content, R.string.set_resume, R.string.set_resume_sub, Store.bool(this, "resume", true), R.drawable.ic_clock, TEAL);
        resume.onChange((b, on) -> Store.setBool(this, "resume", on));
        content.addView(resume.view);
        Ui.Toggle next = Ui.toggle(this, content, R.string.set_autonext, R.string.set_autonext_sub, Store.bool(this, "autonext", true), R.drawable.ic_skip_next, GREEN);
        next.onChange((b, on) -> Store.setBool(this, "autonext", on));
        content.addView(next.view);
        Ui.Toggle land = Ui.toggle(this, content, R.string.set_autorotate, R.string.set_autorotate_sub, Store.bool(this, "autorotate", true), R.drawable.ic_rotate, PURPLE);
        land.onChange((b, on) -> Store.setBool(this, "autorotate", on));
        content.addView(land.view);
        Ui.Toggle pip = Ui.toggle(this, content, R.string.set_pip, R.string.set_pip_sub, Store.bool(this, "pip", true), R.drawable.ic_pip, PINK);
        pip.onChange((b, on) -> Store.setBool(this, "pip", on));
        content.addView(pip.view);
        content.addView(Ui.footer(this, getString(R.string.set_engine_sub)));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_access)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_shield, false,
                getString(R.string.set_media_access),
                getString(Perms.hasMedia(this) ? (Perms.isPartial(this) ? R.string.access_partial : R.string.access_granted)
                        : R.string.access_denied), false, true).tint(GREEN), v -> Perms.openAppSettings(this)));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_updates)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_download, false,
                getString(R.string.upd_check), getString(R.string.set_version, BuildConfig.VERSION_NAME),
                false, true).tint(BLUE), v -> startActivity(new Intent(this, UpdateActivity.class))));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_about)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_info, false,
                getString(R.string.app_name), getString(R.string.set_version, BuildConfig.VERSION_NAME),
                false, false).tint(GRAY), null));
        content.addView(Ui.footer(this, getString(R.string.about_formats)));
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

    private void pickMode() {
        final String[] codes = {Appearance.AUTO, Appearance.LIGHT, Appearance.DARK};
        String cur = Store.themeMode(this);
        String[] names = {getString(R.string.theme_auto), getString(R.string.theme_light), getString(R.string.theme_dark)};
        for (int i = 0; i < names.length; i++) names[i] = (codes[i].equals(cur) ? "● " : "○ ") + names[i];
        new Dlg(this).setTitle(R.string.theme_title).setItems(names, (d, which) -> {
            Store.setThemeMode(this, codes[which]);
            Appearance.applyMode(this);   // AppCompat recreates the open screens in the new look
            render();
        }).show();
    }

    private void pickEngine() {
        final String[] codes = {"auto", "exo", "vlc"};
        String cur = Store.str(this, "engine", "auto");
        String[] names = {getString(R.string.eng_auto), getString(R.string.eng_exo), getString(R.string.eng_vlc)};
        for (int i = 0; i < names.length; i++) names[i] = (codes[i].equals(cur) ? "● " : "○ ") + names[i];
        new Dlg(this).setTitle(R.string.set_engine).setItems(names, (d, which) -> {
            Store.setStr(this, "engine", codes[which]);
            render();
        }).show();
    }
}
