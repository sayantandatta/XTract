package com.xtract.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A collected X thread: the focal post plus everything around it, in page order. */
final class ThreadData {

    private static final Pattern STATUS_URL =
            Pattern.compile("https?://(?:www\\.|mobile\\.)?(?:x|twitter)\\.com/([^/?#]+)/status/(\\d+)");

    static final class Post {
        String url, name, handle, time, text, quote, stats;
        int media;
        boolean focal;

        String header() {
            StringBuilder sb = new StringBuilder();
            sb.append(name.isEmpty() ? handle : name);
            if (!handle.isEmpty() && !name.isEmpty()) sb.append(' ').append(handle);
            String when = displayTime(time);
            if (!when.isEmpty()) sb.append("  ·  ").append(when);
            return sb.toString();
        }
    }

    final String sourceUrl;
    final String statusId;
    final List<Post> posts = new ArrayList<>();
    final long collectedAt = System.currentTimeMillis();

    private ThreadData(String sourceUrl) {
        this.sourceUrl = sourceUrl;
        this.statusId = statusId(sourceUrl);
    }

    static ThreadData fromJson(String sourceUrl, String json) throws JSONException {
        ThreadData t = new ThreadData(sourceUrl);
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Post p = new Post();
            p.url = o.optString("url");
            p.name = o.optString("name");
            p.handle = o.optString("handle");
            p.time = o.optString("time");
            p.text = o.optString("text");
            p.quote = o.optString("quote");
            p.stats = o.optString("stats");
            p.media = o.optInt("media");
            p.focal = t.statusId != null && t.statusId.equals(statusId(p.url));
            t.posts.add(p);
        }
        return t;
    }

    /** Returns the numeric status id of an x.com/twitter.com post URL, or null. */
    static String statusId(String url) {
        if (url == null) return null;
        Matcher m = STATUS_URL.matcher(url);
        return m.find() ? m.group(2) : null;
    }

    static boolean isThreadUrl(String url) {
        return statusId(url) != null;
    }

    /** Extracts the first x.com/twitter.com post link from arbitrary shared text. */
    static String findThreadUrl(String text) {
        if (text == null) return null;
        Matcher m = STATUS_URL.matcher(text);
        return m.find() ? "https://x.com/" + m.group(1) + "/status/" + m.group(2) : null;
    }

    Post focal() {
        for (Post p : posts) if (p.focal) return p;
        return posts.isEmpty() ? null : posts.get(0);
    }

    int focalIndex() {
        Post f = focal();
        return f == null ? -1 : posts.indexOf(f);
    }

    /** Number of posts after the focal post, i.e. the replies/comments. */
    int replyCount() {
        int i = focalIndex();
        return i < 0 ? 0 : posts.size() - i - 1;
    }

    String title() {
        Post f = focal();
        if (f == null) return "X thread";
        return "Thread by " + (f.name.isEmpty() ? f.handle : f.name + " (" + f.handle + ")");
    }

    String fileStem() {
        Post f = focal();
        String who = f == null ? "thread" : f.handle.replace("@", "");
        return "XTract-" + who.replaceAll("[^A-Za-z0-9_]", "") + "-" + (statusId == null ? "post" : statusId);
    }

    /** Plain-text rendering used for the Claude prompt and the "send to Claude app" attachment. */
    String toPlainText() {
        StringBuilder sb = new StringBuilder();
        sb.append(title()).append('\n').append(sourceUrl).append("\n\n");
        int fi = focalIndex();
        for (int i = 0; i < posts.size(); i++) {
            Post p = posts.get(i);
            if (i == fi) sb.append("=== ORIGINAL POST ===\n");
            else if (i == fi + 1) sb.append("=== REPLIES (").append(replyCount()).append(") ===\n\n");
            else if (i < fi && i == 0) sb.append("=== EARLIER IN THREAD ===\n");
            sb.append('[').append(i + 1).append("] ").append(p.header()).append('\n');
            if (!p.text.isEmpty()) sb.append(p.text).append('\n');
            if (!p.quote.isEmpty()) sb.append("  > Quoting: ").append(p.quote.replace("\n", "\n  > ")).append('\n');
            if (p.media > 0) sb.append("  [").append(p.media).append(" media attachment(s)]\n");
            if (!p.stats.isEmpty()) sb.append("  (").append(p.stats).append(")\n");
            sb.append('\n');
        }
        return sb.toString();
    }

    static String displayTime(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = in.parse(iso.length() > 19 ? iso.substring(0, 19) : iso);
            return new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(d);
        } catch (Exception e) {
            return iso;
        }
    }
}
