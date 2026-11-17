package com.gallery.app;

import android.content.Context;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/** Row of round tool dials plus one ruler for the selected tool, the way the iOS Photos "Adjust" tab works. */
final class AdjustPanel extends LinearLayout {
    interface Get {
        float get(EditState s);
    }

    interface Put {
        void put(EditState s, float v);
    }

    interface Host {
        /** The value is about to change: take an undo step. */
        void onBegin();

        /** The state changed: refresh the preview. */
        void onChange();
    }

    static final class Tool {
        final int name;
        final boolean bipolar;
        final Get get;
        final Put put;

        Tool(int name, boolean bipolar, Get get, Put put) {
            this.name = name;
            this.bipolar = bipolar;
            this.get = get;
            this.put = put;
        }
    }

    static Tool[] imageTools() {
        return new Tool[]{
                new Tool(R.string.ad_exposure, true, s -> s.exposure, (s, v) -> s.exposure = v),
                new Tool(R.string.ad_brightness, true, s -> s.brightness, (s, v) -> s.brightness = v),
                new Tool(R.string.ad_contrast, true, s -> s.contrast, (s, v) -> s.contrast = v),
                new Tool(R.string.ad_saturation, true, s -> s.saturation, (s, v) -> s.saturation = v),
                new Tool(R.string.ad_warmth, true, s -> s.warmth, (s, v) -> s.warmth = v),
                new Tool(R.string.ad_tint, true, s -> s.tint, (s, v) -> s.tint = v),
                new Tool(R.string.ad_fade, false, s -> s.fade, (s, v) -> s.fade = v),
                new Tool(R.string.ad_sharpen, false, s -> s.sharpen, (s, v) -> s.sharpen = v),
                new Tool(R.string.ad_denoise, false, s -> s.denoise, (s, v) -> s.denoise = v),
                new Tool(R.string.ad_dehaze, false, s -> s.dehaze, (s, v) -> s.dehaze = v),
                new Tool(R.string.ad_clarity, false, s -> s.clarity, (s, v) -> s.clarity = v),
                new Tool(R.string.ad_shadows, false, s -> s.shadows, (s, v) -> s.shadows = v),
                new Tool(R.string.ad_grain, false, s -> s.grain, (s, v) -> s.grain = v),
                new Tool(R.string.ad_vignette, false, s -> s.vignette, (s, v) -> s.vignette = v)
        };
    }

    static Tool[] videoTools() {
        return new Tool[]{
                new Tool(R.string.ad_exposure, true, s -> s.exposure, (s, v) -> s.exposure = v),
                new Tool(R.string.ad_brightness, true, s -> s.brightness, (s, v) -> s.brightness = v),
                new Tool(R.string.ad_contrast, true, s -> s.contrast, (s, v) -> s.contrast = v),
                new Tool(R.string.ad_saturation, true, s -> s.saturation, (s, v) -> s.saturation = v),
                new Tool(R.string.ad_warmth, true, s -> s.warmth, (s, v) -> s.warmth = v),
                new Tool(R.string.ad_tint, true, s -> s.tint, (s, v) -> s.tint = v),
                new Tool(R.string.ad_fade, false, s -> s.fade, (s, v) -> s.fade = v)
        };
    }

    private final EditState st;
    private final Tool[] tools;
    private final Host host;
    private final LinearLayout list;
    private final List<DialButton> dials = new ArrayList<>();
    private final RulerView ruler;
    private int actions = 0;
    private int sel;

    AdjustPanel(Context c, EditState st, Tool[] tools, Host host, int selected) {
        super(c);
        this.st = st;
        this.tools = tools;
        this.host = host;
        setOrientation(VERTICAL);
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list = new LinearLayout(c);
        list.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0);
        hs.addView(list);
        addView(hs, new LayoutParams(-1, -2));
        for (int i = 0; i < tools.length; i++) {
            final int k = i;
            DialButton d = new DialButton(c, c.getString(tools[i].name));
            d.setOnClickListener(v -> onTap(k));
            dials.add(d);
            list.addView(d);
        }
        ruler = new RulerView(c);
        ruler.setFormat(v -> text(tools[sel], v));
        ruler.setListener(new RulerView.Listener() {
            @Override
            public void onStart() {
                host.onBegin();
            }

            @Override
            public void onValue(float v) {
                Tool t = tools[sel];
                t.put.put(st, v);
                paint(sel);
                host.onChange();
            }

            @Override
            public void onEnd() {
            }
        });
        LayoutParams rp = new LayoutParams(-1, -2);
        rp.topMargin = Ui.dp(c, 2);
        addView(ruler, rp);
        sel = Math.max(0, Math.min(tools.length - 1, selected));
        select(sel);
        for (int i = 0; i < tools.length; i++) paint(i);
    }

    /** Adds a one-shot action (auto enhance, reset) in front of the tools. */
    void addAction(int icon, CharSequence name, final Runnable run) {
        DialButton d = new DialButton(getContext(), name);
        d.setIcon(icon);
        d.setOnClickListener(v -> run.run());
        list.addView(d, actions++);
    }

    int selected() {
        return sel;
    }

    private static String text(Tool t, float v) {
        int n = Math.round(v * 100f);
        return (t.bipolar && n > 0 ? "+" : "") + n;
    }

    private void paint(int i) {
        Tool t = tools[i];
        float v = t.get.get(st);
        dials.get(i).setState(v, text(t, v), t.bipolar);
    }

    private void onTap(int i) {
        if (i == sel) {
            Tool t = tools[i];
            if (Math.abs(t.get.get(st)) < 1e-4f) return;
            host.onBegin();
            t.put.put(st, 0f);
            paint(i);
            ruler.setValue(0f);
            host.onChange();
            return;
        }
        select(i);
    }

    private void select(int i) {
        sel = i;
        Tool t = tools[i];
        for (int k = 0; k < dials.size(); k++) dials.get(k).setActive(k == i);
        ruler.configure(t.bipolar ? -1f : 0f, 1f, 0f, t.bipolar ? 100 : 50, 10);
        ruler.setValue(t.get.get(st));
    }

    /** Re-reads every value from the state (after undo, redo, auto enhance or reset). */
    void refresh() {
        for (int i = 0; i < tools.length; i++) paint(i);
        ruler.setValue(tools[sel].get.get(st));
    }
}
