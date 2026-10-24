package com.xtract.app;

import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes the WebView present itself as the phone's Chrome.
 *
 * X refuses to log in from embedded browsers ("Sorry, you are not allowed to log in at this
 * time"). It recognises a WebView by three things: the user-agent string (the "wv" and
 * "Version/4.0" tokens), the X-Requested-With header carrying the app's package name, and the
 * "Android WebView" brand in the Sec-CH-UA client hints. The first is a public API; the other
 * two live behind the WebView support-library glue, which this class reaches the same way
 * androidx.webkit does (public WebView API + reflection into the WebView package), so the app
 * stays dependency-free. Everything here is best effort: on an old WebView the login block
 * may remain, but nothing else breaks.
 */
final class WebViewDisguise {

    private static final String TAG = "XTract";
    private static final String GLUE_UTIL = "org.chromium.support_lib_glue.SupportLibReflectionUtil";
    private static final String FEATURE_XRW = "REQUESTED_WITH_HEADER_ALLOW_LIST";
    private static final String FEATURE_UA_METADATA = "USER_AGENT_METADATA";
    private static final String WEBVIEW_BRAND = "Android WebView";
    private static final String CHROME_BRAND = "Google Chrome";

    // Local mirrors of the support-library boundary interfaces. The glue dispatches by method
    // name and parameter types, so only the signatures have to match, not the interface names.
    interface ProviderFactory {
        String[] getSupportedFeatures();

        InvocationHandler getWebkitToCompatConverter();
    }

    interface Converter {
        InvocationHandler convertSettings(WebSettings settings);
    }

    interface SettingsCompat {
        void setRequestedWithHeaderOriginAllowList(Set<String> allowList);

        Map<String, Object> getUserAgentMetadataMap();

        void setUserAgentMetadataMap(Map<String, Object> metadata);
    }

    private WebViewDisguise() {}

    static void apply(WebView web) {
        WebSettings s = web.getSettings();
        String major = chromeMajor(s.getUserAgentString());
        s.setUserAgentString(chromeUserAgent(major));
        try {
            applyGlue(s, major);
        } catch (Throwable t) {
            Log.w(TAG, "WebView glue not available; X-Requested-With and client hints left as is: " + t);
        }
    }

    /** The reduced user-agent string that Chrome for Android sends. */
    static String chromeUserAgent(String major) {
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + major + ".0.0.0 Mobile Safari/537.36";
    }

    static String chromeMajor(String defaultUa) {
        Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(defaultUa == null ? "" : defaultUa);
        return m.find() ? m.group(1) : "130";
    }

    private static void applyGlue(WebSettings settings, String major) throws Exception {
        ClassLoader cl = WebView.getWebViewClassLoader();
        Class<?> util = Class.forName(GLUE_UTIL, false, cl);
        InvocationHandler factoryHandler = (InvocationHandler) util.getDeclaredMethod("createWebViewProviderFactory").invoke(null);
        ProviderFactory factory = proxy(ProviderFactory.class, factoryHandler);
        String[] features = factory.getSupportedFeatures();
        Converter converter = proxy(Converter.class, factory.getWebkitToCompatConverter());
        SettingsCompat compat = proxy(SettingsCompat.class, converter.convertSettings(settings));

        if (has(features, FEATURE_XRW)) {
            compat.setRequestedWithHeaderOriginAllowList(Collections.<String>emptySet());
        } else {
            Log.w(TAG, "WebView too old to drop the X-Requested-With header");
        }

        if (has(features, FEATURE_UA_METADATA)) {
            compat.setUserAgentMetadataMap(chromeMetadata(compat.getUserAgentMetadataMap(), major));
        } else {
            Log.w(TAG, "WebView too old to change client-hint brands");
        }
    }

    /** The current metadata with the WebView brand swapped for Chrome's, so hints match the UA string. */
    private static Map<String, Object> chromeMetadata(Map<String, Object> current, String major) {
        Map<String, Object> out = new HashMap<>();
        boolean brandsSeen = false;
        if (current != null) {
            for (Map.Entry<String, Object> e : current.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String[][]) {
                    brandsSeen = true;
                    String[][] brands = (String[][]) v;
                    String[][] copy = new String[brands.length][];
                    for (int i = 0; i < brands.length; i++) {
                        copy[i] = brands[i].clone();
                        if (copy[i].length > 0 && WEBVIEW_BRAND.equals(copy[i][0])) copy[i][0] = CHROME_BRAND;
                    }
                    v = copy;
                } else if (WEBVIEW_BRAND.equals(v)) {
                    v = CHROME_BRAND;
                }
                out.put(e.getKey(), v);
            }
        }
        if (!brandsSeen) {
            out.put("BRAND_VERSION_LIST", new String[][]{
                    {"Chromium", major, major + ".0.0.0"},
                    {CHROME_BRAND, major, major + ".0.0.0"},
                    {"Not?A_Brand", "99", "99.0.0.0"}});
        }
        out.put("PLATFORM", "Android");
        out.put("MOBILE", Boolean.TRUE);
        return out;
    }

    private static boolean has(String[] features, String name) {
        if (features == null) return false;
        for (String f : features) if (f.equals(name) || f.startsWith(name + ":")) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        if (handler == null) throw new IllegalStateException("WebView glue returned no " + type.getSimpleName());
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
