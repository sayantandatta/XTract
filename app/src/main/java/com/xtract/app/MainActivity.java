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
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
    private ThreadScraper scraper;

    private WebView web;
    private ProgressBar pageLoad;
    private ProgressDial dial;
    private TextView caption;
    private final List<ActionTile> actions = new ArrayList<>();

    private ThreadData thread;          // last collected thread
    private Job pendingJob;             // what to do once collection finishes
    private String scrapeStatusId;      // post being collected
    private float collectShown;         // dial fraction reached so far (never moves backwards)
    private boolean busy;
    private boolean onThread;           // current page is a post
    private int captionGeneration;

    /** One of the three bottom-bar actions. */
    private static final class ActionTile {
        LinearLayout root;
        ImageView icon;
        TextView label;
        int color;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this);
        scraper = new ThreadScraper(this, this);
        setContentView(buildUi());
        setupWebView();
        clearPrintCache();

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
        if (scraper.isRunning()) finishCollectingEarly();
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // ---------------------------------------------------------------- UI

    private int color(int res) {
        return getColor(res);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.surface));

        // Top bar: brand on the left (tap for menu), status caption and progress dial on the right.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), 0, dp(16), 0);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(10), dp(8), dp(12), dp(8));
        brand.setBackgroundResource(ripple());
        brand.setContentDescription("XTract menu");
        brand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu();
            }
        });
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView title = new TextView(this);
        SpannableString name = new SpannableString("XTract");
        name.setSpan(new ForegroundColorSpan(color(R.color.accent)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(name);
        title.setTextColor(color(R.color.text));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setLetterSpacing(-0.01f);
        title.setPadding(dp(8), 0, 0, 0);
        brand.addView(title);
        top.addView(brand);

        caption = new TextView(this);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        caption.setTextColor(color(R.color.muted));
        caption.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setPadding(dp(8), 0, dp(10), 0);
        top.addView(caption, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        dial = new ProgressDial(this);
        dial.setColors(color(R.color.hairline), color(R.color.accent), color(R.color.text));
        dial.setContentDescription("Progress");
        dial.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (scraper.isRunning()) finishCollectingEarly();
            }
        });
        top.addView(dial, new LinearLayout.LayoutParams(dp(32), dp(32)));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        // Page area, with a hairline on top and a thin loading line over it while X loads.
        FrameLayout page = new FrameLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(color(R.color.surface));
        page.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View topLine = new View(this);
        topLine.setBackgroundColor(color(R.color.hairline));
        page.addView(topLine, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        pageLoad = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageLoad.setMax(100);
        pageLoad.setProgressTintList(android.content.res.ColorStateList.valueOf(color(R.color.accent)));
        pageLoad.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x00000000));
        pageLoad.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
        plp.topMargin = -dp(1); // the bar's drawable has a little inner padding; tuck it onto the hairline
        page.addView(pageLoad, plp);
        root.addView(page, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Bottom bar: the three actions from the sketch, as rounded tiles.
        View bottomLine = new View(this);
        bottomLine.setBackgroundColor(color(R.color.hairline));
        root.addView(bottomLine, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(11), dp(10), dp(11), dp(12));
        bar.addView(action(R.drawable.ic_pdf, "PDF", color(R.color.pdf), "Download the thread as a PDF", Job.PDF));
        bar.addView(action(R.drawable.ic_claude, "Claude", color(R.color.claude), "Share the thread to Claude", Job.CLAUDE));
        bar.addView(action(R.drawable.ic_print, "Print", color(R.color.text), "Print the thread", Job.PRINT));
        root.addView(bar);
        refreshActions();
        return root;
    }

    private View action(int icon, String label, int tint, String description, final Job job) {
        ActionTile a = new ActionTile();
        a.color = tint;
        a.root = new LinearLayout(this);
        a.root.setOrientation(LinearLayout.VERTICAL);
        a.root.setGravity(Gravity.CENTER);
        a.root.setBackgroundResource(R.drawable.action_bg);
        a.root.setContentDescription(description);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(62), 1);
        lp.setMargins(dp(5), 0, dp(5), 0);
        a.root.setLayoutParams(lp);
        a.icon = new ImageView(this);
        a.icon.setImageResource(icon);
        a.root.addView(a.icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        a.label = new TextView(this);
        a.label.setText(label);
        a.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        a.label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        a.label.setPadding(0, dp(4), 0, 0);
        a.root.addView(a.label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        a.root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startJob(job);
            }
        });
        actions.add(a);
        return a.root;
    }

    private int ripple() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    /** Actions stay tappable off a post (they explain what to do) but look inactive. */
    private void refreshActions() {
        boolean active = onThread && !busy;
        for (ActionTile a : actions) {
            a.root.setEnabled(!busy);
            a.icon.setColorFilter(active ? a.color : color(R.color.inactive));
            a.label.setTextColor(active ? color(R.color.text) : color(R.color.inactive));
        }
    }

    private void setOnThread(boolean on) {
        if (on == onThread) return;
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
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
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
                pageLoad.setProgress(p);
                pageLoad.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
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
            toast("Open a post first");
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
        setCaption("Finishing with what's loaded…", false);
    }

    @Override
    public void onScrapeProgress(int posts, int expanded, double fraction) {
        collectShown = Math.max(collectShown, (float) fraction * COLLECT_SHARE);
        dial.setProgress(collectShown);
        setCaption(posts + (posts == 1 ? " post" : " posts") + " · tap dial to stop", false);
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
            failJob("Couldn't read the collected posts.");
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
                    final String fileName = d.fileName;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            finishJob("Saved · " + pages + (pages == 1 ? " page" : " pages"));
                            offerFile(uri, fileName);
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
                            toast("No PDF viewer installed");
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
                    postFailure("Couldn't prepare the thread", e);
                }
            }
        });
    }

    private void showGetClaude() {
        new AlertDialog.Builder(this)
                .setTitle("Get the Claude app")
                .setMessage("XTract sends the thread to the Claude app, which finds the trending ideas and "
                        + "writes the summary. Install Claude from Google Play, then tap Claude again.")
                .setPositiveButton("Open Google Play", new DialogInterface.OnClickListener() {
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
            toast("Printing isn't available on this device");
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
        final String[] items = {"Open a post link…", "X home", "Reload", "How it works"};
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
                            default:
                                showAbout();
                                break;
                        }
                    }
                })
                .show();
    }

    private void showOpenLink() {
        final EditText input = new EditText(this);
        input.setHint("https://x.com/user/status/…");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        String fromClipboard = clipboardThreadUrl();
        if (fromClipboard != null) input.setText(fromClipboard);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(22), dp(4), dp(22), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Open a post")
                .setMessage("Paste a link, or share a post from the X app to XTract.")
                .setView(box)
                .setPositiveButton("Open", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String url = ThreadData.findThreadUrl(input.getText().toString());
                        if (url == null) toast("That isn't a link to an X post");
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

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("How it works")
                .setMessage("Open a post on X, then tap one of the buttons at the bottom. "
                        + "Each one first scrolls through every reply to collect the whole thread.\n\n"
                        + "PDF — saves the thread to Downloads/XTract.\n\n"
                        + "Claude — sends the thread to the Claude app, which finds the trending ideas "
                        + "and writes a summary.\n\n"
                        + "Print — sends the thread to a printer or \"Save as PDF\".\n\n"
                        + "The dial at the top shows progress while collecting or writing. "
                        + "Tap it to stop early and keep what has loaded.\n\n"
                        + "Tip: in the X app, use Share → XTract on any post.")
                .setPositiveButton("Got it", null)
                .show();
    }
}
