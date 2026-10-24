package com.xtract.app;

import android.content.Context;
import android.content.SharedPreferences;

/** App preferences: the page to reopen at launch. */
final class Settings {

    private final SharedPreferences prefs;

    Settings(Context ctx) {
        prefs = ctx.getSharedPreferences("xtract", Context.MODE_PRIVATE);
    }

    String lastUrl() {
        return prefs.getString("last_url", "https://x.com/home");
    }

    void setLastUrl(String url) {
        prefs.edit().putString("last_url", url).apply();
    }
}
