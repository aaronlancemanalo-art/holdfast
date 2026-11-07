package com.aaron.holdfast;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Environment;
import android.widget.Toast;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class MainActivity extends Activity {

    private static final String REPO = "aaronlancemanalo-art/holdfast";
    private static final String VERSION_URL =
            "https://github.com/" + REPO + "/releases/latest/download/version.txt";
    private static final String APK_URL =
            "https://github.com/" + REPO + "/releases/latest/download/holdfast.apk";
    /** Riyal Ledger, Aaron's budget app: built with Holdfast, signed with the same key, installed by Holdfast. */
    static final String LEDGER_PKG = "com.aaron.riyalledger";
    private static final String LEDGER_APK_URL =
            "https://github.com/" + REPO + "/releases/latest/download/riyal-ledger.apk";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private Rules rules;
    private LinearLayout root;
    private int remoteVersion = -1;
    private String updateError;
    private long lastCheck = 0;
    private boolean downloading = false;

    /** Fires when Android's downloader finishes the update APK. */
    private final BroadcastReceiver downloadDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            SharedPreferences prefs = getPreferences(MODE_PRIVATE);
            if (id >= 0 && (id == prefs.getLong("downloadId", -1) || id == prefs.getLong("ledgerDownloadId", -1))) {
                installIfReady();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rules = new Rules(this);
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);
        setContentView(sv);
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadDone, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(downloadDone, f);
        handleUpdateRequest(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleUpdateRequest(intent);
    }

    static final String ACTION_UPDATE_LEDGER = "com.aaron.holdfast.action.UPDATE_LEDGER";
    /** First ledger build that checks for its own updates. Older installs get a one-time button. */
    private static final int LEDGER_SELF_UPDATE_BUILD = 18;

    /**
     * Riyal Ledger asks for its update here. Holdfast downloads riyal-ledger.apk from its own
     * latest release and opens the installer, the same path as its own updates. Only the ledger
     * is ever downloaded, and Android only installs it over the existing app if it carries the
     * same signature.
     */
    private void handleUpdateRequest(Intent i) {
        if (i == null || !ACTION_UPDATE_LEDGER.equals(i.getAction())) return;
        setIntent(new Intent(this, MainActivity.class));
        if (versionOf(LEDGER_PKG) < 0) return;
        if (!downloading) startDownload(true);
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(downloadDone);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rules.tick();
        render();
        checkForUpdate(false);
        installIfReady();
    }

    private void render() {
        root.removeAllViews();

        TextView title = text("Holdfast", 30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView sub = text("Blocking is instant. Unblocking waits "
                + (Rules.DELAY_MS / 3_600_000) + " hours.", 14);
        sub.setAlpha(0.7f);
        root.addView(sub);
        TextView count = text("Blocked " + rules.blocksToday() + " times today", 14);
        count.setAlpha(0.7f);
        root.addView(count);

        // One-time: ledger builds before LEDGER_SELF_UPDATE_BUILD can't check for updates
        // themselves, so offer the update here once. Disappears after that.
        long lv = versionOf(LEDGER_PKG);
        if (lv >= 0 && lv < LEDGER_SELF_UPDATE_BUILD) {
            root.addView(button(downloading ? "Downloading..." : "Update Riyal Ledger (adds in-app updates)",
                    v -> { if (!downloading) startDownload(true); }));
        }

        // ----- setup status -----
        header("Setup");
        boolean a11y = isAccessibilityOn();
        boolean running = BlockerService.instance != null;
        boolean admin = isAdminOn();
        if (a11y && running) {
            root.addView(text("Blocker: ON and running", 16));
        } else if (a11y) {
            TextView warn = text("Blocker: switched on but NOT running. Nothing is being blocked. "
                    + "Turn Holdfast off and on again in Accessibility, and set its battery to Unrestricted.", 16);
            warn.setTextColor(0xFFFF6B6B);
            root.addView(warn);
            root.addView(button("Open Accessibility settings",
                    v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        } else {
            root.addView(text("Blocker: OFF. Turn on Holdfast under Accessibility > Installed apps.", 16));
            root.addView(button("Open Accessibility settings",
                    v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        }
        if (admin) {
            root.addView(text("Uninstall protection: ON", 16));
        } else {
            root.addView(text("Uninstall protection: OFF", 16));
            root.addView(button("Activate uninstall protection", v -> activateAdmin()));
        }

        String err = rules.lastError();
        if (err != null) {
            TextView e = text("Last error: " + err, 12);
            e.setAlpha(0.6f);
            root.addView(e);
        }

        // ----- apps -----
        header("Blocked apps");
        Set<String> apps = new TreeSet<>(rules.getApps());
        if (apps.isEmpty()) root.addView(text("None", 14));
        for (String pkg : apps) {
            boolean pending = rules.isPending(Rules.TYPE_APP, pkg);
            root.addView(row(label(pkg) + "\n" + pkg,
                    pending ? null : "Unblock (24h)",
                    v -> confirmRemoval(Rules.TYPE_APP, pkg, label(pkg))));
        }
        root.addView(button("+ Block an app", v -> pickApp()));

        // ----- sites -----
        header("Blocked sites (any browser)");
        Set<String> sites = new TreeSet<>(rules.getSites());
        if (sites.isEmpty()) root.addView(text("None", 14));
        for (String s : sites) {
            boolean pending = rules.isPending(Rules.TYPE_SITE, s);
            root.addView(row(s, pending ? null : "Unblock (24h)",
                    v -> confirmRemoval(Rules.TYPE_SITE, s, s)));
        }
        LinearLayout addSite = new LinearLayout(this);
        addSite.setOrientation(LinearLayout.HORIZONTAL);
        EditText input = new EditText(this);
        input.setHint("e.g. instagram.com");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        addSite.addView(input, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        addSite.addView(button("Block", v -> {
            rules.blockSite(input.getText().toString());
            render();
        }));
        root.addView(addSite);

        // ----- all browsers -----
        header("All browsers");
        if (rules.allBrowsersBlocked()) {
            root.addView(text("ON: every browser is blocked, including ones you install later, plus search "
                    + "apps with built-in web viewers (Google, Google Go, Bing...). "
                    + "Other Reddit apps are blocked while reddit.com is.", 14));
            if (!rules.isPending(Rules.TYPE_BROWSERS, "")) {
                root.addView(button("Allow browsers (24h)",
                        v -> confirmRemoval(Rules.TYPE_BROWSERS, "", "all browsers")));
            }
        } else {
            root.addView(text("OFF: browsers are only blocked if listed above.", 14));
            root.addView(button("Block all browsers now", v -> {
                rules.blockAllBrowsers();
                render();
            }));
        }

        // ----- installs from files -----
        header("Installs from files");
        if (rules.sideloadBlocked()) {
            root.addView(text("ON: installing apps from downloaded files (APKs) is blocked, from any app. "
                    + "Any new app that didn't come from the Play Store or Galaxy Store won't open. "
                    + "Play Store installs and Holdfast's own updates still work.", 14));
            if (!rules.isPending(Rules.TYPE_SIDELOAD, "")) {
                root.addView(button("Allow installs from files (24h)",
                        v -> confirmRemoval(Rules.TYPE_SIDELOAD, "", "installs from files")));
            }
        } else {
            root.addView(text("OFF: apps can be installed from files.", 14));
            root.addView(button("Block installs from files now", v -> {
                rules.blockSideload();
                render();
            }));
        }

        // ----- guard -----
        header("Tamper protection");
        TextView g = text(rules.isGuardOn()
                ? "ON: Holdfast's Settings pages, its uninstall dialog, Developer options and Safe mode are blocked."
                : "OFF: Holdfast can be disabled or uninstalled.", 14);
        root.addView(g);
        if (rules.isGuardOn()) {
            if (!rules.isPending(Rules.TYPE_GUARD, "")) {
                root.addView(button("Turn off (24h)",
                        v -> confirmRemoval(Rules.TYPE_GUARD, "", "tamper protection")));
            }
        } else {
            root.addView(button("Turn on now", v -> {
                rules.guardOn();
                render();
            }));
        }

        // ----- pending -----
        List<Rules.Pending> pending = rules.getPending();
        header("Waiting to apply");
        if (pending.isEmpty()) root.addView(text("Nothing. Good.", 14));
        for (int i = 0; i < pending.size(); i++) {
            Rules.Pending p = pending.get(i);
            String what = Rules.TYPE_GUARD.equals(p.type) ? "Turn off tamper protection"
                    : Rules.TYPE_BROWSERS.equals(p.type) ? "Allow browsers"
                    : Rules.TYPE_SIDELOAD.equals(p.type) ? "Allow installs from files"
                    : "Unblock " + (Rules.TYPE_APP.equals(p.type) ? label(p.value) : p.value);
            final int idx = i;
            root.addView(row(what + "\nin " + duration(p.remainingMs), "Cancel", v -> {
                rules.cancelPending(idx);
                render();
            }));
        }

        // ----- updates -----
        header("Updates");
        long installed = installedVersion();
        if (remoteVersion > installed) {
            root.addView(text("Build " + remoteVersion + " is ready (you have " + installed + ").", 15));
            if (downloading) {
                root.addView(text("Downloading... the install screen opens by itself.", 14));
            } else {
                root.addView(button("Download update", v -> startDownload(false)));
            }
            TextView hint = text("Holdfast downloads it itself, no browser needed. Tap Update when asked. "
                    + "Your rules are kept.", 12);
            hint.setAlpha(0.6f);
            root.addView(hint);
        } else if (remoteVersion > 0) {
            root.addView(text("Up to date (build " + installed + ").", 15));
        } else if (updateError != null) {
            root.addView(text("Couldn't check for updates (" + updateError + "). Build "
                    + installed + " installed.", 14));
        } else {
            root.addView(text("Checking... (build " + installed + " installed)", 14));
        }
        root.addView(button("Check now", v -> checkForUpdate(true)));

        TextView ver = text("\nSafe Mode or a factory reset can still get around this. "
                + "That's the honest limit of any app.", 12);
        ver.setAlpha(0.5f);
        root.addView(ver);
    }

    // ---------- actions ----------

    private void confirmRemoval(String type, String value, String name) {
        new AlertDialog.Builder(this)
                .setTitle("Unblock " + name + "?")
                .setMessage("This will take effect in 24 hours. You can cancel it any time before then.")
                .setPositiveButton("Start the 24h wait", (d, w) -> {
                    rules.requestRemoval(type, value);
                    render();
                })
                .setNegativeButton("Never mind", null)
                .show();
    }

    private void pickApp() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
        final List<String[]> items = new ArrayList<>();
        Set<String> already = rules.getApps();
        for (ResolveInfo ri : infos) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || already.contains(pkg)) continue;
            boolean dup = false;
            for (String[] it : items) if (it[1].equals(pkg)) dup = true;
            if (!dup) items.add(new String[]{ri.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(items, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[items.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = items.get(i)[0];
        new AlertDialog.Builder(this)
                .setTitle("Block which app?")
                .setItems(labels, (d, which) -> {
                    rules.blockApp(items.get(which)[1]);
                    render();
                })
                .show();
    }

    private void activateAdmin() {
        rules.openSetupWindow();
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        new ComponentName(this, AdminReceiver.class))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Stops Holdfast from being uninstalled on impulse.");
        startActivity(i);
    }

    // ---------- updates ----------

    private void checkForUpdate(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && lastCheck != 0 && now - lastCheck < 10 * 60_000) return;
        lastCheck = now;
        new Thread(() -> {
            int v = -1;
            String err = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(VERSION_URL).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setInstanceFollowRedirects(true);
                int code = c.getResponseCode();
                if (code == 200) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    String line = r.readLine();
                    r.close();
                    v = Integer.parseInt(line == null ? "" : line.trim());
                } else {
                    err = "HTTP " + code;
                }
            } catch (Exception e) {
                err = e.getClass().getSimpleName();
            } finally {
                if (c != null) c.disconnect();
            }
            final int fv = v;
            final String fe = err;
            runOnUiThread(() -> {
                remoteVersion = fv;
                updateError = fe;
                if (!isFinishing()) render();
            });
        }).start();
    }

    /** Downloads the update with Android's own downloader, so no browser is needed. */
    private void startDownload(boolean ledger) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            rules.openInstallPermissionWindow();
            Toast.makeText(this, "Allow Holdfast to install updates, then come back and tap Download again.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(ledger ? LEDGER_APK_URL : APK_URL))
                .setTitle(ledger ? "Riyal Ledger" : "Holdfast update")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS,
                        (ledger ? "riyal-ledger-" : "holdfast-") + remoteVersion + "-" + System.currentTimeMillis() + ".apk");
        long id = dm.enqueue(r);
        getPreferences(MODE_PRIVATE).edit().putLong(ledger ? "ledgerDownloadId" : "downloadId", id).apply();
        downloading = true;
        render();
    }

    /** If the last Holdfast or Riyal Ledger download finished, open the installer and forget it. */
    private void installIfReady() {
        downloading = false;
        installIfReady("downloadId");
        installIfReady("ledgerDownloadId");
    }

    /** True if the APK at uri is package `expected` and signed with the same key as Holdfast. */
    @SuppressWarnings("deprecation")
    private boolean isOwnSignedApk(Uri uri, String expected) {
        java.io.File tmp = new java.io.File(getCacheDir(), "verify.apk");
        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
             java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
            if (in == null) return false;
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            return false;
        }
        try {
            PackageManager pm = getPackageManager();
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo apk = pm.getPackageArchiveInfo(tmp.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
                PackageInfo me = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                if (apk == null || apk.signingInfo == null || !expected.equals(apk.packageName)) return false;
                return java.util.Arrays.equals(apk.signingInfo.getApkContentsSigners(), me.signingInfo.getApkContentsSigners());
            } else {
                PackageInfo apk = pm.getPackageArchiveInfo(tmp.getPath(), PackageManager.GET_SIGNATURES);
                PackageInfo me = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
                if (apk == null || !expected.equals(apk.packageName)) return false;
                return java.util.Arrays.equals(apk.signatures, me.signatures);
            }
        } catch (Exception e) {
            return false;
        } finally {
            tmp.delete();
        }
    }

    private void installIfReady(String key) {
        long id = getPreferences(MODE_PRIVATE).getLong(key, -1);
        if (id < 0) return;
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        int status = -1;
        Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
        if (c != null) {
            if (c.moveToFirst()) {
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            }
            c.close();
        }
        if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING
                || status == DownloadManager.STATUS_PAUSED) {
            downloading = true;
            return;
        }
        getPreferences(MODE_PRIVATE).edit().remove(key).apply();
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            updateError = "download failed, try again";
            render();
            return;
        }
        Uri uri = dm.getUriForDownloadedFile(id);
        if (uri == null) {
            updateError = "downloaded file missing";
            render();
            return;
        }
        // Only our own APKs get the install window: check the file is the expected app and is
        // signed with Holdfast's key before letting any installer screen through.
        String expected = "ledgerDownloadId".equals(key) ? LEDGER_PKG : getPackageName();
        if (!isOwnSignedApk(uri, expected)) {
            updateError = "the download failed a safety check, so it wasn't installed";
            render();
            return;
        }
        rules.openSelfUpdateWindow();
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(install);
        } catch (Exception e) {
            updateError = "couldn't open installer";
            render();
        }
    }

    private long installedVersion() {
        return versionOf(getPackageName());
    }

    @SuppressWarnings("deprecation")
    private long versionOf(String pkg) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    // ---------- status ----------

    private boolean isAccessibilityOn() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName() + "/");
    }

    private boolean isAdminOn() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(new ComponentName(this, AdminReceiver.class));
    }

    // ---------- view helpers ----------

    private void header(String s) {
        TextView t = text(s, 20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(24), 0, dp(8));
        root.addView(t);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(String label, String action, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(label, 15);
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null) {
            r.addView(button(action, l));
        } else {
            TextView p = text("pending", 13);
            p.setAlpha(0.6f);
            r.addView(p);
        }
        return r;
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static String duration(long ms) {
        long mins = Math.max(1, ms / 60_000);
        long h = mins / 60, m = mins % 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
