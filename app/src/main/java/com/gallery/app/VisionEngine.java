package com.gallery.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class VisionEngine implements OcrEngine {
    static final String URL = "https://vision.googleapis.com/v1/images:annotate";

    static String key(Context c) {
        return Store.str(c, "vision_key", "").trim();
    }

    @Override
    public OcrResult run(Context c, Bitmap b) throws Exception {
        String key = key(c);
        if (key.isEmpty()) throw new IOException("no key");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 88, bo);
        JSONObject req = new JSONObject();
        req.put("image", new JSONObject().put("content", Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)));
        req.put("features", new JSONArray().put(new JSONObject().put("type", "DOCUMENT_TEXT_DETECTION")));
        req.put("imageContext", new JSONObject().put("languageHints",
                new JSONArray().put("ar").put("en").put("en-t-i0-handwrit")));
        String body = new JSONObject().put("requests", new JSONArray().put(req)).toString();
        String out = Net.post(URL, body, "X-Goog-Api-Key", key);

        OcrResult res = new OcrResult();
        JSONObject r0 = new JSONObject(out).getJSONArray("responses").getJSONObject(0);
        if (r0.has("error")) throw new IOException("vision");
        JSONObject full = r0.optJSONObject("fullTextAnnotation");
        if (full == null) return res;
        int line = 0;
        JSONArray pages = full.optJSONArray("pages");
        for (int p = 0; pages != null && p < pages.length(); p++) {
            JSONArray blocks = pages.getJSONObject(p).optJSONArray("blocks");
            for (int bi = 0; blocks != null && bi < blocks.length(); bi++) {
                JSONArray paras = blocks.getJSONObject(bi).optJSONArray("paragraphs");
                for (int pi = 0; paras != null && pi < paras.length(); pi++) {
                    JSONArray words = paras.getJSONObject(pi).optJSONArray("words");
                    for (int wi = 0; words != null && wi < words.length(); wi++) {
                        JSONObject w = words.getJSONObject(wi);
                        JSONArray syms = w.optJSONArray("symbols");
                        if (syms == null) continue;
                        StringBuilder sb = new StringBuilder();
                        boolean eol = false;
                        for (int si = 0; si < syms.length(); si++) {
                            JSONObject s = syms.getJSONObject(si);
                            sb.append(s.optString("text"));
                            JSONObject prop = s.optJSONObject("property");
                            JSONObject br = prop == null ? null : prop.optJSONObject("detectedBreak");
                            if (br != null) {
                                String t = br.optString("type");
                                if ("LINE_BREAK".equals(t) || "EOL_SURE_SPACE".equals(t)) eol = true;
                            }
                        }
                        JSONObject box = w.optJSONObject("boundingBox");
                        JSONArray vs = box == null ? null : box.optJSONArray("vertices");
                        if (vs != null && vs.length() > 0) {
                            float l = Float.MAX_VALUE, t = Float.MAX_VALUE, rr = 0, bb = 0;
                            for (int vi = 0; vi < vs.length(); vi++) {
                                JSONObject v = vs.getJSONObject(vi);
                                float x = (float) v.optDouble("x", 0);
                                float y = (float) v.optDouble("y", 0);
                                l = Math.min(l, x);
                                t = Math.min(t, y);
                                rr = Math.max(rr, x);
                                bb = Math.max(bb, y);
                            }
                            res.add(sb.toString(), l, t, rr, bb, b.getWidth(), b.getHeight(), line);
                        }
                        if (eol) line++;
                    }
                }
            }
        }
        return res;
    }
}
