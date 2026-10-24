package com.xtract.app;

import android.content.Context;
import android.content.res.Configuration;

/** The three X colour schemes (Light, Dim, Lights out), applied to the app chrome and the x.com page. */
final class AppTheme {

    static final String SYSTEM = "system", LIGHT = "light", DIM = "dim", DARK = "dark";
    static final String[] IDS = {SYSTEM, LIGHT, DIM, DARK};
    static final String[] NAMES = {"Follow system", "Light", "Dim", "Lights out"};

    static final int ACCENT = 0xFF1D9BF0;
    static final int PDF_RED = 0xFFE5322D;
    static final int CLAUDE_CLAY = 0xFFD97757;

    final String id;
    final boolean dark;
    final int styleRes;
    final int background, text, muted, border;
    /** Value of x.com's {@code night_mode} cookie that renders the same scheme. */
    final int xNightMode;

    private AppTheme(String id, boolean dark, int styleRes, int background, int text, int muted, int border, int xNightMode) {
        this.id = id;
        this.dark = dark;
        this.styleRes = styleRes;
        this.background = background;
        this.text = text;
        this.muted = muted;
        this.border = border;
        this.xNightMode = xNightMode;
    }

    static AppTheme resolve(Context ctx, String id) {
        if (id == null || SYSTEM.equals(id)) {
            int night = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            id = night == Configuration.UI_MODE_NIGHT_YES ? DARK : LIGHT;
        }
        switch (id) {
            case DIM:
                return new AppTheme(DIM, true, R.style.XTractDark, 0xFF15202B, 0xFFF7F9F9, 0xFF8B98A5, 0xFF38444D, 1);
            case DARK:
                return new AppTheme(DARK, true, R.style.XTractDark, 0xFF000000, 0xFFE7E9EA, 0xFF71767B, 0xFF2F3336, 2);
            default:
                return new AppTheme(LIGHT, false, R.style.XTractLight, 0xFFFFFFFF, 0xFF0F1419, 0xFF536471, 0xFFEFF3F4, 0);
        }
    }
}
