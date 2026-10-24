package com.xtract.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Drives assets/scraper.js inside the WebView: it auto-scrolls the open thread,
 * expands hidden replies and reports every post it sees back through this bridge.
 */
final class ThreadScraper {

    interface Listener {
        /** {@code fraction} is how far down the page the collector has scrolled, 0..1. */
        void onScrapeProgress(int posts, int expanded, double fraction);

        void onScrapeDone(String sourceUrl, String json);

        void onScrapeError(String message);
    }

    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final String script;
    private volatile int activeToken;
    private int nextToken = 1;
    private String sourceUrl;

    ThreadScraper(Context context, Listener listener) {
        this.listener = listener;
        this.script = readAsset(context, "scraper.js");
    }

    void attach(WebView web) {
        web.addJavascriptInterface(this, "XTractBridge");
    }

    boolean isRunning() {
        return activeToken != 0;
    }

    void start(WebView web) {
        sourceUrl = web.getUrl();
        activeToken = nextToken++;
        web.evaluateJavascript(script.replace("__TOKEN__", String.valueOf(activeToken)), null);
    }

    /** Asks the script to stop; it still reports what it collected so far. */
    void stop(WebView web) {
        web.evaluateJavascript("window.__xtractStop = true;", null);
    }

    /** Forgets the current run, e.g. because the page navigated away and the script died with it. */
    void cancel() {
        activeToken = 0;
    }

    // Bridge methods run on a WebView binder thread. Calls carrying a stale token are ignored,
    // so a page cannot feed data into a run it did not start.

    @JavascriptInterface
    public void progress(final int token, final int posts, final int expanded, final double fraction) {
        if (token != activeToken) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (token == activeToken) listener.onScrapeProgress(posts, expanded, fraction);
            }
        });
    }

    @JavascriptInterface
    public void done(final int token, final String json) {
        if (token != activeToken) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (token != activeToken) return;
                activeToken = 0;
                listener.onScrapeDone(sourceUrl, json);
            }
        });
    }

    @JavascriptInterface
    public void error(final int token, final String message) {
        if (token != activeToken) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (token != activeToken) return;
                activeToken = 0;
                listener.onScrapeError(message);
            }
        });
    }

    private static String readAsset(Context context, String name) {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Missing asset " + name, e);
        }
    }
}
