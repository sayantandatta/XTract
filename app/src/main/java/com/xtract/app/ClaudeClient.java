package com.xtract.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Calls the Claude Messages API over HTTPS with streaming. Raw HTTP is used instead of the
 * Java SDK so the APK stays dependency-free (the SDK pulls in OkHttp, Kotlin and Jackson).
 */
final class ClaudeClient {

    static final String DEFAULT_MODEL = "claude-opus-5-5";
    static final String[] MODELS = {"claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"};

    /** Roughly 250K tokens of thread text; larger threads are cut and the cut is reported. */
    static final int MAX_THREAD_CHARS = 1_000_000;

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2026-10-21";
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    static final String SYSTEM_PROMPT =
            "You analyse public discussion threads from X (formerly Twitter). You receive the original post "
            + "followed by the replies, in page order, each numbered with author, time, text and engagement "
            + "stats. Identify what the crowd in the replies is actually saying. Group replies by idea, not "
            + "by wording; weigh how many distinct people raise an idea and how much engagement those replies "
            + "got. Quote short representative replies verbatim with their [number] and handle. Ignore spam, "
            + "bots and pure emoji reactions unless they are a notable share of the thread. Be specific and "
            + "neutral; do not invent content that is not in the thread.";

    static final String USER_INSTRUCTIONS =
            "Write a summary document of this thread in Markdown with exactly these sections:\n"
            + "# <a short title for the discussion>\n"
            + "## Overview — 3-5 sentences: what the original post says and how the replies responded overall.\n"
            + "## Trending ideas — the main ideas ranked by prominence. For each: a bold one-line name, an "
            + "approximate share of replies, 2-3 sentences explaining it, and 1-2 short quotes.\n"
            + "## Sentiment — the overall mood and how it splits (support / criticism / jokes / questions).\n"
            + "## Notable dissent & minority views\n"
            + "## Questions people are asking\n"
            + "## Key takeaways — 3-6 bullets.\n"
            + "Use plain Markdown only (headings, bullets, **bold**, > quotes); no tables.";

    interface Progress {
        void onText(int charsReceived);
    }

    static final class Result {
        final String markdown;
        final String model;
        final boolean truncated;

        Result(String markdown, String model, boolean truncated) {
            this.markdown = markdown;
            this.model = model;
            this.truncated = truncated;
        }
    }

    static final class ClaudeException extends Exception {
        ClaudeException(String message) {
            super(message);
        }
    }

    private ClaudeClient() {}

    static Result summarize(String apiKey, String model, ThreadData thread, Progress progress)
            throws IOException, ClaudeException {
        String text = thread.toPlainText();
        boolean truncated = text.length() > MAX_THREAD_CHARS;
        if (truncated) text = text.substring(0, MAX_THREAD_CHARS) + "\n\n[... thread truncated for length ...]";

        String body;
        try {
            body = requestBody(model, text, truncated).toString();
        } catch (JSONException e) {
            throw new ClaudeException("Could not build request: " + e.getMessage());
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(180_000); // the stream sends pings, so this only trips on a dead connection
            conn.setDoOutput(true);
            conn.setRequestProperty("content-type", "application/json");
            conn.setRequestProperty("accept", "text/event-stream");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", API_VERSION);
            if (supportsFallback(model)) conn.setRequestProperty("anthropic-beta", FALLBACK_BETA);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }

            int status = conn.getResponseCode();
            if (status != 200) throw new ClaudeException(httpError(status, readAll(conn.getErrorStream())));

            StringBuilder out = new StringBuilder();
            String stopReason = null;
            String servedModel = model;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    JSONObject ev;
                    try {
                        ev = new JSONObject(line.substring(5).trim());
                    } catch (JSONException e) {
                        continue;
                    }
                    switch (ev.optString("type")) {
                        case "message_start":
                            JSONObject msg = ev.optJSONObject("message");
                            if (msg != null) servedModel = msg.optString("model", servedModel);
                            break;
                        case "content_block_delta":
                            JSONObject d = ev.optJSONObject("delta");
                            if (d != null && "text_delta".equals(d.optString("type"))) {
                                out.append(d.optString("text"));
                                progress.onText(out.length());
                            }
                            break;
                        case "message_delta":
                            JSONObject md = ev.optJSONObject("delta");
                            if (md != null && !md.isNull("stop_reason")) stopReason = md.optString("stop_reason");
                            break;
                        case "error":
                            JSONObject err = ev.optJSONObject("error");
                            throw new ClaudeException("Claude API error: "
                                    + (err == null ? "unknown" : err.optString("message", err.toString())));
                        default:
                            break;
                    }
                }
            }

            if ("refusal".equals(stopReason)) {
                throw new ClaudeException("Claude declined to analyse this thread (its safety checks flagged the content).");
            }
            if (out.length() == 0) throw new ClaudeException("Claude returned an empty response.");
            if ("max_tokens".equals(stopReason)) out.append("\n\n*(Summary was cut off at the length limit.)*");
            return new Result(out.toString(), servedModel, truncated);
        } finally {
            conn.disconnect();
        }
    }

    private static JSONObject requestBody(String model, String threadText, boolean truncated) throws JSONException {
        String user = USER_INSTRUCTIONS
                + (truncated ? "\nNote: the thread was too long and was truncated at the end." : "")
                + "\n\n<thread>\n" + threadText + "\n</thread>";
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("max_tokens", 32000)
                .put("stream", true)
                .put("thinking", new JSONObject().put("type", "adaptive"))
                .put("output_config", new JSONObject().put("effort", "medium"))
                .put("system", SYSTEM_PROMPT)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", user)));
        // Server-side fallback reroutes a safety-classifier refusal to another model instead of failing.
        if (supportsFallback(model)) body.put("fallbacks", "default");
        return body;
    }

    private static boolean supportsFallback(String model) {
        return model.startsWith("claude-opus-5") || model.equals("claude-sonnet-5-5") || model.startsWith("claude-fable-5");
    }

    private static String httpError(int status, String body) {
        String detail = body;
        try {
            JSONObject err = new JSONObject(body).optJSONObject("error");
            if (err != null) detail = err.optString("message", body);
        } catch (JSONException ignored) {
        }
        switch (status) {
            case 401:
                return "Invalid API key. Check it in Settings.";
            case 403:
                return "This API key is not allowed to use that model. " + detail;
            case 404:
                return "Model not found: " + detail;
            case 413:
                return "The thread is too large for one request.";
            case 429:
                return "Rate limited by the Claude API. Wait a minute and try again.";
            case 529:
                return "Claude is overloaded right now. Try again shortly.";
            default:
                return "Claude API error " + status + ": " + detail;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
