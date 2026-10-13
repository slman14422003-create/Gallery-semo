package com.gallery.app;

import android.graphics.RectF;

import java.util.ArrayList;
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
    private List<OcrEntity> entities;

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    void add(String text, float l, float t, float r, float b, int iw, int ih, int line) {
        if (text == null || iw <= 0 || ih <= 0) return;
        String s = text.trim();
        if (s.isEmpty()) return;
        words.add(new Word(s, new RectF(clamp(l / iw), clamp(t / ih), clamp(r / iw), clamp(b / ih)), line));
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
