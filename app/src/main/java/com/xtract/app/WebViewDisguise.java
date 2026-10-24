package com.xtract.app;

import android.content.pm.PackageInfo;
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
 * time"). A WebView gives itself away in four ways: the user-agent string (the "wv" and
 * "Version/4.0" tokens), the X-Requested-With header carrying the app's package name, the
 * "Android WebView" brand in the Sec-CH-UA client hints, and the missing {@code window.chrome}
 * object that Chrome exposes to pages. The first is a public API; the rest live behind the
 * WebView support-library glue, which this class reaches the same way androidx.webkit does
 * (public WebView API + reflection into the WebView package), so the app stays dependency-free.
 * Everything is best effort: on an old WebView the login block may remain, nothing else breaks.
 * {@link #report()} says what was and wasn't applied.
 */
final class WebViewDisguise {

    /** Bump when the fingerprint changes; the app then starts from clean cookies so X re-evaluates it. */
    static final int VERSION = 2;

    private static final String TAG = "XTract";
    private static final String GLUE_UTIL = "org.chromium.support_lib_glue.SupportLibReflectionUtil";
    private static final String FEATURE_XRW = "REQUESTED_WITH_HEADER_ALLOW_LIST";
    private static final String FEATURE_UA_METADATA = "USER_AGENT_METADATA";
    private static final String FEATURE_DOC_START = "DOCUMENT_START_SCRIPT";
    private static final String WEBVIEW_BRAND = "Android WebView";
    private static final String CHROME_BRAND = "Google Chrome";

    /** Runs before any page script, in every frame: the window.chrome object of Chrome for Android. */
    private static final String CHROME_OBJECT_SCRIPT =
            "(function(){if(window.chrome)return;"
            + "var IS={DISABLED:'disabled',INSTALLED:'installed',NOT_INSTALLED:'not_installed'};"
            + "var RS={CANNOT_RUN:'cannot_run',READY_TO_RUN:'ready_to_run',RUNNING:'running'};"
            + "var t0=Date.now()/1000;"
            + "var c={app:{isInstalled:false,InstallState:IS,RunningState:RS,"
            + "getDetails:function(){return null},getIsInstalled:function(){return false},"
            + "installState:function(cb){if(cb)cb(IS.NOT_INSTALLED)},runningState:function(){return RS.CANNOT_RUN}},"
            + "csi:function(){return{startE:t0*1000,onloadT:Date.now(),pageT:Date.now()-t0*1000,tran:15}},"
            + "loadTimes:function(){return{requestTime:t0,startLoadTime:t0,commitLoadTime:t0,"
            + "finishDocumentLoadTime:t0,finishLoadTime:t0,firstPaintTime:t0,firstPaintAfterLoadTime:0,"
            + "navigationType:'Other',wasFetchedViaSpdy:true,wasNpnNegotiated:true,npnNegotiatedProtocol:'h2',"
            + "wasAlternateProtocolAvailable:false,connectionInfo:'h2'}}};"
            + "Object.defineProperty(window,'chrome',{value:c,writable:true,enumerable:true,configurable:true});"
            + "})();";

    // Local mirrors of the support-library boundary interfaces. The glue dispatches by method
    // name and parameter types, so only the signatures have to match, not the interface names.
    interface ProviderFactory {
        String[] getSupportedFeatures();

        InvocationHandler getWebkitToCompatConverter();

        InvocationHandler createWebView(WebView webView);
    }

    interface Converter {
        InvocationHandler convertSettings(WebSettings settings);
    }

    interface SettingsCompat {
        void setRequestedWithHeaderOriginAllowList(Set<String> allowList);

        Map<String, Object> getUserAgentMetadataMap();

        void setUserAgentMetadataMap(Map<String, Object> metadata);
    }

    interface WebViewCompat {
        InvocationHandler addDocumentStartJavaScript(String script, String[] allowedOriginRules);
    }

    private static final StringBuilder report = new StringBuilder();

    private WebViewDisguise() {}

    static void apply(WebView web) {
        report.setLength(0);
        PackageInfo pkg = WebView.getCurrentWebViewPackage();
        note("WebView: " + (pkg == null ? "unknown" : pkg.packageName + " " + pkg.versionName));
        WebSettings s = web.getSettings();
        String major = chromeMajor(s.getUserAgentString());
        s.setUserAgentString(chromeUserAgent(major));
        note("UA string: Chrome " + major);
        try {
            applyGlue(web, s, major);
        } catch (Throwable t) {
            note("glue FAILED: " + t);
            Log.w(TAG, "WebView glue not available", t);
        }
    }

    /** What was applied, one line per item, for the in-app Browser check page. */
    static String report() {
        return report.toString();
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

    private static void applyGlue(WebView web, WebSettings settings, String major) throws Exception {
        ClassLoader cl = WebView.getWebViewClassLoader();
        Class<?> util = Class.forName(GLUE_UTIL, false, cl);
        InvocationHandler factoryHandler = (InvocationHandler) util.getDeclaredMethod("createWebViewProviderFactory").invoke(null);
        ProviderFactory factory = proxy(ProviderFactory.class, factoryHandler);
        String[] features = factory.getSupportedFeatures();
        note("glue: " + (features == null ? 0 : features.length) + " features");
        Converter converter = proxy(Converter.class, factory.getWebkitToCompatConverter());
        SettingsCompat compat = proxy(SettingsCompat.class, converter.convertSettings(settings));

        if (has(features, FEATURE_XRW)) {
            try {
                compat.setRequestedWithHeaderOriginAllowList(Collections.<String>emptySet());
                note("X-Requested-With: removed");
            } catch (Throwable t) {
                note("X-Requested-With FAILED: " + t);
            }
        } else {
            note("X-Requested-With: WebView too old to remove it");
        }

        if (has(features, FEATURE_UA_METADATA)) {
            try {
                compat.setUserAgentMetadataMap(chromeMetadata(compat.getUserAgentMetadataMap(), major));
                note("client hints: Chrome brands");
            } catch (Throwable t) {
                note("client hints FAILED: " + t);
            }
        } else {
            note("client hints: WebView too old to change brands");
        }

        if (has(features, FEATURE_DOC_START)) {
            try {
                WebViewCompat wv = proxy(WebViewCompat.class, factory.createWebView(web));
                wv.addDocumentStartJavaScript(CHROME_OBJECT_SCRIPT, new String[]{"*"});
                note("window.chrome: provided");
            } catch (Throwable t) {
                note("window.chrome FAILED: " + t);
            }
        } else {
            note("window.chrome: WebView too old to provide it");
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

    private static void note(String line) {
        report.append(line).append('\n');
        Log.i(TAG, line);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        if (handler == null) throw new IllegalStateException("WebView glue returned no " + type.getSimpleName());
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    /** A page that shows what a server and a script see, for diagnosing login problems. */
    static String checkPageHtml(boolean dark) {
        String bg = dark ? "#000" : "#fff", fg = dark ? "#e7e9ea" : "#0f1419";
        return "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{font:14px/1.45 -apple-system,Roboto,sans-serif;margin:16px;background:" + bg + ";color:" + fg + "}"
                + "h2{font-size:18px;margin:0 0 4px}p{margin:0 0 14px;opacity:.7}"
                + "pre{white-space:pre-wrap;word-break:break-all;font-size:12px;background:rgba(128,128,128,.15);padding:10px;border-radius:8px}</style>"
                + "</head><body><h2>Browser check</h2><p>Screenshot this if X refuses to log in.</p>"
                + "<pre id='o'>…</pre><script>"
                + "var o={};"
                + "o.applied=" + jsString(report()) + ".split('\\n').filter(Boolean);"
                + "o.userAgent=navigator.userAgent;"
                + "o.userAgentData=navigator.userAgentData?{brands:navigator.userAgentData.brands,mobile:navigator.userAgentData.mobile,platform:navigator.userAgentData.platform}:'none';"
                + "o.windowChrome=typeof window.chrome;"
                + "o.cookies=navigator.cookieEnabled;"
                + "function show(){document.getElementById('o').textContent=JSON.stringify(o,null,2)}"
                + "function pick(h){var k={};for(var n in h){if(/^(x-requested-with|sec-ch-ua|sec-ch-ua-mobile|sec-ch-ua-platform|user-agent)$/i.test(n))k[n]=h[n]}return k}"
                + "show();"
                + "fetch('https://httpbingo.org/headers').then(function(r){return r.json()})"
                + ".then(function(j){o.serverSees=pick(j.headers);show()})"
                + ".catch(function(e){fetch('https://httpbin.org/headers').then(function(r){return r.json()})"
                + ".then(function(j){o.serverSees=pick(j.headers);show()})"
                + ".catch(function(e2){o.serverSees='could not reach an echo service: '+e2;show()})});"
                + "</script></body></html>";
    }

    private static String jsString(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (char c : s.toCharArray()) {
            if (c == '\'' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '<') sb.append("\\x3c");
            else sb.append(c);
        }
        return sb.append('\'').toString();
    }
}
