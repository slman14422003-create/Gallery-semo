package com.gallery.app;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class OcrResult {
    static final class Word {
        final String text;
        final RectF box;
        final int line;

        Word(String text, RectF box, int line) {
            this.text = text;
            this.box = box;
            this.line = line;
        }
    }

    final List<Word> words = new ArrayList<>();
    float aspect = 1f;
    private List<OcrEntity> entities;

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    void add(String text, float l, float t, float r, float b, int iw, int ih, int line) {
        if (text == null || iw <= 0 || ih <= 0) return;
        aspect = iw / (float) ih;
        String s = text.trim();
        if (s.isEmpty()) return;
        words.add(new Word(s, new RectF(clamp(l / iw), clamp(t / ih), clamp(r / iw), clamp(b / ih)), line));
    }

    static boolean isArabicChar(char c) {
        return (c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F) || (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF);
    }

    private static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); i++) if (isArabicChar(s.charAt(i))) return true;
        return false;
    }

    private static float overlap(RectF a, RectF b) {
        float w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        float h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        if (w <= 0f || h <= 0f) return 0f;
        float small = Math.min(a.width() * a.height(), b.width() * b.height());
        return small <= 0f ? 0f : w * h / small;
    }

    /** Joins the words of several passes or engines; a word already found wins unless a later Arabic reading replaces non-Arabic guesses. */
    static OcrResult merge(List<OcrResult> parts) {
        List<Word> kept = new ArrayList<>();
        float aspect = 0f;
        for (OcrResult p : parts) {
            if (aspect == 0f && !p.words.isEmpty()) aspect = p.aspect;
            for (Word w : p.words) {
                List<Integer> hits = new ArrayList<>();
                for (int i = 0; i < kept.size(); i++) if (overlap(kept.get(i).box, w.box) > 0.5f) hits.add(i);
                if (hits.isEmpty()) {
                    kept.add(w);
                    continue;
                }
                if (!hasArabic(w.text)) continue;
                boolean replace = true;
                for (int i : hits) {
                    if (hasArabic(kept.get(i).text)) {
                        replace = false;
                        break;
                    }
                }
                if (!replace) continue;
                for (int k = hits.size() - 1; k >= 0; k--) kept.remove(hits.get(k).intValue());
                kept.add(w);
            }
        }
        return arrange(kept, aspect == 0f ? 1f : aspect);
    }

    private static OcrResult arrange(List<Word> all, float aspect) {
        OcrResult out = new OcrResult();
        out.aspect = aspect;
        if (all.isEmpty()) return out;
        List<Word> sorted = new ArrayList<>(all);
        Collections.sort(sorted, (a, b) -> Float.compare(a.box.centerY(), b.box.centerY()));
        List<List<Word>> lines = new ArrayList<>();
        List<RectF> bands = new ArrayList<>();
        for (Word w : sorted) {
            int best = -1;
            for (int i = lines.size() - 1; i >= 0 && i >= lines.size() - 40; i--) {
                RectF b = bands.get(i);
                float shared = Math.min(b.bottom, w.box.bottom) - Math.max(b.top, w.box.top);
                float h = Math.min(b.height(), w.box.height());
                if (h <= 0f || shared / h < 0.5f) continue;
                float gap = Math.max(0f, Math.max(w.box.left - b.right, b.left - w.box.right)) * aspect;
                if (gap > 2.2f * Math.max(b.height(), w.box.height())) continue;
                best = i;
                break;
            }
            if (best < 0) {
                List<Word> line = new ArrayList<>();
                line.add(w);
                lines.add(line);
                bands.add(new RectF(w.box));
            } else {
                lines.get(best).add(w);
                bands.get(best).union(w.box);
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) order.add(i);
        Collections.sort(order, (a, b) -> Float.compare(bands.get(a).centerY(), bands.get(b).centerY()));
        int no = 0;
        for (int idx : order) {
            List<Word> line = lines.get(idx);
            int ar = 0, la = 0;
            for (Word w : line) {
                for (int i = 0; i < w.text.length(); i++) {
                    char c = w.text.charAt(i);
                    if (isArabicChar(c)) ar++;
                    else if (Character.isLetter(c)) la++;
                }
            }
            final boolean rtl = ar > la;
            Collections.sort(line, (a, b) -> rtl ? Float.compare(b.box.centerX(), a.box.centerX()) : Float.compare(a.box.centerX(), b.box.centerX()));
            if (rtl) {
                int i = 0;
                while (i < line.size()) {
                    if (hasArabic(line.get(i).text)) {
                        i++;
                        continue;
                    }
                    int j = i;
                    while (j + 1 < line.size() && !hasArabic(line.get(j + 1).text)) j++;
                    Collections.reverse(line.subList(i, j + 1));
                    i = j + 1;
                }
            }
            for (Word w : line) out.words.add(new Word(w.text, w.box, no));
            no++;
        }
        return out;
    }

    String text(int from, int to) {
        StringBuilder sb = new StringBuilder();
        int a = Math.max(0, Math.min(from, to));
        int z = Math.min(words.size() - 1, Math.max(from, to));
        for (int i = a; i <= z; i++) {
            if (i > a) sb.append(words.get(i).line != words.get(i - 1).line ? '\n' : ' ');
            sb.append(words.get(i).text);
        }
        return sb.toString();
    }

    String text() {
        return text(0, words.size() - 1);
    }

    List<OcrEntity> entities() {
        if (entities == null) entities = Entities.find(this);
        return entities;
    }

    OcrEntity entityAt(int word) {
        for (OcrEntity e : entities()) if (word >= e.from && word <= e.to) return e;
        return null;
    }

    List<OcrEntity> entitiesIn(int from, int to) {
        List<OcrEntity> out = new ArrayList<>();
        for (OcrEntity e : entities()) if (e.to >= from && e.from <= to) out.add(e);
        return out;
    }
}
