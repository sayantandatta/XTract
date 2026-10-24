package com.xtract.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.ValueCallback;
import android.webkit.WebStorage;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements ThreadScraper.Listener {

    private enum Job { PDF, CLAUDE, PRINT }

    /** Collection fills this much of the dial; writing the file fills the rest. */
    private static final float COLLECT_SHARE = 0.9f;
    private static final long CAPTION_LINGER_MS = 6000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Settings settings;
    private AppTheme theme;
    private ThreadScraper scraper;

    private WebView web;
    private ProgressDial dial;
    private TextView caption;
    private final List<View> actions = new ArrayList<>();

    private ThreadData thread;          // last collected thread
    private Job pendingJob;             // what to do once collection finishes
    private String scrapeStatusId;      // post being collected
    private float collectShown;         // dial fraction reached so far (never moves backwards)
    private boolean busy;
    private boolean onThread;           // current page is a post
    private int captionGeneration;

    @Override
    protected void onCreate(Bundle state) {
        settings = new Settings(this);
        theme = AppTheme.resolve(this, settings.theme());
        setTheme(theme.styleRes);
        super.onCreate(state);
        scraper = new ThreadScraper(this, this);
        setContentView(buildUi());
        getWindow().setStatusBarColor(theme.background);
        getWindow().setNavigationBarColor(theme.background);
        setupWebView();
        clearPrintCache();

        String shared = sharedThreadUrl(getIntent());
        if (settings.disguiseVersion() != WebViewDisguise.VERSION) {
            // The browser fingerprint changed. X may have flagged the old one in its cookies,
            // so start this version from a clean session.
            settings.setDisguiseVersion(WebViewDisguise.VERSION);
            resetSession(shared != null ? shared : settings.lastUrl());
        } else {
            if (state != null) web.restoreState(state);
            if (shared != null) web.loadUrl(shared);
            else if (state == null) web.loadUrl(settings.lastUrl());
        }
    }

    /** Clears cookies, storage and cache (logs out of X), then loads {@code thenLoad}. */
    private void resetSession(final String thenLoad) {
        thread = null;
        WebStorage.getInstance().deleteAllData();
        web.clearCache(true);
        web.clearHistory();
        final CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(new ValueCallback<Boolean>() {
            @Override
            public void onReceiveValue(Boolean removed) {
                cm.flush();
                web.loadUrl(thenLoad);
            }
        });
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
        if (scraper.isRunning()) finishCollectingEarly();
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.background);

        // Top bar: brand on the left, status caption and progress dial on the right.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), 0, dp(12), 0);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(8), dp(8), dp(10), dp(8));
        brand.setBackgroundResource(rippleBackground());
        brand.setContentDescription("XTract menu");
        brand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu();
            }
        });
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView title = new TextView(this);
        SpannableString name = new SpannableString("XTract");
        name.setSpan(new ForegroundColorSpan(AppTheme.ACCENT), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(name);
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(8), 0, 0, 0);
        brand.addView(title);
        top.addView(brand);

        caption = new TextView(this);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        caption.setTextColor(theme.muted);
        caption.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setPadding(dp(8), 0, dp(10), 0);
        top.addView(caption, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        dial = new ProgressDial(this);
        dial.setColors(theme.border, AppTheme.ACCENT, theme.text);
        dial.setContentDescription("Progress");
        dial.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (scraper.isRunning()) finishCollectingEarly();
            }
        });
        top.addView(dial, new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        root.addView(hairline());

        web = new WebView(this);
        web.setBackgroundColor(theme.background);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Bottom bar: the three actions from the sketch.
        root.addView(hairline());
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(action(R.drawable.ic_pdf, "PDF", AppTheme.PDF_RED, "Download the thread as a PDF", Job.PDF));
        bar.addView(action(R.drawable.ic_claude, "Claude", AppTheme.CLAUDE_CLAY, "Share the thread to Claude", Job.CLAUDE));
        bar.addView(action(R.drawable.ic_print, "Print", theme.text, "Print the thread", Job.PRINT));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        setOnThread(false);
        return root;
    }

    private View action(int icon, String label, int tint, String description, final Job job) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setBackgroundResource(rippleBackground());
        cell.setContentDescription(description);
        cell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(tint);
        cell.addView(iv, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(theme.text);
        tv.setPadding(0, dp(3), 0, 0);
        cell.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startJob(job);
            }
        });
        actions.add(cell);
        return cell;
    }

    private View hairline() {
        View v = new View(this);
        v.setBackgroundColor(theme.border);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        return v;
    }

    private int rippleBackground() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        return tv.resourceId;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void refreshActions() {
        boolean enabled = onThread && !busy;
        for (View a : actions) {
            a.setEnabled(enabled);
            a.setAlpha(enabled ? 1f : 0.35f);
        }
    }

    private void setOnThread(boolean on) {
        onThread = on;
        refreshActions();
    }

    private void setBusy(boolean b) {
        busy = b;
        refreshActions();
    }

    /** Shows a short status next to the dial; a final message fades out after a few seconds. */
    private void setCaption(String s, boolean linger) {
        final int gen = ++captionGeneration;
        caption.setText(s);
        if (linger) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (gen == captionGeneration) caption.setText("");
                }
            }, CAPTION_LINGER_MS);
        }
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
        WebViewDisguise.apply(web); // X refuses to log in from anything it recognises as a WebView
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        scraper.attach(web);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String scheme = url.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    if (!request.isForMainFrame() || isXHost(url.getHost())) return false;
                    // Links that leave X open in the phone's browser; this app is only for X.
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, url));
                    } catch (ActivityNotFoundException e) {
                        return false;
                    }
                    return true;
                }
                try {
                    startActivity(Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME));
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
    }

    private static boolean isXHost(String host) {
        if (host == null) return true;
        host = host.toLowerCase();
        return host.equals("x.com") || host.endsWith(".x.com")
                || host.equals("twitter.com") || host.endsWith(".twitter.com")
                || host.equals("t.co") || host.endsWith(".twimg.com")
                || host.equals("appleid.apple.com") || host.equals("accounts.google.com");
    }

    /** A full page load kills the injected script; a history change to another post makes its results wrong. */
    private void onUrlChanged(String url, boolean fullLoad) {
        setOnThread(ThreadData.isThreadUrl(url));
        if (scraper.isRunning() && (fullLoad || !scrapeStatusId.equals(ThreadData.statusId(url)))) {
            scraper.cancel();
            failJob("Cancelled: the page changed.");
        }
    }

    private static String sharedThreadUrl(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return null;
        return ThreadData.findThreadUrl(intent.getStringExtra(Intent.EXTRA_TEXT));
    }

    // ---------------------------------------------------------------- Jobs

    private void startJob(Job job) {
        if (busy) return;
        String id = ThreadData.statusId(web.getUrl());
        if (id == null) {
            toast("Open a post first.");
            return;
        }
        if (job == Job.CLAUDE && !ClaudeShare.isInstalled(this)) {
            showGetClaude();
            return;
        }
        if (thread != null && id.equals(thread.statusId) && !thread.posts.isEmpty()) {
            runJob(job);
            return;
        }
        pendingJob = job;
        scrapeStatusId = id;
        collectShown = 0;
        setBusy(true);
        dial.setIndeterminate();
        setCaption("Collecting replies…", false);
        scraper.start(web);
    }

    private void finishCollectingEarly() {
        scraper.stop(web);
        setCaption("Finishing with what has loaded…", false);
    }

    @Override
    public void onScrapeProgress(int posts, int expanded, double fraction) {
        collectShown = Math.max(collectShown, (float) fraction * COLLECT_SHARE);
        dial.setProgress(collectShown);
        setCaption(posts + (posts == 1 ? " post" : " posts") + " · tap the dial to stop", false);
    }

    @Override
    public void onScrapeDone(String sourceUrl, String json) {
        try {
            ThreadData t = ThreadData.fromJson(sourceUrl, json);
            if (t.posts.isEmpty()) {
                failJob("No posts found. Are you logged in to X?");
                return;
            }
            thread = t;
            Job job = pendingJob;
            pendingJob = null;
            if (job != null) runJob(job);
            else finishJob(t.posts.size() + " posts collected");
        } catch (JSONException e) {
            failJob("Could not read the collected posts.");
        }
    }

    @Override
    public void onScrapeError(String message) {
        failJob("Collection failed: " + message);
    }

    private void runJob(Job job) {
        setBusy(true);
        switch (job) {
            case PDF:
                exportPdf();
                break;
            case CLAUDE:
                shareToClaude();
                break;
            case PRINT:
                printThread();
                break;
        }
    }

    private void finishJob(String message) {
        pendingJob = null;
        setBusy(false);
        dial.setDone();
        setCaption(message, true);
    }

    private void failJob(String message) {
        pendingJob = null;
        setBusy(false);
        dial.setIdle();
        setCaption(message, true);
        toast(message);
    }

    private void postFailure(final String what, final Exception e) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                failJob(what + ": " + e.getMessage());
            }
        });
    }

    private PdfExporter.Progress pdfProgress() {
        return new PdfExporter.Progress() {
            @Override
            public void onProgress(final int done, final int total) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        dial.setProgress(COLLECT_SHARE + (1 - COLLECT_SHARE) * done / total);
                    }
                });
            }
        };
    }

    // ---------------------------------------------------------------- PDF

    private void exportPdf() {
        final ThreadData t = thread;
        dial.setProgress(COLLECT_SHARE);
        setCaption("Writing PDF…", false);
        io.execute(new Runnable() {
            @Override
            public void run() {
                try (PdfExporter.Document d = PdfExporter.render(t, pdfProgress())) {
                    final Uri uri = PdfExporter.saveToDownloads(MainActivity.this, d);
                    final int pages = d.pages;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            finishJob("Saved · " + pages + (pages == 1 ? " page" : " pages"));
                            offerFile(uri, d.fileName);
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    postFailure("PDF export failed", e);
                }
            }
        });
    }

    private void offerFile(final Uri uri, String fileName) {
        new AlertDialog.Builder(this)
                .setTitle("PDF saved")
                .setMessage("Downloads/XTract/" + fileName)
                .setPositiveButton("Open", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try {
                            startActivity(view);
                        } catch (ActivityNotFoundException e) {
                            toast("No PDF viewer installed.");
                        }
                    }
                })
                .setNeutralButton("Share", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Intent send = new Intent(Intent.ACTION_SEND).setType("application/pdf")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(send, "Share PDF"));
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ---------------------------------------------------------------- Claude

    private void shareToClaude() {
        final ThreadData t = thread;
        dial.setProgress(COLLECT_SHARE);
        setCaption("Preparing for Claude…", false);
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Uri uri = Downloads.saveText(MainActivity.this, t.fileStem() + "-thread.txt", t.toPlainText());
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            finishJob("Sent to Claude");
                            ClaudeShare.send(MainActivity.this, uri, t);
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    postFailure("Could not prepare the thread", e);
                }
            }
        });
    }

    private void showGetClaude() {
        new AlertDialog.Builder(this)
                .setTitle("Claude isn't installed")
                .setMessage("XTract sends the thread to the Claude app, which finds the trending ideas and writes "
                        + "the summary. Install Claude from Google Play, then tap Claude again.")
                .setPositiveButton("Get Claude", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        ClaudeShare.openPlayStore(MainActivity.this);
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    // ---------------------------------------------------------------- Print

    private void printThread() {
        final ThreadData t = thread;
        dial.setProgress(COLLECT_SHARE);
        setCaption("Preparing to print…", false);
        io.execute(new Runnable() {
            @Override
            public void run() {
                try (PdfExporter.Document d = PdfExporter.render(t, pdfProgress())) {
                    final File file = PdfExporter.saveToCache(MainActivity.this, d);
                    final int pages = d.pages;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            finishJob("Ready to print · " + pages + (pages == 1 ? " page" : " pages"));
                            print(file, t.fileStem() + "-thread", pages);
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    postFailure("Print failed", e);
                }
            }
        });
    }

    /** Hands the rendered PDF to Android's print framework (any printer, or "Save as PDF"). */
    private void print(final File file, final String jobName, final int pages) {
        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (pm == null) {
            toast("Printing isn't available on this device.");
            return;
        }
        pm.print(jobName, new PrintDocumentAdapter() {
            @Override
            public void onLayout(PrintAttributes oldAttrs, PrintAttributes newAttrs, CancellationSignal signal,
                                 LayoutResultCallback callback, Bundle extras) {
                if (signal.isCanceled()) {
                    callback.onLayoutCancelled();
                    return;
                }
                callback.onLayoutFinished(new PrintDocumentInfo.Builder(jobName + ".pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(pages)
                        .build(), !newAttrs.equals(oldAttrs));
            }

            @Override
            public void onWrite(PageRange[] ranges, ParcelFileDescriptor dest, CancellationSignal signal,
                                WriteResultCallback callback) {
                try (InputStream in = new FileInputStream(file);
                     OutputStream out = new FileOutputStream(dest.getFileDescriptor())) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (signal.isCanceled()) {
                            callback.onWriteCancelled();
                            return;
                        }
                        out.write(buf, 0, n);
                    }
                    callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                } catch (IOException e) {
                    callback.onWriteFailed(e.getMessage());
                }
            }
        }, new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
    }

    private void clearPrintCache() {
        File[] old = new File(getCacheDir(), "print").listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    // ---------------------------------------------------------------- Menu

    private void showMenu() {
        final String[] items = {"Open a post link…", "X home", "Reload", "Theme: " + themeName(),
                "Browser check", "Reset X session", "About XTract"};
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                showOpenLink();
                                break;
                            case 1:
                                web.loadUrl("https://x.com/home");
                                break;
                            case 2:
                                web.reload();
                                break;
                            case 3:
                                showThemePicker();
                                break;
                            case 4:
                                web.loadDataWithBaseURL("https://xtract.local/check",
                                        WebViewDisguise.checkPageHtml(theme.dark), "text/html", "utf-8", null);
                                break;
                            case 5:
                                confirmReset();
                                break;
                            default:
                                showAbout();
                                break;
                        }
                    }
                })
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Reset X session?")
                .setMessage("Logs out of X and clears everything the site stored in this app. "
                        + "Use it when X says you are not allowed to log in.")
                .setPositiveButton("Reset", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        resetSession("https://x.com/login");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String themeName() {
        int i = Arrays.asList(AppTheme.IDS).indexOf(settings.theme());
        return AppTheme.NAMES[Math.max(i, 0)];
    }

    private void showOpenLink() {
        final EditText input = new EditText(this);
        input.setHint("https://x.com/user/status/…");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        String fromClipboard = clipboardThreadUrl();
        if (fromClipboard != null) input.setText(fromClipboard);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Open a post")
                .setMessage("Paste a link, or share a post from the X app to XTract.")
                .setView(box)
                .setPositiveButton("Open", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String url = ThreadData.findThreadUrl(input.getText().toString());
                        if (url == null) toast("That isn't a link to an X post.");
                        else web.loadUrl(url);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String clipboardThreadUrl() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            return ThreadData.findThreadUrl(text == null ? null : text.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void showThemePicker() {
        int current = Math.max(0, Arrays.asList(AppTheme.IDS).indexOf(settings.theme()));
        new AlertDialog.Builder(this)
                .setTitle("Theme")
                .setSingleChoiceItems(AppTheme.NAMES, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (!AppTheme.IDS[which].equals(settings.theme())) {
                            settings.setTheme(AppTheme.IDS[which]);
                            recreate();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("XTract")
                .setMessage("Open a post on X, then:\n\n"
                        + "PDF — scrolls through every reply and saves the whole thread to Downloads/XTract.\n\n"
                        + "Claude — collects the thread and sends it to the Claude app, which finds the trending "
                        + "ideas and writes a summary.\n\n"
                        + "Print — collects the thread and sends it to a printer or \"Save as PDF\".\n\n"
                        + "The dial at the top shows progress while collecting or writing. Tap it to stop early "
                        + "and keep what has loaded.\n\n"
                        + "Tip: in the X app, use Share → XTract on any post.")
                .setPositiveButton("OK", null)
                .show();
    }
}
