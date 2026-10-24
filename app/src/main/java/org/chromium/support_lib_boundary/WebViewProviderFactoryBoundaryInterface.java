package org.chromium.support_lib_boundary;

import android.webkit.WebView;

import java.lang.reflect.InvocationHandler;

public interface WebViewProviderFactoryBoundaryInterface {
    InvocationHandler createWebView(WebView webview);

    InvocationHandler getWebkitToCompatConverter();

    String[] getSupportedFeatures();
}
