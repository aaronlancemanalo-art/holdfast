package com.aaron.riyalledger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Riyal Ledger, the budget and savings forecast app, as its own Android app.
 *
 * The web app is bundled into the APK at build time (assets/ledger, copied from the
 * riyal-ledger repo by the GitHub workflow) and served from a fixed https origin so its
 * ES modules and IndexedDB storage work. It never loads anything from the internet and
 * refuses to navigate anywhere else, so it can't be used as a browser.
 */
public class LedgerActivity extends Activity {

    private static final String HOST = "app.riyalledger.local";
    private static final String ORIGIN = "https://" + HOST + "/";
    private static final int REQ_SAVE = 41;
    private static final int REQ_OPEN = 42;

    /** Fingerprint of the latest ledger build, published with every Holdfast release. */
    private static final String HASH_URL =
            "https://github.com/aaronlancemanalo-art/holdfast/releases/latest/download/ledger-hash.txt";
    /** Holdfast does the download and install, the same way it updates itself. */
    private static final String HOLDFAST_PKG = "com.aaron.holdfast";
    private static final String ACTION_UPDATE_LEDGER = "com.aaron.holdfast.action.UPDATE_LEDGER";
    private static final long CHECK_EVERY_MS = 60L * 60 * 1000;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private byte[] pendingSave;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#8A1538"));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(s.getUserAgentString() + " HoldfastLedger");

        web.addJavascriptInterface(new Bridge(), "HoldfastLedger");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                // Stay inside the ledger. Nothing else opens, here or in another app.
                return !HOST.equals(req.getUrl().getHost());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) {
                    // Block every outside request outright.
                    return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked",
                            new HashMap<>(), new ByteArrayInputStream(new byte[0]));
                }
                String path = u.getPath() == null || u.getPath().equals("/") ? "/index.html" : u.getPath();
                try {
                    InputStream in = getAssets().open("ledger" + path);
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-cache");
                    return new WebResourceResponse(mime(path), "utf-8", 200, "OK", headers, in);
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not found",
                            new HashMap<>(), new ByteArrayInputStream(new byte[0]));
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
                startActivityForResult(i, REQ_OPEN);
                return true;
            }
        });
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(ORIGIN + "index.html");
    }

    private static String mime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "text/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    /** Called from the page to save a backup or CSV; Android asks where to put it. */
    private class Bridge {
        @JavascriptInterface
        public void saveFile(String name, String mimeType, String text) {
            pendingSave = text.getBytes(StandardCharsets.UTF_8);
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType);
                i.putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(i, REQ_SAVE);
            });
        }

        @JavascriptInterface
        public boolean available() { return true; }

        /** Settings > Data > "Check for updates" in the ledger. */
        @JavascriptInterface
        public void checkForUpdates() { runOnUiThread(() -> checkForUpdate(true)); }

        /** The phone app's build number, shown in the ledger's footer and Settings. */
        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public String build() {
            try {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                long v = android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                return String.valueOf(v);
            } catch (Exception e) {
                return "";
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OPEN) {
            Uri[] result = (res == RESULT_OK && data != null && data.getData() != null) ? new Uri[]{data.getData()} : null;
            if (fileCallback != null) fileCallback.onReceiveValue(result);
            fileCallback = null;
        } else if (req == REQ_SAVE) {
            if (res == RESULT_OK && data != null && data.getData() != null && pendingSave != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    out.write(pendingSave);
                    toast("Saved");
                } catch (Exception e) {
                    toast("Couldn't save the file");
                }
            }
            pendingSave = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkForUpdate(false);
    }

    /**
     * Compares this build's fingerprint with the latest release's. Only a real change to the
     * ledger counts, so a Holdfast-only rebuild doesn't prompt. Checks at most hourly on its own;
     * the ledger's Settings > Data has a button to check right away.
     * This runs outside the WebView; the page itself still can't reach the internet.
     */
    private void checkForUpdate(boolean manual) {
        SharedPreferences prefs = getSharedPreferences("updates", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && now - prefs.getLong("lastCheck", 0) < CHECK_EVERY_MS) return;
        prefs.edit().putLong("lastCheck", now).apply();
        new Thread(() -> {
            String mine = readAsset("build-hash.txt");
            String latest = fetch(HASH_URL);
            if (mine.isEmpty() || latest.isEmpty() || mine.equals(latest)) {
                if (manual) runOnUiThread(() -> toast(latest.isEmpty()
                        ? "Couldn't check for updates. Try again with internet on."
                        : "Riyal Ledger is up to date."));
                return;
            }
            if (!manual && latest.equals(prefs.getString("skipped", ""))) return;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                new AlertDialog.Builder(this)
                        .setTitle("Update ready")
                        .setMessage("A new version of Riyal Ledger is ready. Holdfast will download and install it. "
                                + "Your entries stay as they are.")
                        .setPositiveButton("Update", (d, w) -> askHoldfast())
                        .setNegativeButton("Later", (d, w) -> prefs.edit().putString("skipped", latest).apply())
                        .show();
            });
        }).start();
    }

    private void askHoldfast() {
        try {
            startActivity(new Intent(ACTION_UPDATE_LEDGER).setPackage(HOLDFAST_PKG)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("Holdfast isn't installed, so it can't update Riyal Ledger.");
        }
    }

    private String readAsset(String name) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open(name)))) {
            String l = r.readLine();
            return l == null ? "" : l.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return "";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String l = r.readLine();
                return l == null ? "" : l.trim();
            }
        } catch (Exception e) {
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        // The ledger closes its own dialogs; back otherwise leaves the app.
        web.evaluateJavascript("(function(){var d=document.querySelector('dialog[open]');if(d){d.close();return true}return false})()",
                v -> { if (!"true".equals(v)) LedgerActivity.super.onBackPressed(); });
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
