package com.gallery.app;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * The ONLY place in the app that opens a network connection (update check, OCR language data, optional cloud OCR).
 * Everything is HTTPS, limited to GitHub's own hosts, redirects are followed by hand and every hop
 * is checked again. Device files leave the phone only through the optional cloud OCR engine, after the user enabled it.
 */
final class Net {
    private Net() {
    }

    private static final String[] HOSTS = {
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com",
            "raw.githubusercontent.com",
            "vision.googleapis.com"
    };
    private static final int MAX_REDIRECTS = 5;

    static boolean allowed(URL u) {
        if (u == null || !"https".equals(u.getProtocol())) return false;
        if (u.getUserInfo() != null) return false;
        int port = u.getPort();
        if (port != -1 && port != 443) return false;
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.US);
        for (String h : HOSTS) if (h.equals(host)) return true;
        return false;
    }

    static boolean allowed(String url) {
        try {
            return allowed(new URL(url));
        } catch (Exception e) {
            return false;
        }
    }

    static void download(String url, java.io.File dest, long max) throws IOException {
        HttpURLConnection h = get(url, "application/octet-stream");
        try {
            if (h.getResponseCode() != 200) throw new IOException("http");
            try (java.io.InputStream in = h.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                byte[] buf = new byte[32768];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > max) throw new IOException("size");
                }
            }
        } finally {
            h.disconnect();
        }
    }

    static String post(String url, String json, String keyHeader, String key) throws IOException {
        URL u = new URL(url);
        if (!allowed(u)) throw new IOException("blocked host");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty(keyHeader, key);
            byte[] data = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(data.length);
            try (java.io.OutputStream o = c.getOutputStream()) {
                o.write(data);
            }
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            if (in != null) {
                try (java.io.InputStream i = in) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = i.read(buf)) > 0) {
                        bo.write(buf, 0, n);
                        if (bo.size() > (16 << 20)) throw new IOException("too large");
                    }
                }
            }
            if (code >= 400) throw new IOException("HTTP " + code);
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Opens a GET connection whose response has already been received (final, non-redirect hop). */
    static HttpURLConnection get(String url, String accept) throws IOException {
        String cur = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL u = new URL(cur);
            if (!allowed(u)) throw new IOException("blocked host");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", accept);
            c.setRequestProperty("User-Agent", "Gallery-Updater");
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("bad redirect");
                cur = new URL(u, loc).toString();
                continue;
            }
            return c;
        }
        throw new IOException("too many redirects");
    }
}
