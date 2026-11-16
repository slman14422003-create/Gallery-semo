package com.gallery.app;

import android.util.Patterns;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Entities {
    private Entities() {
    }

    private static final Pattern DATE_LIKE = Pattern.compile("^\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}$");

    static String norm(String s) {
        char[] a = s.toCharArray();
        for (int i = 0; i < a.length; i++) {
            char c = a[i];
            if (c >= '٠' && c <= '٩') a[i] = (char) ('0' + (c - '٠'));
            else if (c >= '۰' && c <= '۹') a[i] = (char) ('0' + (c - '۰'));
        }
        return new String(a);
    }

    static List<OcrEntity> find(OcrResult r) {
        List<OcrEntity> out = new ArrayList<>();
        int n = r.words.size();
        if (n == 0) return out;
        StringBuilder sb = new StringBuilder();
        int[] st = new int[n];
        int[] en = new int[n];
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(r.words.get(i).line != r.words.get(i - 1).line ? '\n' : ' ');
            st[i] = sb.length();
            sb.append(r.words.get(i).text);
            en[i] = sb.length();
        }
        String s = norm(sb.toString());
        List<int[]> taken = new ArrayList<>();

        Matcher m = Patterns.EMAIL_ADDRESS.matcher(s);
        while (m.find()) add(out, taken, OcrEntity.EMAIL, m.start(), m.end(), s, st, en);

        m = Patterns.WEB_URL.matcher(s);
        while (m.find()) {
            String v = m.group();
            boolean web = v.toLowerCase(java.util.Locale.ROOT).startsWith("http") || v.toLowerCase(java.util.Locale.ROOT).startsWith("www.");
            boolean letters = false;
            for (int i = 0; i < v.length(); i++) if (Character.isLetter(v.charAt(i))) letters = true;
            if (!web && !letters) continue;
            if (overlaps(taken, m.start(), m.end())) continue;
            add(out, taken, OcrEntity.URL, m.start(), m.end(), s, st, en);
        }

        m = Patterns.PHONE.matcher(s);
        while (m.find()) {
            String v = m.group().trim();
            int digits = 0;
            for (int i = 0; i < v.length(); i++) if (Character.isDigit(v.charAt(i))) digits++;
            if (digits < 7 || digits > 15) continue;
            if (DATE_LIKE.matcher(v).matches()) continue;
            int a = m.start() + (m.group().length() - m.group().replaceFirst("^\\s+", "").length());
            int b = a + v.length();
            if (overlaps(taken, a, b)) continue;
            add(out, taken, OcrEntity.PHONE, a, b, s, st, en);
        }
        return out;
    }

    private static boolean overlaps(List<int[]> taken, int a, int b) {
        for (int[] t : taken) if (a < t[1] && b > t[0]) return true;
        return false;
    }

    private static void add(List<OcrEntity> out, List<int[]> taken, int type, int a, int b, String s, int[] st, int[] en) {
        int from = -1;
        int to = -1;
        for (int i = 0; i < st.length; i++) {
            if (en[i] > a && st[i] < b) {
                if (from < 0) from = i;
                to = i;
            }
        }
        if (from < 0) return;
        taken.add(new int[]{a, b});
        String v = s.substring(a, b).trim();
        if (type == OcrEntity.PHONE) v = v.replaceAll("[^+0-9]", "");
        out.add(new OcrEntity(type, from, to, v));
    }
}
