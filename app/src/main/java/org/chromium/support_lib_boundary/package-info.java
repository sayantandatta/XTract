/**
 * Minimal copies of the WebView support-library boundary interfaces (normally shipped inside
 * androidx.webkit). The WebView resolves calls by the interface's fully-qualified name in its
 * own class loader, so these must live in this exact package with these exact names and method
 * signatures. Only the methods XTract uses are declared. Remove this package if androidx.webkit
 * is ever added, since it ships the full versions.
 */
package org.chromium.support_lib_boundary;
