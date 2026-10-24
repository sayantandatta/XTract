package com.xtract.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements ThreadScraper.Listener {

    private static final String CLAUDE_APP = "com.anthropic.claude";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Settings settings;
    private ThreadScraper scraper;

    private WebView web;
    private EditText urlBar;
    private ProgressBar pageProgress;
    private TextView status;
    private Button collectBtn, pdfBtn, analyzeBtn;

    private ThreadData thread;          // last collected thread
    private Runnable afterCollect;      // action waiting for a collection to finish
    private String scrapeStatusId;      // post being collected
    private boolean busy;               // exporting or analysing
    private String summary, summaryModel;
    private String summaryForStatusId;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this);
        scraper = new ThreadScraper(this, this);
        setContentView(buildUi());
        setupWebView();

        String shared = sharedThreadUrl(getIntent());
        if (state != null) web.restoreState(state);
        if (shared != null) web.loadUrl(shared);
        else if (state == null) web.loadUrl(settings.lastUrl());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String shared = sharedThreadUrl(intent);
        if (shared != null) web.loadUrl(shared);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush(); // keep the X login across restarts
        if (web.getUrl() != null) settings.setLastUrl(web.getUrl());
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (scraper.isRunning()) scraper.stop(web);
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Color.rgb(15, 20, 25));
        top.setPadding(dp(4), dp(4), dp(4), dp(4));

        top.addView(iconButton(android.R.drawable.ic_media_previous, "Back", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (web.canGoBack()) web.goBack();
            }
        }));
        top.addView(iconButton(android.R.drawable.ic_menu_myplaces, "X home", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                web.loadUrl("https://x.com/home");
            }
        }));

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        urlBar.setTextColor(Color.WHITE);
        urlBar.setHintTextColor(Color.rgb(139, 152, 165));
        urlBar.setHint("Paste an x.com post link");
        urlBar.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setBackgroundColor(Color.rgb(39, 51, 64));
        urlBar.setPadding(dp(10), dp(6), dp(10), dp(6));
        urlBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                navigate(urlBar.getText().toString());
                return true;
            }
        });
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        ulp.setMargins(dp(4), 0, dp(4), 0);
        top.addView(urlBar, ulp);

        top.addView(iconButton(android.R.drawable.ic_popup_sync, "Reload", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                web.reload();
            }
        }));
        root.addView(top);

        pageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgress.setMax(100);
        root.addView(pageProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(Color.rgb(83, 100, 113));
        status.setPadding(dp(12), dp(6), dp(12), dp(2));
        status.setText("Log in to X, open a post, then tap Load all.");
        root.addView(status);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(6), 0, dp(6), dp(6));
        collectBtn = actionButton("Load all", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (scraper.isRunning()) scraper.stop(web);
                else collect(null);
            }
        });
        pdfBtn = actionButton("PDF", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                withThread(new Runnable() {
                    @Override
                    public void run() {
                        exportThreadPdf();
                    }
                });
            }
        });
        analyzeBtn = actionButton("Analyze", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAnalyzeMenu();
            }
        });
        bar.addView(collectBtn, weighted());
        bar.addView(pdfBtn, weighted());
        bar.addView(analyzeBtn, weighted());
        ImageButton gear = iconButton(android.R.drawable.ic_menu_manage, "Settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings(null);
            }
        });
        gear.setColorFilter(Color.rgb(83, 100, 113));
        bar.addView(gear);
        root.addView(bar);
        return root;
    }

    private Button actionButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setOnClickListener(l);
        return b;
    }

    private ImageButton iconButton(int icon, String description, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setContentDescription(description);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void refreshButtons() {
        boolean collecting = scraper.isRunning();
        collectBtn.setText(collecting ? "Stop" : "Load all");
        collectBtn.setEnabled(!busy);
        pdfBtn.setEnabled(!collecting && !busy);
        analyzeBtn.setEnabled(!collecting && !busy);
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- WebView

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportMultipleWindows(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // Some sites refuse embedded WebViews; present as the regular mobile Chrome build.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        scraper.attach(web);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                try {
                    startActivity(Intent.parseUri(request.getUrl().toString(), Intent.URI_INTENT_SCHEME));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                onUrlChanged(url, true);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                onUrlChanged(url, false); // X is a single-page app; most navigations only change history
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                pageProgress.setProgress(p);
                pageProgress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
    }

    /** A full page load kills the injected script; a history change to another post makes its results wrong. */
    private void onUrlChanged(String url, boolean fullLoad) {
        if (!urlBar.hasFocus()) urlBar.setText(url);
        if (scraper.isRunning() && (fullLoad || !scrapeStatusId.equals(ThreadData.statusId(url)))) {
            scraper.cancel();
            afterCollect = null;
            setStatus("Collection cancelled: the page changed.");
            refreshButtons();
        }
    }

    private void navigate(String input) {
        String s = input.trim();
        if (s.isEmpty()) return;
        String link = ThreadData.findThreadUrl(s);
        if (link != null) s = link;
        else if (!s.startsWith("http")) s = "https://" + s;
        web.loadUrl(s);
        urlBar.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        web.requestFocus();
    }

    private static String sharedThreadUrl(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return null;
        return ThreadData.findThreadUrl(intent.getStringExtra(Intent.EXTRA_TEXT));
    }

    // ---------------------------------------------------------------- Collection

    /** Runs {@code action} with a thread collected from the current page, collecting first if needed. */
    private void withThread(Runnable action) {
        String id = ThreadData.statusId(web.getUrl());
        if (id == null) {
            toast("Open a post first (x.com/<user>/status/<id>).");
            return;
        }
        if (thread != null && id.equals(thread.statusId) && !thread.posts.isEmpty()) action.run();
        else collect(action);
    }

    private void collect(Runnable then) {
        if (!ThreadData.isThreadUrl(web.getUrl())) {
            toast("Open a post first (x.com/<user>/status/<id>).");
            return;
        }
        afterCollect = then;
        scrapeStatusId = ThreadData.statusId(web.getUrl());
        scraper.start(web);
        setStatus("Scrolling the thread…");
        refreshButtons();
    }

    @Override
    public void onScrapeProgress(int posts, int expanded) {
        setStatus("Scrolling… " + posts + " posts collected"
                + (expanded > 0 ? ", " + expanded + " hidden sections opened" : "") + ". Tap Stop to finish early.");
    }

    @Override
    public void onScrapeDone(String sourceUrl, String json) {
        try {
            ThreadData t = ThreadData.fromJson(sourceUrl, json);
            if (t.posts.isEmpty()) {
                afterCollect = null;
                setStatus("No posts found. Are you logged in, and is the post fully loaded?");
            } else {
                thread = t;
                setStatus(t.posts.size() + " posts collected (" + t.replyCount() + " replies). Ready for PDF or Analyze.");
                Runnable next = afterCollect;
                afterCollect = null;
                if (next != null) next.run();
            }
        } catch (JSONException e) {
            afterCollect = null;
            setStatus("Could not read the collected posts: " + e.getMessage());
        }
        refreshButtons();
    }

    @Override
    public void onScrapeError(String message) {
        afterCollect = null;
        setStatus("Collection failed: " + message);
        refreshButtons();
    }

    // ---------------------------------------------------------------- PDF export

    private void exportThreadPdf() {
        final ThreadData t = thread;
        busy = true;
        refreshButtons();
        setStatus("Writing PDF…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Uri uri = PdfExporter.exportThread(MainActivity.this, t);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            finishBusy("Saved Downloads/XTract/" + t.fileStem() + "-thread.pdf");
                            offerFile(uri, "application/pdf", "Thread PDF saved");
                        }
                    });
                } catch (final IOException | RuntimeException e) {
                    postError("PDF export failed", e);
                }
            }
        });
    }

    private void offerFile(final Uri uri, final String mime, String title) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("Saved in Downloads/XTract.")
                .setPositiveButton("Open", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try {
                            startActivity(view);
                        } catch (ActivityNotFoundException e) {
                            toast("No app installed that can open " + mime);
                        }
                    }
                })
                .setNeutralButton("Share", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Intent send = new Intent(Intent.ACTION_SEND).setType(mime)
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(send, "Share"));
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ---------------------------------------------------------------- Analysis

    private void showAnalyzeMenu() {
        final boolean haveSummary = summary != null && summaryForStatusId != null
                && summaryForStatusId.equals(ThreadData.statusId(web.getUrl()));
        final boolean claudeApp = isInstalled(CLAUDE_APP);
        String[] items = haveSummary
                ? new String[]{"View last summary", "Analyze with Claude (API)", claudeApp ? "Send to Claude app" : "Send to an AI app"}
                : new String[]{"Analyze with Claude (API)", claudeApp ? "Send to Claude app" : "Send to an AI app"};
        new AlertDialog.Builder(this)
                .setTitle("Find trending ideas")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        int choice = haveSummary ? which : which + 1;
                        if (choice == 0) showSummary();
                        else if (choice == 1) analyzeWithApi();
                        else withThread(new Runnable() {
                            @Override
                            public void run() {
                                sendToClaudeApp();
                            }
                        });
                    }
                })
                .show();
    }

    private void analyzeWithApi() {
        if (!settings.hasApiKey()) {
            showSettings(new Runnable() {
                @Override
                public void run() {
                    if (settings.hasApiKey()) analyzeWithApi();
                }
            });
            return;
        }
        withThread(new Runnable() {
            @Override
            public void run() {
                runAnalysis();
            }
        });
    }

    private void runAnalysis() {
        final ThreadData t = thread;
        final String key = settings.apiKey();
        final String model = settings.model();
        busy = true;
        refreshButtons();
        setStatus("Sending " + t.posts.size() + " posts to " + model + "… this can take a minute.");
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final ClaudeClient.Result r = ClaudeClient.summarize(key, model, t, new ClaudeClient.Progress() {
                        private long last;

                        @Override
                        public void onText(final int chars) {
                            long now = System.currentTimeMillis();
                            if (now - last < 300) return;
                            last = now;
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    setStatus("Claude is writing the summary… " + chars + " characters");
                                }
                            });
                        }
                    });
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            summary = r.markdown;
                            summaryModel = r.model;
                            summaryForStatusId = t.statusId;
                            finishBusy(r.truncated
                                    ? "Summary ready. The thread was very long; only the first part was analysed."
                                    : "Summary ready.");
                            showSummary();
                        }
                    });
                } catch (final ClaudeClient.ClaudeException e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            finishBusy(e.getMessage());
                            new AlertDialog.Builder(MainActivity.this).setTitle("Analysis failed")
                                    .setMessage(e.getMessage()).setPositiveButton("OK", null).show();
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    postError("Analysis failed (network)", e);
                }
            }
        });
    }

    private void showSummary() {
        if (summary == null) return;
        TextView tv = new TextView(this);
        tv.setText(summary);
        tv.setTextIsSelectable(true);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(Color.rgb(15, 20, 25));
        tv.setPadding(dp(20), dp(12), dp(20), dp(12));
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Summary (" + summaryModel + ")")
                .setView(sv)
                .setPositiveButton("Export PDF", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        exportSummaryPdf();
                    }
                })
                .setNeutralButton("Copy", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("XTract summary", summary));
                        toast("Summary copied");
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void exportSummaryPdf() {
        final ThreadData t = thread;
        final String md = summary, model = summaryModel;
        if (t == null || md == null || !t.statusId.equals(summaryForStatusId)) {
            toast("Collect the thread again before exporting.");
            return;
        }
        busy = true;
        refreshButtons();
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Uri uri = PdfExporter.exportSummary(MainActivity.this, t, md, model);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            finishBusy("Saved Downloads/XTract/" + t.fileStem() + "-summary.pdf");
                            offerFile(uri, "application/pdf", "Summary PDF saved");
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    postError("Summary export failed", e);
                }
            }
        });
    }

    /**
     * Hands the thread to the Claude Android app (or any app that accepts shared files) as a
     * text attachment with the analysis prompt, so it can be analysed without an API key.
     */
    private void sendToClaudeApp() {
        final ThreadData t = thread;
        busy = true;
        refreshButtons();
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Uri uri = Downloads.saveText(MainActivity.this, t.fileStem() + "-thread.txt", t.toPlainText());
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            finishBusy("Thread text saved to Downloads/XTract.");
                            String prompt = "The attached file is an X thread: the original post followed by "
                                    + t.replyCount() + " replies. " + ClaudeClient.SYSTEM_PROMPT + "\n\n"
                                    + ClaudeClient.USER_INSTRUCTIONS;
                            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .putExtra(Intent.EXTRA_TEXT, prompt)
                                    .putExtra(Intent.EXTRA_SUBJECT, t.title())
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            if (isInstalled(CLAUDE_APP)) {
                                send.setPackage(CLAUDE_APP);
                                try {
                                    startActivity(send);
                                    return;
                                } catch (ActivityNotFoundException e) {
                                    send.setPackage(null);
                                }
                            }
                            startActivity(Intent.createChooser(send, "Send thread to…"));
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    postError("Could not prepare the thread", e);
                }
            }
        });
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void finishBusy(String message) {
        busy = false;
        refreshButtons();
        setStatus(message);
    }

    private void postError(final String what, final Exception e) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                finishBusy(what + ": " + e.getMessage());
                toast(what + ": " + e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- Settings

    private void showSettings(final Runnable onSaved) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        TextView info = new TextView(this);
        info.setText("Analyze uses the Claude API with your own key (console.anthropic.com → API keys). "
                + "The key is encrypted on this phone and only sent to api.anthropic.com. "
                + "Without a key, use \"Send to Claude app\" instead.");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(info);

        final EditText key = new EditText(this);
        key.setHint(settings.hasApiKey() ? "Key saved — type to replace" : "sk-ant-…");
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(key);

        TextView ml = new TextView(this);
        ml.setText("Model");
        ml.setTypeface(Typeface.DEFAULT_BOLD);
        ml.setPadding(0, dp(12), 0, 0);
        box.addView(ml);
        final Spinner model = new Spinner(this);
        model.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ClaudeClient.MODELS));
        int idx = Arrays.asList(ClaudeClient.MODELS).indexOf(settings.model());
        model.setSelection(Math.max(idx, 0));
        box.addView(model);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String k = key.getText().toString().trim();
                        if (!k.isEmpty()) settings.setApiKey(k);
                        settings.setModel((String) model.getSelectedItem());
                        toast("Settings saved");
                        if (onSaved != null) onSaved.run();
                    }
                })
                .setNegativeButton("Cancel", null);
        if (settings.hasApiKey()) {
            b.setNeutralButton("Remove key", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    settings.setApiKey("");
                    toast("API key removed");
                }
            });
        }
        b.show();
    }
}
