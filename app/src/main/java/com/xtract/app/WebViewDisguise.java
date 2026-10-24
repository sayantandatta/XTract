package com.xtract.app;

import android.content.pm.PackageInfo;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.chromium.support_lib_boundary.WebSettingsBoundaryInterface;
import org.chromium.support_lib_boundary.WebViewProviderBoundaryInterface;
import org.chromium.support_lib_boundary.WebViewProviderFactoryBoundaryInterface;
import org.chromium.support_lib_boundary.WebkitToCompatConverterBoundaryInterface;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collections;
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
 * (public WebView API + reflection into the WebView package, through the boundary interfaces
 * in org.chromium.support_lib_boundary), so the app stays dependency-free.
 * Everything is best effort: on an old WebView the login block may remain, nothing else breaks.
 * {@link #report()} says what was and wasn't applied.
 */
final class WebViewDisguise {

    /** Bump when the fingerprint changes; the app then starts from clean cookies so X re-evaluates it. */
    static final int VERSION = 4;

    private static final String TAG = "XTract";
    private static final String GLUE_UTIL = "org.chromium.support_lib_glue.SupportLibReflectionUtil";
    private static final String WEBVIEW_BRAND = "Android WebView";
    private static final String CHROME_BRAND = "Google Chrome";

    /** Runs before any page script, in every frame: the window.chrome object of Chrome for Android. */
    private static final String CHROME_OBJECT_SCRIPT =
            "(function(){try{if(window.chrome)return;"
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
            + "}catch(e){}})();";

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
            applyGlue(web, s);
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

    private static void applyGlue(WebView web, WebSettings settings) throws Exception {
        ClassLoader cl = WebView.getWebViewClassLoader();
        Class<?> util = Class.forName(GLUE_UTIL, false, cl);
        InvocationHandler factoryHandler = (InvocationHandler) util.getDeclaredMethod("createWebViewProviderFactory").invoke(null);
        WebViewProviderFactoryBoundaryInterface factory = proxy(WebViewProviderFactoryBoundaryInterface.class, factoryHandler);
        String[] features = factory.getSupportedFeatures();
        note("glue: " + (features == null ? 0 : features.length) + " features");
        WebkitToCompatConverterBoundaryInterface converter =
                proxy(WebkitToCompatConverterBoundaryInterface.class, factory.getWebkitToCompatConverter());
        WebSettingsBoundaryInterface compat = proxy(WebSettingsBoundaryInterface.class, converter.convertSettings(settings));

        // Attempt each call rather than gating on a feature-name string (those names vary by
        // WebView build): if the method exists it works, otherwise it throws and we report why.
        try {
            compat.setRequestedWithHeaderOriginAllowList(Collections.<String>emptySet());
            note("X-Requested-With: removed");
        } catch (Throwable t) {
            note("X-Requested-With FAILED: " + cause(t));
        }

        try {
            Map<String, Object> md = compat.getUserAgentMetadataMap();
            int renamed = renameBrandInPlace(md);
            // Pass the glue's own map back untouched except for the brand strings, so the value
            // types stay exactly what the marshaller expects.
            compat.setUserAgentMetadataMap(md);
            note("client hints: Chrome brands (" + renamed + " renamed)");
        } catch (Throwable t) {
            note("client hints FAILED: " + cause(t));
        }

        try {
            WebViewProviderBoundaryInterface wv = proxy(WebViewProviderBoundaryInterface.class, factory.createWebView(web));
            wv.addDocumentStartJavaScript(CHROME_OBJECT_SCRIPT, new String[]{"*"});
            note("window.chrome: provided");
        } catch (Throwable t) {
            note("window.chrome FAILED: " + cause(t));
        }
    }

    /** Renames the "Android WebView" brand to Chrome inside the glue's own metadata map. */
    private static int renameBrandInPlace(Map<String, Object> md) {
        if (md == null) return 0;
        int renamed = 0;
        for (Object v : md.values()) {
            if (v instanceof String[][]) {
                for (String[] brand : (String[][]) v) {
                    if (brand.length > 0 && WEBVIEW_BRAND.equals(brand[0])) {
                        brand[0] = CHROME_BRAND;
                        renamed++;
                    }
                }
            } else if (v instanceof Object[]) {
                for (Object b : (Object[]) v) {
                    if (b instanceof String[]) {
                        String[] brand = (String[]) b;
                        if (brand.length > 0 && WEBVIEW_BRAND.equals(brand[0])) {
                            brand[0] = CHROME_BRAND;
                            renamed++;
                        }
                    }
                }
            }
        }
        return renamed;
    }

    /** The message of the deepest cause, so a wrapped "Reflection failed" reports the real reason. */
    private static String cause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String msg = c.getClass().getSimpleName();
        if (c.getMessage() != null) msg += ": " + c.getMessage();
        return msg;
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
