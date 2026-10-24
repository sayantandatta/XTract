package com.xtract.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.StyleSpan;

import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders threads and summaries to A4 PDFs saved in Downloads/XTract. */
final class PdfExporter {

    private static final int PAGE_W = 595, PAGE_H = 842, MARGIN = 44, FOOTER = 24;
    private static final int TEXT = Color.rgb(15, 20, 25);
    private static final int MUTED = Color.rgb(83, 100, 113);
    private static final int ACCENT = Color.rgb(29, 155, 240);
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");

    private PdfExporter() {}

    static Uri exportThread(Context ctx, ThreadData t) throws IOException {
        Writer w = new Writer(t.title());
        w.text(t.title(), w.paint(20, true, TEXT), 0, 4);
        w.text(t.sourceUrl, w.paint(9, false, ACCENT), 0, 2);
        w.text("Exported " + DateFormat.getDateTimeInstance().format(new Date(t.collectedAt))
                + "  ·  " + t.posts.size() + " posts, " + t.replyCount() + " replies", w.paint(9, false, MUTED), 0, 14);

        int fi = t.focalIndex();
        for (int i = 0; i < t.posts.size(); i++) {
            ThreadData.Post p = t.posts.get(i);
            if (i == 0 && fi > 0) w.section("Earlier in thread");
            if (i == fi) w.section("Original post");
            if (i == fi + 1) w.section("Replies (" + t.replyCount() + ")");
            boolean focal = i == fi;
            w.text(p.header(), w.paint(focal ? 12 : 10.5f, true, TEXT), 0, 3);
            if (!p.text.isEmpty()) w.text(p.text, w.paint(focal ? 12 : 10.5f, false, TEXT), 0, 3);
            if (!p.quote.isEmpty()) w.text("Quoting: " + p.quote, w.italic(w.paint(9.5f, false, MUTED)), 14, 3);
            StringBuilder meta = new StringBuilder();
            if (p.media > 0) meta.append(p.media).append(p.media == 1 ? " media attachment" : " media attachments");
            if (!p.stats.isEmpty()) meta.append(meta.length() > 0 ? "  ·  " : "").append(p.stats);
            if (meta.length() > 0) w.text(meta, w.paint(8.5f, false, MUTED), 0, 3);
            w.text(p.url, w.paint(7.5f, false, ACCENT), 0, 6);
            w.rule();
        }
        return save(ctx, w, t.fileStem() + "-thread.pdf");
    }

    static Uri exportSummary(Context ctx, ThreadData t, String markdown, String model) throws IOException {
        Writer w = new Writer("Summary: " + t.title());
        w.text("Thread summary & trending ideas", w.paint(20, true, TEXT), 0, 4);
        w.text(t.title(), w.paint(12, false, TEXT), 0, 2);
        w.text(t.sourceUrl, w.paint(9, false, ACCENT), 0, 2);
        w.text("Analysed " + t.posts.size() + " posts (" + t.replyCount() + " replies) with " + model + "  ·  "
                + DateFormat.getDateTimeInstance().format(new Date()), w.paint(9, false, MUTED), 0, 14);
        renderMarkdown(w, markdown);
        return save(ctx, w, t.fileStem() + "-summary.pdf");
    }

