package com.gallery.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Settings, redesigned like iOS 26: a slim top bar whose centred title fades in as the big title scrolls away, an app card
 * that opens updates, appearance as three live previews, language as a segmented control, Live Text status, then the
 * player, access and about groups.
 */
public class SettingsActivity extends BaseActivity {
    private LinearLayout content;
    private NavBar nav;
    private TextView barTitle;

    private static final int BLUE = 0xFF007AFF, INDIGO = 0xFF5856D6, PURPLE = 0xFFAF52DE, GREEN = 0xFF34C759,
            ORANGE = 0xFFFF9500, RED = 0xFFFF3B30, GRAY = 0xFF8E8E93, TEAL = 0xFF30B0C7, PINK = 0xFFFF2D55;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.color(this, R.color.bg_group));
        setContentView(root);
        useGroupedBackground();
        enableSwipeBack();

        // slim top bar: back disc on the start side, small title that appears once the large one has scrolled away
        FrameLayout bar = new FrameLayout(this);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.text_primary)));
        back.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Ui.color(this, R.color.surface));
        bg.setStroke(Math.max(1, Ui.dp(this, 1) / 2), Ui.color(this, R.color.stroke_soft));
        back.setBackground(bg);
        back.setContentDescription(getString(R.string.back));
        Ui.press(this, back);
        back.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.START | Gravity.CENTER_VERTICAL);
        bl.setMarginStart(Ui.dp(this, 16));
        bar.addView(back, bl);
        barTitle = new TextView(this);
        barTitle.setText(R.string.settings);
        barTitle.setTextSize(17);
        barTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        barTitle.setTextColor(Ui.color(this, R.color.text_primary));
        barTitle.setAlpha(0f);
        bar.addView(barTitle, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, Ui.dp(this, 120));
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        final int fade = Ui.dp(this, 46);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> barTitle.setAlpha(Math.max(0f, Math.min(1f, (y - fade * 0.4f) / (fade * 0.6f)))));

        Ui.autoGroup(this, content);
        nav = NavBar.attach(this, NavBar.SETTINGS, this::goTab);
        Motion.install(this, R.anim.act_open_enter, R.anim.act_open_exit, R.anim.act_close_enter, R.anim.act_close_exit);
    }

    private void goTab(int idx) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("tab", idx);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
        Motion.legacy(this, R.anim.act_close_enter, R.anim.act_close_exit);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private String engineName() {
        String e = Store.str(this, "engine", "auto");
        if ("exo".equals(e)) return getString(R.string.eng_exo);
        if ("vlc".equals(e)) return getString(R.string.eng_vlc);
        return getString(R.string.eng_auto);
    }

    private String ocrEngineName() {
        String m = Ocr.mode(this);
        if (Ocr.MLKIT.equals(m)) return getString(R.string.ocr_engine_mlkit);
        if (Ocr.TESS.equals(m)) return getString(R.string.ocr_engine_tess);
        if (Ocr.VISION.equals(m)) return getString(R.string.ocr_engine_vision);
        return getString(R.string.ocr_engine_auto);
    }

    private void render() {
        content.removeAllViews();

        TextView big = new TextView(this);
        big.setText(R.string.settings);
        big.setTextSize(34);
        big.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        big.setTextColor(Ui.color(this, R.color.text_primary));
        big.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        big.setPaddingRelative(Ui.dp(this, 32), Ui.dp(this, 2), Ui.dp(this, 32), Ui.dp(this, 10));
        content.addView(big);

        content.addView(appCard());

        // appearance: three live previews
        content.addView(Ui.sectionTitle(this, getString(R.string.theme_title)));
        content.addView(themePicker());
        content.addView(Ui.footer(this, getString(R.string.theme_mode_note)));

        // language: segmented control
        content.addView(Ui.sectionTitle(this, getString(R.string.set_language)));
        content.addView(languagePicker());

        // Live Text
        content.addView(Ui.sectionTitle(this, getString(R.string.set_gallery_sec)));
        boolean ready = TessEngine.ready(this);
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_live_text, false,
                getString(R.string.live_text), getString(ready ? R.string.set_live_ready : R.string.set_live_loading),
                false, false).tint(BLUE), null));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_text, false,
                getString(R.string.ocr_engine), ocrEngineName(), false, true).tint(INDIGO), v -> pickOcr()));
        content.addView(Ui.footer(this, getString(R.string.set_live_note)));

        // player
        content.addView(Ui.sectionTitle(this, getString(R.string.set_player)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_video, false,
                getString(R.string.set_engine), engineName(), false, true).tint(RED), v -> pickEngine()));
        toggle(R.string.set_hw, R.string.set_hw_sub, "hw", R.drawable.ic_speed, ORANGE);
        toggle(R.string.set_resume, R.string.set_resume_sub, "resume", R.drawable.ic_clock, TEAL);
        toggle(R.string.set_autonext, R.string.set_autonext_sub, "autonext", R.drawable.ic_skip_next, GREEN);
        toggle(R.string.set_autorotate, R.string.set_autorotate_sub, "autorotate", R.drawable.ic_rotate, PURPLE);
        toggle(R.string.set_pip, R.string.set_pip_sub, "pip", R.drawable.ic_pip, PINK);
        content.addView(Ui.footer(this, getString(R.string.set_engine_sub)));

        // access
        content.addView(Ui.sectionTitle(this, getString(R.string.set_access)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_shield, false,
                getString(R.string.set_media_access),
                getString(Perms.hasMedia(this) ? (Perms.isPartial(this) ? R.string.access_partial : R.string.access_granted)
                        : R.string.access_denied), false, true).tint(GREEN), v -> Perms.openAppSettings(this)));

        // about
        content.addView(Ui.sectionTitle(this, getString(R.string.set_about)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_info, false,
                getString(R.string.app_name), getString(R.string.set_version, BuildConfig.VERSION_NAME),
                false, false).tint(GRAY), null));
        content.addView(Ui.footer(this, getString(R.string.about_formats)));
    }

    private void toggle(int title, int sub, final String key, int icon, int color) {
        Ui.Toggle t = Ui.toggle(this, content, title, sub, Store.bool(this, key, true), icon, color);
        t.onChange((b, on) -> Store.setBool(this, key, on));
        content.addView(t.view);
    }

    // ------------------------------------------------------------------ blocks

    /** Rounded card: app icon, name, version and a chevron; opens "check for updates". */
    private View appCard() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPaddingRelative(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 20));
        g.setColor(Ui.color(this, R.color.surface));
        card.setBackground(g);
        Ui.press(this, card);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_brand_mark);
        GradientDrawable ig = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFFFFFFFF, 0xFFEAF3FF});
        ig.setCornerRadius(Ui.dp(this, 16));
        ig.setStroke(1, 0x14000000);
        icon.setBackground(ig);
        icon.setClipToOutline(true);
        card.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPaddingRelative(Ui.dp(this, 14), 0, Ui.dp(this, 8), 0);
        TextView n = new TextView(this);
        n.setText(R.string.app_name);
        n.setTextSize(19);
        n.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        n.setTextColor(Ui.color(this, R.color.text_primary));
        n.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        TextView v = new TextView(this);
        v.setText(getString(R.string.set_version, BuildConfig.VERSION_NAME));
        v.setTextSize(13.5f);
        v.setTextColor(Ui.color(this, R.color.text_secondary));
        v.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        TextView u = new TextView(this);
        u.setText(R.string.upd_check);
        u.setTextSize(13.5f);
        u.setTextColor(Ui.color(this, R.color.accent));
        u.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        texts.addView(n);
        texts.addView(v);
        texts.addView(u);
        card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron);
        chev.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.text_secondary)));
        card.addView(chev, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        card.setOnClickListener(x -> startActivity(new Intent(this, UpdateActivity.class)));
        return Ui.block(this, card);
    }

    /** Auto / Light / Dark as small previews of the screen; the chosen one gets an accent ring. */
    private View themePicker() {
        final String[] codes = {Appearance.AUTO, Appearance.LIGHT, Appearance.DARK};
        final int[] names = {R.string.theme_short_auto, R.string.theme_light, R.string.theme_short_dark};
        String cur = Store.themeMode(this);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (int i = 0; i < codes.length; i++) {
            final int k = i;
            boolean on = codes[i].equals(cur);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
            FrameLayout prev = new FrameLayout(this);
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(Ui.dp(this, 16));
            ring.setColor(0x00000000);
            ring.setStroke(Ui.dp(this, on ? 3 : 1), on ? Ui.color(this, R.color.accent) : Ui.color(this, R.color.stroke));
            LinearLayout halves = new LinearLayout(this);
            GradientDrawable clip = new GradientDrawable();
            clip.setCornerRadius(Ui.dp(this, 14));
            halves.setBackground(clip);
            halves.setClipToOutline(true);
            if (codes[i].equals(Appearance.AUTO)) {                 // half day, half night
                halves.addView(mini(0xFFF2F2F7, false), new LinearLayout.LayoutParams(0, -1, 1f));
                halves.addView(mini(0xFF000000, true), new LinearLayout.LayoutParams(0, -1, 1f));
            } else if (codes[i].equals(Appearance.LIGHT)) {
                halves.addView(mini(0xFFF2F2F7, false), new LinearLayout.LayoutParams(0, -1, 1f));
            } else {
                halves.addView(mini(0xFF000000, true), new LinearLayout.LayoutParams(0, -1, 1f));
            }
            prev.addView(halves, new FrameLayout.LayoutParams(-1, -1));
            prev.setForeground(ring);
            cell.addView(prev, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 112)));
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(13.5f);
            t.setTypeface(Typeface.create("sans-serif-medium", on ? Typeface.BOLD : Typeface.NORMAL));
            t.setTextColor(Ui.color(this, on ? R.color.accent : R.color.text_secondary));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
            tp.topMargin = Ui.dp(this, 8);
            cell.addView(t, tp);
            Ui.press(this, cell);
            cell.setOnClickListener(x -> {
                Store.setThemeMode(this, codes[k]);
                Appearance.applyMode(this);
                render();
            });
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return Ui.block(this, row);
    }

    /** A tiny "screen": bar at the top, a few tiles under it. */
    private View mini(int bg, boolean darkInk) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(bg);
        col.setPadding(Ui.dp(this, 7), Ui.dp(this, 9), Ui.dp(this, 7), Ui.dp(this, 7));
        View title = new View(this);
        GradientDrawable tg = new GradientDrawable();
        tg.setCornerRadius(Ui.dp(this, 3));
        tg.setColor(darkInk ? 0xFFFFFFFF : 0xFF1C1C1E);
        title.setBackground(tg);
        col.addView(title, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 5)));
        int[] cols = {0xFF4F8CFF, 0xFFFFB340, 0xFF34C759, 0xFFFF6B6B};
        for (int i = 0; i < 2; i++) {
            LinearLayout line = new LinearLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
            lp.topMargin = Ui.dp(this, 6);
            for (int j = 0; j < 2; j++) {
                View tile = new View(this);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(Ui.dp(this, 4));
                g.setColor(cols[i * 2 + j]);
                tile.setBackground(g);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -1, 1f);
                tl.setMargins(j == 0 ? 0 : Ui.dp(this, 3), 0, j == 0 ? Ui.dp(this, 3) : 0, 0);
                line.addView(tile, tl);
            }
            col.addView(line, lp);
        }
        return col;
    }

    /** System / Arabic / English as an iOS segmented control. */
    private View languagePicker() {
        final String[] codes = {"system", "ar", "en"};
        final String[] names = {getString(R.string.lang_system), getString(R.string.lang_ar), getString(R.string.lang_en)};
        String cur = Store.language(this);
        LinearLayout seg = new LinearLayout(this);
        seg.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        seg.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        GradientDrawable sb = new GradientDrawable();
        sb.setCornerRadius(Ui.dp(this, 14));
        sb.setColor(Ui.color(this, R.color.neutral_soft));
        seg.setBackground(sb);
        for (int i = 0; i < codes.length; i++) {
            final int k = i;
            boolean on = codes[i].equals(cur);
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(15);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setTypeface(Typeface.create("sans-serif-medium", on ? Typeface.BOLD : Typeface.NORMAL));
            t.setTextColor(Ui.color(this, on ? R.color.text_primary : R.color.text_secondary));
            if (on) {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(Ui.dp(this, 11));
                g.setColor(Ui.color(this, R.color.surface));
                g.setStroke(Math.max(1, Ui.dp(this, 1) / 2), Ui.color(this, R.color.stroke_soft));
                t.setBackground(g);
                t.setElevation(Ui.dp(this, 1));
            }
            t.setOnClickListener(x -> {
                if (codes[k].equals(Store.language(this))) return;
                Store.setLanguage(this, codes[k]);
                Lang.apply(this);
            });
            seg.addView(t, new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1f));
        }
        return Ui.block(this, seg);
    }

    private void pickOcr() {
        final String[] ids = {Ocr.AUTO, Ocr.MLKIT, Ocr.TESS};
        final int[] names = {R.string.ocr_engine_auto, R.string.ocr_engine_mlkit, R.string.ocr_engine_tess};
        String cur = Ocr.mode(this);
        String[] items = new String[ids.length];
        for (int i = 0; i < ids.length; i++) items[i] = (ids[i].equals(cur) ? "● " : "○ ") + getString(names[i]);
        new Dlg(this).setTitle(R.string.ocr_engine).setItems(items, (d, w) -> {
            Ocr.setMode(this, ids[w]);
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
