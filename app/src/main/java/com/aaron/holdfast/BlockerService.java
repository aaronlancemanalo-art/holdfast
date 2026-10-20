package com.aaron.holdfast;

import android.accessibilityservice.AccessibilityService;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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

    private Rules rules;
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
        if (!isSettings && !isInstaller && !isBrowser) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // 3. Tamper guard
        if (rules.isGuardOn() && !setupAllowed()) {
            if (isSettings && treeContains(root, APP_NAME)) {
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

    // ---------- helpers ----------

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
