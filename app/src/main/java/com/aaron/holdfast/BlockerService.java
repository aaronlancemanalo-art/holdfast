package com.aaron.holdfast;

import android.accessibilityservice.AccessibilityService;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Watches which app is on screen.
 *  1. Blocked app in front        -> kick to home + show block screen.
 *  2. Browser URL bar on a blocked site -> same.
 *  3. Tamper guard: Settings pages about this app, or the uninstall dialog -> same.
 */
public class BlockerService extends AccessibilityService {

    private static final String APP_NAME = "holdfast";
    private static final int MAX_NODES = 800;

    /** Settings-type apps where someone could disable or uninstall this blocker. */
    private static final Set<String> SETTINGS_PKGS = new HashSet<>(Arrays.asList(
            "com.android.settings",
            "com.samsung.accessibility",
            "com.samsung.android.lool",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller"));

    private static final Set<String> INSTALLER_PKGS = new HashSet<>(Arrays.asList(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller"));

    /** Where the power menu (with its Safe mode option) lives. */
    private static final Set<String> SYSTEM_UI_PKGS = new HashSet<>(Arrays.asList(
            "com.android.systemui",
            "com.samsung.android.globalactions"));

    /** Known URL-bar view ids per browser. Unknown browsers fall back to a generic scan. */
    private static final Map<String, String[]> URL_BAR_IDS = new HashMap<>();

    static {
        URL_BAR_IDS.put("com.sec.android.app.sbrowser", new String[]{
                "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                "com.sec.android.app.sbrowser:id/custom_tab_toolbar_url_bar_text"});
        URL_BAR_IDS.put("com.android.chrome", new String[]{"com.android.chrome:id/url_bar"});
        URL_BAR_IDS.put("com.chrome.beta", new String[]{"com.chrome.beta:id/url_bar"});
        URL_BAR_IDS.put("com.brave.browser", new String[]{"com.brave.browser:id/url_bar"});
        URL_BAR_IDS.put("com.microsoft.emmx", new String[]{"com.microsoft.emmx:id/url_bar"});
        URL_BAR_IDS.put("com.kiwibrowser.browser", new String[]{"com.kiwibrowser.browser:id/url_bar"});
        URL_BAR_IDS.put("com.vivaldi.browser", new String[]{"com.vivaldi.browser:id/url_bar"});
        URL_BAR_IDS.put("org.mozilla.firefox", new String[]{
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view"});
        URL_BAR_IDS.put("org.mozilla.focus", new String[]{
                "org.mozilla.focus:id/mozac_browser_toolbar_url_view"});
        URL_BAR_IDS.put("com.duckduckgo.mobile.android", new String[]{
                "com.duckduckgo.mobile.android:id/omnibarTextInput"});
        URL_BAR_IDS.put("com.opera.browser", new String[]{"com.opera.browser:id/url_field"});
        URL_BAR_IDS.put("com.opera.mini.native", new String[]{"com.opera.mini.native:id/url_field"});
        URL_BAR_IDS.put("com.google.android.googlequicksearchbox", new String[0]);
    }

    /** Set while Android actually has the service connected. The app reads this to show real status. */
    static volatile BlockerService instance;

    private static final String OFFICIAL_REDDIT = "com.reddit.frontpage";

    private Rules rules;
    private Set<String> browserPkgs = new HashSet<>();
    private long browsersCheckedAt = 0;
    private final Map<String, Boolean> redditClientCache = new HashMap<>();
    private long lastBlockAt = 0;
    private final Map<String, Long> lastScan = new HashMap<>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        rules = new Rules(this);
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Never let one bad event crash the service: Android quietly disables a crashing service.
        try {
            handle(event);
        } catch (Throwable t) {
            if (rules != null) rules.recordError(t);
        }
    }

    private void handle(AccessibilityEvent event) {
        if (rules == null || event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (pkg.equals(getPackageName())) return;

        rules.tick();

        // 1. Blocked apps
        if (rules.getApps().contains(pkg)) {
            block(appLabel(pkg) + " is blocked.");
            return;
        }

        // 1b. Any browser, including ones installed after the rules were set
        if (rules.allBrowsersBlocked() && browserPackages().contains(pkg)) {
            block(appLabel(pkg) + " is blocked (all browsers are).");
            return;
        }

        // 1c. Other Reddit apps, while reddit.com is blocked
        if (rules.getSites().contains("reddit.com") && isRedditClient(pkg)) {
            block(appLabel(pkg) + " is a Reddit app, and Reddit is blocked.");
            return;
        }

        // Content-change events fire constantly; scan each app at most every 400 ms.
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            long now = SystemClock.uptimeMillis();
            Long last = lastScan.get(pkg);
            if (last != null && now - last < 400) return;
            lastScan.put(pkg, now);
        }

        boolean isSettings = SETTINGS_PKGS.contains(pkg);
        boolean isInstaller = INSTALLER_PKGS.contains(pkg);
        boolean isBrowser = URL_BAR_IDS.containsKey(pkg) || pkg.contains("browser");
        boolean isSystemUi = SYSTEM_UI_PKGS.contains(pkg);
        if (!isSettings && !isInstaller && !isBrowser && !isSystemUi) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (isSystemUi) {
            // Only look at system UI's own windows (the power menu), not whatever app is underneath.
            CharSequence rootPkg = root.getPackageName();
            if (rootPkg == null || !SYSTEM_UI_PKGS.contains(rootPkg.toString())) return;
        }

        // 3. Tamper guard
        if (rules.isGuardOn() && !setupAllowed()) {
            // The only Holdfast settings page let through: "Install unknown apps", for 90 s after
            // tapping Download update. Pages with other controls (force stop etc.) stay blocked.
            boolean installPermissionPage = rules.inInstallPermissionWindow()
                    && treeContains(root, "this source")
                    && !treeContains(root, "force stop")
                    && !treeContains(root, "uninstall")
                    && !treeContains(root, "clear data")
                    && !treeContains(root, "accessibility");
            if (isSettings && (treeContains(root, "usb debugging")
                    || treeContains(root, "wireless debugging"))) {
                block("Developer options are protected while tamper protection is on.");
                return;
            }
            if (isSystemUi && treeHasExactText(root, "safe mode")) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                block("Safe mode is blocked while tamper protection is on.");
                return;
            }
            if (isSettings && !installPermissionPage && treeContains(root, APP_NAME)) {
                block("Holdfast's settings are protected. Turning protection off takes 24 hours.");
                return;
            }
            if (isInstaller && treeContains(root, APP_NAME) && treeContains(root, "uninstall")) {
                block("Uninstalling Holdfast is blocked. Turn protection off in the app first (24 hours).");
                return;
            }
        }

        // 2. Blocked sites
        if (isBrowser) {
            String hit = blockedSiteOnScreen(root, pkg);
            if (hit != null) block(hit + " is blocked.");
        }
    }

    @Override
    public void onInterrupt() {
    }

    // ---------- blocking ----------

    private void block(String reason) {
        long now = SystemClock.uptimeMillis();
        if (now - lastBlockAt < 800) return;
        lastBlockAt = now;
        rules.countBlock();
        performGlobalAction(GLOBAL_ACTION_HOME);
        Intent i = new Intent(this, BlockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(BlockActivity.EXTRA_REASON, reason);
        startActivity(i);
    }

    /** During first setup the admin-activation screen must be reachable. */
    private boolean setupAllowed() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        boolean adminActive = dpm != null
                && dpm.isAdminActive(new ComponentName(this, AdminReceiver.class));
        return !adminActive && rules.inSetupWindow();
    }

    // ---------- site detection ----------

    private String blockedSiteOnScreen(AccessibilityNodeInfo root, String pkg) {
        Set<String> sites = rules.getSites();
        if (sites.isEmpty()) return null;

        String[] ids = URL_BAR_IDS.get(pkg);
        if (ids != null) {
            for (String id : ids) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (nodes == null) continue;
                for (AccessibilityNodeInfo n : nodes) {
                    String hit = match(n.getText(), sites);
                    if (hit != null) return hit;
                }
            }
        }

        // Generic fallback: only look at text fields that look like an address bar,
        // so search results that merely mention a site don't trigger a block.
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen < MAX_NODES) {
            AccessibilityNodeInfo n = q.poll();
            seen++;
            if (n == null) continue;
            if (looksLikeAddressBar(n)) {
                String hit = match(n.getText(), sites);
                if (hit != null) return hit;
            }
            for (int c = 0; c < n.getChildCount(); c++) q.add(n.getChild(c));
        }
        return null;
    }

    private static boolean looksLikeAddressBar(AccessibilityNodeInfo n) {
        CharSequence cls = n.getClassName();
        if (cls != null && cls.toString().endsWith("EditText")) return true;
        String id = n.getViewIdResourceName();
        if (id == null) return false;
        id = id.toLowerCase(Locale.ROOT);
        return id.contains("url") || id.contains("omnibar") || id.contains("location_bar")
                || id.contains("address");
    }

    private static String match(CharSequence text, Set<String> sites) {
        if (text == null) return null;
        String t = text.toString().toLowerCase(Locale.ROOT);
        for (String s : sites) {
            if (!s.isEmpty() && t.contains(s)) return s;
        }
        return null;
    }

    // ---------- app detection ----------

    /** Every app that can open an ordinary web link is a browser. Re-checked every minute. */
    private Set<String> browserPackages() {
        long now = SystemClock.uptimeMillis();
        if (browsersCheckedAt != 0 && now - browsersCheckedAt < 60_000) return browserPkgs;
        browsersCheckedAt = now;
        Set<String> found = new HashSet<>();
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        int flags = Build.VERSION.SDK_INT >= 23 ? PackageManager.MATCH_ALL : 0;
        List<ResolveInfo> list = getPackageManager().queryIntentActivities(web, flags);
        for (ResolveInfo ri : list) {
            if (ri.activityInfo != null) found.add(ri.activityInfo.packageName);
        }
        found.remove(getPackageName());
        found.remove("android");
        browserPkgs = found;
        return found;
    }

    /** Third-party Reddit apps (Infinity, Boost, RedReader, Sync...), by package name or label. */
    private boolean isRedditClient(String pkg) {
        if (pkg.equals(OFFICIAL_REDDIT)) return false; // handled by the normal app list
        Boolean cached = redditClientCache.get(pkg);
        if (cached != null) return cached;
        String id = pkg.toLowerCase(Locale.ROOT);
        String label = appLabel(pkg).toLowerCase(Locale.ROOT);
        boolean r = id.contains("reddit") || id.contains("redreader")
                || label.contains("reddit") || label.contains("redreader");
        redditClientCache.put(pkg, r);
        return r;
    }

    // ---------- helpers ----------

    private static boolean treeHasExactText(AccessibilityNodeInfo root, String text) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen < MAX_NODES) {
            AccessibilityNodeInfo n = q.poll();
            seen++;
            if (n == null) continue;
            CharSequence t = n.getText();
            if (t != null && t.toString().trim().equalsIgnoreCase(text)) return true;
            CharSequence d = n.getContentDescription();
            if (d != null && d.toString().trim().equalsIgnoreCase(text)) return true;
            for (int c = 0; c < n.getChildCount(); c++) q.add(n.getChild(c));
        }
        return false;
    }

    private static boolean treeContains(AccessibilityNodeInfo root, String needle) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen < MAX_NODES) {
            AccessibilityNodeInfo n = q.poll();
            seen++;
            if (n == null) continue;
            if (contains(n.getText(), needle) || contains(n.getContentDescription(), needle)) {
                return true;
            }
            for (int c = 0; c < n.getChildCount(); c++) q.add(n.getChild(c));
        }
        return false;
    }

    private static boolean contains(CharSequence cs, String needle) {
        return cs != null && cs.toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
