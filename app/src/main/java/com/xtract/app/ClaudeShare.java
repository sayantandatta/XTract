package com.xtract.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

/** Hands a collected thread to the Claude Android app, or sends the user to Google Play to get it. */
final class ClaudeShare {

    static final String PACKAGE = "com.anthropic.claude";

    /** The message that accompanies the thread file when it is shared into Claude. */
    static final String PROMPT =
            "The attached file is a public discussion thread from X (formerly Twitter): the original post "
            + "followed by the replies in page order, each numbered with author, time, text and engagement stats.\n\n"
            + "Analyse what the crowd in the replies is actually saying. Group replies by idea, not by wording; "
            + "weigh how many distinct people raise an idea and how much engagement those replies got. Quote short "
            + "representative replies verbatim with their [number] and handle. Ignore spam, bots and pure emoji "
            + "reactions unless they are a notable share of the thread. Be specific and neutral; do not invent "
            + "anything that is not in the thread.\n\n"
            + "Write a summary document with exactly these sections:\n"
            + "1. Title — a short name for the discussion.\n"
            + "2. Overview — 3-5 sentences: what the original post says and how the replies responded overall.\n"
            + "3. Trending ideas — the main ideas ranked by prominence. For each: a one-line name, an approximate "
            + "share of replies, 2-3 sentences explaining it, and 1-2 short quotes.\n"
            + "4. Sentiment — the overall mood and how it splits (support / criticism / jokes / questions).\n"
            + "5. Notable dissent and minority views.\n"
            + "6. Questions people are asking.\n"
            + "7. Key takeaways — 3-6 bullets.";

    private ClaudeShare() {}

    static boolean isInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Opens Claude with the thread file attached and the analysis prompt as the message. */
    static void send(Activity activity, Uri threadFile, ThreadData thread) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, threadFile)
                .putExtra(Intent.EXTRA_TEXT, PROMPT)
                .putExtra(Intent.EXTRA_SUBJECT, thread.title())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(new Intent(send).setPackage(PACKAGE));
        } catch (ActivityNotFoundException e) {
            // Claude is installed but does not expose a share target for this type; let the user pick.
            activity.startActivity(Intent.createChooser(send, "Send thread to…"));
        }
    }

    static void openPlayStore(Activity activity) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + PACKAGE)));
        } catch (ActivityNotFoundException e) {
            activity.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + PACKAGE)));
        }
    }
}