    /** Minimal Markdown: headings, bullets, numbered lists, quotes, rules and **bold**. */
    private static void renderMarkdown(Writer w, String md) {
        for (String raw : md.replace("\r", "").split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                w.space(5);
            } else if (line.matches("^(-{3,}|\\*{3,}|_{3,})$")) {
                w.rule();
            } else if (line.startsWith("#")) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') level++;
                String h = line.substring(level).trim();
                float size = level <= 1 ? 17 : level == 2 ? 14 : 12;
                w.space(level <= 2 ? 8 : 4);
                w.text(inline(h), w.paint(size, true, level == 2 ? ACCENT : TEXT), 0, 4);
            } else if (line.matches("^[-*+]\\s+.*")) {
                int indent = 10 + Math.min(raw.indexOf(line.charAt(0)), 8) * 4;
                w.text(inline("•  " + line.substring(1).trim()), w.paint(10.5f, false, TEXT), indent, 3);
            } else if (line.matches("^\\d+[.)]\\s+.*")) {
                w.text(inline(line), w.paint(10.5f, false, TEXT), 10, 3);
            } else if (line.startsWith(">")) {
                w.text(inline(line.substring(1).trim()), w.italic(w.paint(10, false, MUTED)), 16, 3);
            } else {
                w.text(inline(line), w.paint(10.5f, false, TEXT), 0, 4);
            }
        }
    }

    private static CharSequence inline(String s) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        Matcher m = BOLD.matcher(s);
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            String inner = m.group(1) != null ? m.group(1) : m.group(2);
            int start = sb.length();
            sb.append(inner);
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            last = m.end();
        }
        sb.append(s, last, s.length());
        // Drop leftover *emphasis* and `code` markers.
        for (int i = sb.length() - 1; i >= 0; i--) {
            char c = sb.charAt(i);
            if (c == '`' || c == '*') sb.delete(i, i + 1);
        }
        return sb;
    }

    private static Uri save(Context ctx, final Writer w, String fileName) throws IOException {
        return Downloads.save(ctx, fileName, "application/pdf", new Downloads.Body() {
            @Override
            public void writeTo(OutputStream out) throws IOException {
                w.finish(out);
            }
        });
    }

    /** Flows text top-to-bottom across pages, splitting paragraphs between lines when needed. */
    private static final class Writer {
        private final PdfDocument doc = new PdfDocument();
        private final String footer;
        private PdfDocument.Page page;
        private Canvas canvas;
        private float y;
        private int pageNo;

        Writer(String footer) {
            this.footer = footer;
            newPage();
        }

        TextPaint paint(float size, boolean bold, int color) {
            TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            p.setTextSize(size);
            p.setColor(color);
            p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            return p;
        }

        TextPaint italic(TextPaint p) {
            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
            return p;
        }

        void section(String title) {
            space(6);
            text(title.toUpperCase(), paint(9, true, ACCENT), 0, 6);
        }

        void text(CharSequence s, TextPaint paint, float indent, float after) {
            int width = (int) (PAGE_W - 2 * MARGIN - indent);
            StaticLayout layout = StaticLayout.Builder.obtain(s, 0, s.length(), paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0, 1.15f)
                    .setIncludePad(false)
                    .build();
            int line = 0, count = layout.getLineCount();
            while (line < count) {
                float room = bottom() - y;
                int end = line;
                while (end < count && layout.getLineBottom(end) - layout.getLineTop(line) <= room) end++;
                if (end == line) {
                    if (y > MARGIN) {
                        newPage();
                        continue;
                    }
                    end = line + 1; // a single line taller than a page; draw it anyway
                }
                float top = layout.getLineTop(line);
                float h = layout.getLineBottom(end - 1) - top;
                canvas.save();
                canvas.translate(MARGIN + indent, y - top);
                canvas.clipRect(0, top, width, top + h);
                layout.draw(canvas);
                canvas.restore();
                y += h;
                line = end;
            }
            space(after);
        }

        void space(float h) {
            y += h;
            if (y > bottom()) newPage();
        }

        void rule() {
            if (y + 8 > bottom()) {
                newPage();
                return;
            }
            Paint p = new Paint();
            p.setColor(Color.rgb(207, 217, 222));
            p.setStrokeWidth(0.6f);
            canvas.drawLine(MARGIN, y + 3, PAGE_W - MARGIN, y + 3, p);
            y += 10;
        }

        private float bottom() {
            return PAGE_H - MARGIN - FOOTER;
        }

        private void newPage() {
            if (page != null) closePage();
            pageNo++;
            page = doc.startPage(new PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create());
            canvas = page.getCanvas();
            y = MARGIN;
        }

        private void closePage() {
            TextPaint p = paint(7.5f, false, MUTED);
            String left = footer.length() > 80 ? footer.substring(0, 79) + "…" : footer;
            canvas.drawText("XTract  ·  " + left, MARGIN, PAGE_H - MARGIN / 2f, p);
            String num = String.valueOf(pageNo);
            canvas.drawText(num, PAGE_W - MARGIN - p.measureText(num), PAGE_H - MARGIN / 2f, p);
            doc.finishPage(page);
            page = null;
        }

        void finish(OutputStream out) throws IOException {
            closePage();
            try {
                doc.writeTo(out);
            } finally {
                doc.close();
            }
        }
    }
}
