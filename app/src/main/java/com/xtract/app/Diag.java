package com.xtract.app;

import android.text.format.DateFormat;

import java.util.ArrayDeque;
import java.util.Deque;

/** A small ring buffer of recent page events (console messages, load errors) for the Diagnostics view. */
final class Diag {

    private static final int MAX = 60;
    private static final Deque<String> LINES = new ArrayDeque<>();

    private Diag() {}

    static synchronized void add(String line) {
        LINES.addLast(DateFormat.format("HH:mm:ss", System.currentTimeMillis()) + "  " + line);
        while (LINES.size() > MAX) LINES.removeFirst();
    }

    static synchronized String dump() {
        if (LINES.isEmpty()) return "(no page events yet)";
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }

    static synchronized void clear() {
        LINES.clear();
    }
}
