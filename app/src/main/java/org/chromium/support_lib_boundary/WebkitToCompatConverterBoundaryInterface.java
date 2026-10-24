package org.chromium.support_lib_boundary;

import android.webkit.WebSettings;

import java.lang.reflect.InvocationHandler;

public interface WebkitToCompatConverterBoundaryInterface {
    InvocationHandler convertSettings(WebSettings webSettings);
}
