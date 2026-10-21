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
            if (id == savedDownloadId()) installIfReady();
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
            root.addView(text("ON: every browser is blocked, including ones you install later. "
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
                root.addView(button("Download update", v -> startDownload()));
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
    private void startDownload() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            rules.openInstallPermissionWindow();
            Toast.makeText(this, "Allow Holdfast to install updates, then come back and tap Download again.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(APK_URL))
                .setTitle("Holdfast update")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS,
                        "holdfast-" + remoteVersion + "-" + System.currentTimeMillis() + ".apk");
        long id = dm.enqueue(r);
        getPreferences(MODE_PRIVATE).edit().putLong("downloadId", id).apply();
        downloading = true;
        render();
    }

    private long savedDownloadId() {
        return getPreferences(MODE_PRIVATE).getLong("downloadId", -1);
    }

    /** If the last update download finished, open the installer and forget the download. */
    private void installIfReady() {
        long id = savedDownloadId();
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
        getPreferences(MODE_PRIVATE).edit().remove("downloadId").apply();
        downloading = false;
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

    @SuppressWarnings("deprecation")
    private long installedVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
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
