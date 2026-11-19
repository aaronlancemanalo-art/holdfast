package com.aaron.holdfast;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    /**
     * Apps that show web pages in their own built-in viewer without registering as a browser
     * (search apps and "lite" apps). Treated as browsers while "All browsers" is on.
     */
    private static final Set<String> WEB_APP_PKGS = new HashSet<>(Arrays.asList(
            "com.google.android.apps.searchlite",        // Google Go
            "com.google.android.googlequicksearchbox",   // Google app
            "com.microsoft.bing",                        // Bing
            "com.yahoo.mobile.client.android.search",    // Yahoo Search
            "com.opera.app.news",                        // Opera News
            "ru.yandex.searchplugin",                    // Yandex
            "com.ecosia.android",                        // Ecosia
            "com.qwant.liberty",                         // Qwant
            "com.startpage.app",                         // Startpage
            "ai.perplexity.app.android"));               // Perplexity

    /** Never deep-scanned for web pages: home screen, system UI, keyboards, Settings, installers. */
    private static final Set<String> NO_SCAN_PKGS = new HashSet<>(Arrays.asList(
            "com.sec.android.app.launcher",
            "com.android.systemui",
            "com.samsung.android.globalactions",
            "com.samsung.android.honeyboard",
            "com.google.android.inputmethod.latin",
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller"));

    private static final String CLAUDE_PKG = "com.anthropic.claude";
    /** Browsers that show link tabs for other apps (custom tabs). */
    private static final Set<String> TAB_BROWSERS = new HashSet<>(Arrays.asList(
            "com.android.chrome", "com.chrome.beta", "com.sec.android.app.sbrowser"));
    private static final Set<String> TRACK_IGNORE_PKGS = new HashSet<>(Arrays.asList(
            "com.android.systemui", "com.samsung.android.globalactions",
            "com.samsung.android.honeyboard", "com.google.android.inputmethod.latin"));
    /** Last real app in front (not a browser, system UI, keyboard or Holdfast). */
    private String lastApp = "";
    /** True while a browser is showing a link tab opened from the Claude app. */
    private boolean claudeTabOpen = false;

    private static final String GEMINI_PKG = "com.google.android.apps.bard";
    private static final String GOOGLE_APP_PKG = "com.google.android.googlequicksearchbox";
    /** Whether the Google app's current screen is Gemini, updated on every screen change. */
    private boolean geminiInFront = false;

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
    /** Reddit's own addresses: how a page is known to be Reddit when Reddit isn't on the blocked list. */
    private static final Set<String> REDDIT_SITES = new HashSet<>(Arrays.asList("reddit.com", "redd.it"));
    private static final String MATURE_BLOCKED = "Mature (NSFW) content on Reddit is blocked.";
    /** When each app was last seen showing a Reddit web page (uptime ms). */
    private final Map<String, Long> redditPageSeen = new HashMap<>();

    private Rules rules;
    private Set<String> browserPkgs = new HashSet<>();
    /** Apps that open reddit.com links themselves without being a general browser. */
    private Set<String> redditLinkApps = new HashSet<>();
    private long browsersCheckedAt = 0;
    private final Map<String, Boolean> redditClientCache = new HashMap<>();
    private final Map<String, Boolean> browserNameCache = new HashMap<>();
    private final Map<String, Boolean> systemAppCache = new HashMap<>();
    private final Map<String, Long> lastDeepScan = new HashMap<>();
    private final Map<String, Boolean> sideloadedCache = new HashMap<>();
    private long sideloadedCacheAt = 0;
    /** File-installed apps from before the install rule began ("package|install time"). */
    private Set<String> sideloadAllowed;
    private long sideloadListTriedAt = 0;

    /** App stores whose installs are allowed. */
    private static final Set<String> STORES = new HashSet<>(Arrays.asList(
            "com.android.vending",               // Google Play Store
            "com.sec.android.app.samsungapps")); // Galaxy Store
    private long lastBlockAt = 0;
    private String lastBlockReason = "";
    private final Map<String, Long> lastScan = new HashMap<>();
    /** Until when app-picker screens count as part of the sleeping-apps lists (uptime ms). */
    private long sleepListsUntil = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /**
     * More looks at each new screen shortly after it appears. Events are handled the instant
     * they arrive, which can be a moment before the new screen is the one that can be read.
     */
    private final Runnable[] rechecks = {
            new Runnable() {
                @Override
                public void run() {
                    recheckScreen(false);
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    recheckScreen(true);
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    recheckScreen(false);
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    recheckScreen(false);
                }
            }};
    private static final long[] RECHECK_AFTER_MS = {60, 250, 800, 2200};

    /** One more look at a web page after it has stopped changing. */
    private final Runnable webRecheck = new Runnable() {
        @Override
        public void run() {
            recheckWebPage();
        }
    };

    /**
     * A Google app screen that isn't Gemini is judged a moment after it appears, not at once,
     * because opening Gemini can pass through one on the way.
     */
    private static final long GOOGLE_GRACE_MS = 250;
    private long googleGraceUntil = 0;
    private final Runnable googleRecheck = new Runnable() {
        @Override
        public void run() {
            recheckGoogleApp();
        }
    };

    /** An app was installed, updated or removed: forget what was remembered about apps. */
    private final BroadcastReceiver appsChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            forgetAppFacts();
        }
    };
    private boolean appsChangedOn = false;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        rules = new Rules(this);
        instance = this;
        if (!appsChangedOn) {
            try {
                IntentFilter f = new IntentFilter();
                f.addAction(Intent.ACTION_PACKAGE_ADDED);
                f.addAction(Intent.ACTION_PACKAGE_REMOVED);
                f.addAction(Intent.ACTION_PACKAGE_REPLACED);
                f.addAction(Intent.ACTION_PACKAGE_CHANGED);
                f.addDataScheme("package");
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(appsChanged, f, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(appsChanged, f);
                }
                appsChangedOn = true;
            } catch (Throwable t) {
                rules.recordError(t);
            }
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        stopExtras();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        stopExtras();
        super.onDestroy();
    }

    private void stopExtras() {
        handler.removeCallbacksAndMessages(null);
        if (appsChangedOn) {
            appsChangedOn = false;
            try {
                unregisterReceiver(appsChanged);
            } catch (Throwable ignored) {
            }
        }
    }

    private void forgetAppFacts() {
        browsersCheckedAt = 0;
        sideloadedCache.clear();
        installedCache.clear();
        redditClientCache.clear();
        browserNameCache.clear();
        systemAppCache.clear();
        ledgerTrusted = null;
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

        boolean windowChange = event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        // Every new screen gets more looks shortly after, for pages that fill in late and for
        // the rare event that arrives while another block is still being handled.
        if (windowChange) {
            for (int i = 0; i < rechecks.length; i++) {
                handler.removeCallbacks(rechecks[i]);
                handler.postDelayed(rechecks[i], RECHECK_AFTER_MS[i]);
            }
        }

        // 0. Riyal Ledger: the one app Holdfast installs itself. Trusted only under its own
        // package name AND when signed with Holdfast's key, so nothing else can pose as it.
        if (isTrustedOwnApp(pkg)) return;

        // Settings, installer and power menu screens are read before anything else: these are
        // the screens where reacting late matters most. The other rules still run afterwards.
        boolean guardFamily = SETTINGS_PKGS.contains(pkg) || INSTALLER_PKGS.contains(pkg)
                || SYSTEM_UI_PKGS.contains(pkg);
        AccessibilityNodeInfo root = null;
        if (guardFamily && (windowChange || dueForScan(pkg, 150))) {
            root = getRootInActiveWindow();
            if (root != null && checkSensitiveScreen(root, false)) return;
        }

        // 0b. Apps that aren't installed in the main profile (Secure Folder-only apps, instant
        // apps) can't be checked like normal apps, so they don't open while protection is on.
        if (rules.isGuardOn() && windowChange
                && !"android".equals(pkg) && !isInstalledHere(pkg)) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null && pkg.equals(r.getPackageName().toString())) {
                block("This app runs outside your main profile (Secure Folder or an instant app), "
                        + "so it's blocked while tamper protection is on.");
                return;
            }
        }

        // Links opened from the Claude app load in a browser "custom tab" (no address bar to type
        // in). Those tabs are allowed; every other browser screen stays blocked, and the site
        // checks further down still apply inside them.
        trackForegroundApp(event, pkg);
        boolean claudeTab = isClaudeLinkTab(event, pkg);

        // 1. Blocked apps
        if (rules.getApps().contains(pkg) && !claudeTab) {
            block(appLabel(pkg) + " is blocked.");
            return;
        }

        // 1a. Gemini isn't a browser. Its app is a launcher for screens that run inside the Google
        // app, so let the Google app through only while it is showing Gemini. Google search and
        // web pages in the Google app stay blocked. The Gemini app itself only skips the browser
        // rule; every other check below still applies to it.
        boolean geminiApp = GEMINI_PKG.equals(pkg);
        if (GOOGLE_APP_PKG.equals(pkg)) {
            long now = SystemClock.uptimeMillis();
            if (isGeminiScreen(event)) {
                // Even inside Gemini, a web page with an address bar (or a blocked site) means the
                // Google app is being used as a browser, so it closes.
                Long last = lastDeepScan.get(pkg);
                if (last == null || now - last >= 1000) {
                    lastDeepScan.put(pkg, now);
                    AccessibilityNodeInfo r = getRootInActiveWindow();
                    if (r != null && r.getPackageName() != null
                            && pkg.equals(r.getPackageName().toString())
                            && (looksLikeBrowserScreen(r)
                                || blockedSiteInWebViewer(r, rules.getSites(), false) != null)) {
                        geminiInFront = false;
                        block("Web pages in the Google app are blocked (Gemini itself is fine).");
                    }
                }
                return;
            }
            // Not a Gemini screen. One that has only just appeared is judged after a short wait
            // (see GOOGLE_GRACE_MS); recheckGoogleApp() and the rules below take it from there.
            if (windowChange && now >= googleGraceUntil) {
                // A wait already running is never extended, so screens that keep changing
                // can't put the verdict off.
                googleGraceUntil = now + GOOGLE_GRACE_MS;
                handler.removeCallbacks(googleRecheck);
                handler.postDelayed(googleRecheck, GOOGLE_GRACE_MS + 20);
            }
            if (now < googleGraceUntil) return;
        }

        // 1b. Any browser, including ones installed after the rules were set
        if (!claudeTab && !geminiApp && rules.allBrowsersBlocked()
                && (browserPackages().contains(pkg) || WEB_APP_PKGS.contains(pkg)
                    || isNamedLikeBrowser(pkg))) {
            block(appLabel(pkg) + " is blocked (all browsers are).");
            return;
        }

        // 1c. Other Reddit apps, while reddit.com is blocked
        if (rules.getSites().contains("reddit.com") && isRedditClient(pkg)) {
            block(appLabel(pkg) + " is a Reddit app, and Reddit is blocked.");
            return;
        }

        // 1d. Apps installed from a file (whatever app delivered the file) can't be opened
        if (rules.sideloadBlocked()) haveSideloadList(); // made on the first event, before any app is judged
        if (rules.sideloadBlocked() && isNewSideloadedApp(pkg)) {
            block(appLabel(pkg) + " was installed from a file, not an app store, so it's blocked.");
            return;
        }

        // 1f. Reddit, wherever the rules let it open: it closes when something marked mature
        // (NSFW, 18+) is on screen, including the settings that would switch such content on.
        if (rules.matureBlocked() && isAllowedRedditApp(pkg)
                && (windowChange || dueForScan("mature " + pkg, 250))) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null && pkg.equals(r.getPackageName().toString())
                    && matureContentOnScreen(readScreen(r))) {
                // Stepping back first leaves the post, gate or settings screen itself, so the
                // same screen isn't waiting the next time Reddit is opened.
                block(MATURE_BLOCKED, true);
                return;
            }
        }

        // 1e. Web pages inside any app's built-in viewer (Instagram links, unnamed browser
        // apps...). Other pages load normally; only a blocked site on screen closes it, or mature
        // content on Reddit where Reddit itself is allowed.
        if (!NO_SCAN_PKGS.contains(pkg) && (!rules.getSites().isEmpty() || rules.matureBlocked())) {
            long now = SystemClock.uptimeMillis();
            Long last = lastDeepScan.get(pkg);
            long every = webLookEvery(pkg, now);
            if (last == null || now - last >= every) {
                String why = null;
                try {
                    AccessibilityNodeInfo r = getRootInActiveWindow();
                    if (r != null && r.getPackageName() != null
                            && pkg.equals(r.getPackageName().toString())) {
                        lastDeepScan.put(pkg, now);
                        why = webPageVerdict(r, pkg);
                    }
                } catch (Throwable t) {
                    // A page that can't be read must not stop the checks below.
                    rules.recordError(t);
                }
                if (why != null) {
                    block(why, MATURE_BLOCKED.equals(why));
                    return;
                }
            } else {
                // Too soon after the last look. Look once more when the page has settled, so a
                // page that finishes loading just after a look isn't missed.
                handler.removeCallbacks(webRecheck);
                handler.postDelayed(webRecheck, every + 100 - (now - last));
            }
        }

        // 2. Blocked sites in a browser's address bar. Content-change events fire constantly;
        // each browser is read at most every 400 ms.
        if (!guardFamily && (URL_BAR_IDS.containsKey(pkg) || pkg.contains("browser"))
                && (windowChange || dueForScan(pkg, 400))) {
            root = getRootInActiveWindow();
            if (root != null && checkSensitiveScreen(root, true)) return;
        }

        if (windowChange && !NO_SCAN_PKGS.contains(pkg)) {
            if (root == null) root = getRootInActiveWindow();
            if (root != null) checkSystemAppScreen(root);
        }
    }

    /** True if this app's screen hasn't been read within the last everyMs (and notes the read). */
    private boolean dueForScan(String pkg, long everyMs) {
        long now = SystemClock.uptimeMillis();
        Long last = lastScan.get(pkg);
        if (last != null && now - last < everyMs) return false;
        lastScan.put(pkg, now);
        return true;
    }

    /**
     * How often (ms) an app's web pages are read. A page known to be Reddit is read more often,
     * so a marked post doesn't stay up.
     */
    private long webLookEvery(String pkg, long now) {
        Long reddit = redditPageSeen.get(pkg);
        return reddit != null && now - reddit < 5000 ? 250 : 1000;
    }

    /** A Reddit app that the rules currently let open (the official one, or another one). */
    private boolean isAllowedRedditApp(String pkg) {
        if (rules.getApps().contains(pkg)) return false;
        if (OFFICIAL_REDDIT.equals(pkg)) return true;
        return isRedditClient(pkg) && !rules.getSites().contains("reddit.com");
    }

    /**
     * Web pages inside the app in front. Returns the reason to close it, or null:
     * a blocked site, or (where Reddit itself is allowed) mature content on a Reddit page.
     */
    private String webPageVerdict(AccessibilityNodeInfo r, String pkg) {
        Set<String> sites = rules.getSites();
        boolean redditApp = isAllowedRedditApp(pkg);
        // An allowed Reddit app showing Reddit's own address is not a blocked site in a viewer.
        // What it shows is covered by rule 1f instead.
        if (redditApp) sites.removeAll(REDDIT_SITES);
        if (!sites.isEmpty()) {
            String hit = blockedSiteInWebViewer(r, sites, true);
            if (hit != null) return hit + " is blocked, in every app.";
        }
        if (rules.matureBlocked() && !redditApp && !sites.contains("reddit.com")
                && blockedSiteInWebViewer(r, REDDIT_SITES, true) != null) {
            redditPageSeen.put(pkg, SystemClock.uptimeMillis());
            if (matureContentOnScreen(readScreen(r))) return MATURE_BLOCKED;
        }
        return null;
    }

    /** The delayed look at a web page that was still changing when it was last read. */
    private void recheckWebPage() {
        try {
            if (rules == null || instance != this) return;
            if (rules.getSites().isEmpty() && !rules.matureBlocked()) return;
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r == null || r.getPackageName() == null) return;
            String pkg = r.getPackageName().toString();
            // The Google app has its own check (Gemini); Holdfast and the ledger are never scanned.
            if (pkg.equals(getPackageName()) || NO_SCAN_PKGS.contains(pkg)
                    || GOOGLE_APP_PKG.equals(pkg) || isTrustedOwnApp(pkg)) return;
            long now = SystemClock.uptimeMillis();
            Long last = lastDeepScan.get(pkg);
            if (last != null && now - last < webLookEvery(pkg, now) - 100) return; // an event already caused a fresh look
            lastDeepScan.put(pkg, now);
            String why = webPageVerdict(r, pkg);
            if (why != null) block(why, MATURE_BLOCKED.equals(why));
        } catch (Throwable t) {
            if (rules != null) rules.recordError(t);
        }
    }

    /** The delayed verdict on a Google app screen that wasn't Gemini when it appeared. */
    private void recheckGoogleApp() {
        try {
            if (rules == null || instance != this || geminiInFront) return;
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r == null || r.getPackageName() == null
                    || !GOOGLE_APP_PKG.equals(r.getPackageName().toString())) return;
            // The screen may not have been readable yet when it appeared: read it now.
            if (treeHintContains(r, "ask gemini") || treeContains(r, "ask gemini")) {
                geminiInFront = true;
                return;
            }
            if (rules.allBrowsersBlocked()) {
                block(appLabel(GOOGLE_APP_PKG) + " is blocked (all browsers are).");
            }
        } catch (Throwable t) {
            if (rules != null) rules.recordError(t);
        }
    }

    /**
     * A delayed look at whatever screen is in front now.
     *
     * @param systemAppsToo also read preinstalled apps outside Settings (done on one of the
     *                      looks only, to keep the reading of other apps' screens light)
     */
    private void recheckScreen(boolean systemAppsToo) {
        try {
            if (rules == null || instance != this) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) return;
            String pkg = root.getPackageName().toString();
            if (pkg.equals(getPackageName())) return;
            if (!TAB_BROWSERS.contains(pkg) && !TRACK_IGNORE_PKGS.contains(pkg)) noteAppInFront(pkg);
            // Same rule as 0b in handle(), for a screen that wasn't readable yet at its event.
            if (rules.isGuardOn() && !"android".equals(pkg) && !isInstalledHere(pkg)) {
                block("This app runs outside your main profile (Secure Folder or an instant app), "
                        + "so it's blocked while tamper protection is on.");
                return;
            }
            // The Google app is judged by its own rules (Gemini), not here.
            if (GOOGLE_APP_PKG.equals(pkg)) return;
            if (checkSensitiveScreen(root, true)) return;
            // Same rule as 1f in handle().
            if (rules.matureBlocked() && isAllowedRedditApp(pkg) && matureContentOnScreen(readScreen(root))) {
                block(MATURE_BLOCKED, true);
                return;
            }
            if (systemAppsToo) checkSystemAppScreen(root);
        } catch (Throwable t) {
            if (rules != null) rules.recordError(t);
        }
    }

    /**
     * Settings, installer, power menu and browser screens. Decided by which app the screen in
     * front belongs to, read once, then checked against every rule. Returns true if it blocked.
     */
    private boolean checkSensitiveScreen(AccessibilityNodeInfo root, boolean browsersToo) {
        CharSequence rp = root.getPackageName();
        if (rp == null) return false;
        String pkg = rp.toString();
        if (pkg.equals(getPackageName())) return false;
        boolean isSettings = SETTINGS_PKGS.contains(pkg);
        boolean isInstaller = INSTALLER_PKGS.contains(pkg);
        boolean isSystemUi = SYSTEM_UI_PKGS.contains(pkg);
        boolean isBrowser = browsersToo && (URL_BAR_IDS.containsKey(pkg) || pkg.contains("browser"));
        if (!isSettings && !isInstaller && !isSystemUi && !isBrowser) return false;

        if (isSettings || isInstaller || isSystemUi) {
            Screen s = readScreen(root);
            // The checks below read English. In any other phone language they fall back to
            // stricter rules that don't depend on wording.
            boolean english = phoneInEnglish();

            // 3. Tamper guard
            if (rules.isGuardOn() && !setupAllowed()) {
                if (isSystemUi && s.exact("safe mode")) {
                    block("Safe mode is blocked while tamper protection is on.", true);
                    return true;
                }
                if (isSettings) {
                    String why = protectedSettingsPage(s, english);
                    if (why != null) {
                        // Stepping back first closes the page (and any dialog on it), so it isn't
                        // still there the next time Settings is opened.
                        block(why, true);
                        return true;
                    }
                }
                if (isInstaller && s.has(APP_NAME)
                        && (s.has("uninstall") || (!english && !rules.inSelfUpdateWindow()))) {
                    block("Uninstalling Holdfast is blocked. Turn protection off in the app first (24 hours).", true);
                    return true;
                }
            }

            // 4a. No app can be given permission to install apps (only Holdfast, for its own updates)
            if (isSettings && rules.sideloadBlocked() && s.has("this source")
                    && !(rules.inInstallPermissionWindow() && s.has(APP_NAME))) {
                block("Letting apps install other apps is blocked while installs from files are.", true);
                return true;
            }

            // 4. Installing apps from files. Uninstall dialogs (other apps) are left alone.
            if (isInstaller && rules.sideloadBlocked()) {
                // An Install or Update button always means an install screen, whatever else the
                // dialog says (the app's own name is on it too).
                boolean installScreen = !english || s.exact("install") || s.exact("update")
                        || ((s.has("install") || s.has("update")) && !s.has("uninstall"));
                // The window opens only after Holdfast has checked that the file it is about to install
                // is Holdfast or Riyal Ledger, signed with Holdfast's key (MainActivity.isOwnSignedApk).
                // Within it, every installer screen passes: Samsung's installer and Play Protect show
                // screens without the app's name, and matching on the name dropped them.
                boolean ownUpdate = rules.inSelfUpdateWindow();
                if (installScreen && !ownUpdate) {
                    block("Installing apps from files is blocked. Use the Play Store.", true);
                    return true;
                }
            }
        }

        // 2. Blocked sites
        if (isBrowser) {
            Set<String> sites = rules.getSites();
            String hit = blockedSiteOnScreen(root, pkg, sites);
            if (hit != null) {
                block(hit + " is blocked.");
                return true;
            }
            // Reddit allowed in this browser: mature content on it still closes the page.
            if (rules.matureBlocked() && !sites.contains("reddit.com")
                    && blockedSiteOnScreen(root, pkg, REDDIT_SITES) != null) {
                redditPageSeen.put(pkg, SystemClock.uptimeMillis());
                if (matureContentOnScreen(readScreen(root))) {
                    block(MATURE_BLOCKED, true);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Preinstalled apps outside Settings can also host a protected page (Samsung keeps some
     * settings in separate apps). Checked once per new screen. Returns true if it blocked.
     */
    private boolean checkSystemAppScreen(AccessibilityNodeInfo root) {
        if (!rules.isGuardOn() || setupAllowed()) return false;
        CharSequence rp = root.getPackageName();
        if (rp == null) return false;
        String pkg = rp.toString();
        if (pkg.equals(getPackageName()) || NO_SCAN_PKGS.contains(pkg) || SETTINGS_PKGS.contains(pkg)
                || INSTALLER_PKGS.contains(pkg) || TAB_BROWSERS.contains(pkg)
                || !isSystemApp(pkg)) return false;
        String why = phoneModePage(readScreen(root));
        if (why == null) return false;
        block(why);
        return true;
    }

    @Override
    public void onInterrupt() {
    }

    // ---------- blocking ----------

    private void block(String reason) {
        block(reason, false);
    }

    /** @param stepBack press Back before going home, to close the page or dialog itself */
    private void block(String reason, boolean stepBack) {
        long now = SystemClock.uptimeMillis();
        // Blocks arriving within a moment of each other are one block: the screen is already
        // on its way out. The exception is a protected page (stepBack) with a different reason,
        // which is a new screen and must never be skipped.
        if (now - lastBlockAt < 800 && (!stepBack || reason.equals(lastBlockReason))) return;
        lastBlockAt = now;
        lastBlockReason = reason;
        rules.countBlock();
        if (screenPinned()) {
            // A pinned app can't be sent home and nothing can open over it. Step back out of the
            // page and lock the screen instead.
            performGlobalAction(GLOBAL_ACTION_BACK);
            if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            try {
                Toast.makeText(this, "Blocked. This app is pinned, so Holdfast locked the screen. "
                        + "Unpin the app or restart the phone.", Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
            return;
        }
        if (stepBack) performGlobalAction(GLOBAL_ACTION_BACK);
        performGlobalAction(GLOBAL_ACTION_HOME);
        Intent i = new Intent(this, BlockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(BlockActivity.EXTRA_REASON, reason);
        startActivity(i);
    }

    /** True while an app is pinned to the screen (Android's "Pin app"). */
    private boolean screenPinned() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            return am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        } catch (Throwable t) {
            return false;
        }
    }

    /** During first setup the admin-activation screen must be reachable. */
    private boolean setupAllowed() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        boolean adminActive = dpm != null
                && dpm.isAdminActive(new ComponentName(this, AdminReceiver.class));
        return !adminActive && rules.inSetupWindow();
    }

    private boolean phoneInEnglish() {
        try {
            return "en".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());
        } catch (Throwable t) {
            return true;
        }
    }

    // ---------- reading a screen ----------

    /** Everything written on a screen, read in one pass so the checks on it are quick. */
    static final class Screen {
        private final ArrayList<String> texts = new ArrayList<>();

        void add(CharSequence cs) {
            if (cs == null) return;
            String t = cs.toString().trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) texts.add(t);
        }

        /** Some text on the screen contains this. */
        boolean has(String needle) {
            for (String t : texts) {
                if (t.contains(needle)) return true;
            }
            return false;
        }

        /** Some label on the screen is exactly this. */
        boolean exact(String label) {
            for (String t : texts) {
                if (t.equals(label)) return true;
            }
            return false;
        }

        boolean hasAny(String[] needles) {
            for (String n : needles) {
                if (has(n)) return true;
            }
            return false;
        }
    }

    private static Screen readScreen(AccessibilityNodeInfo root) {
        Screen s = new Screen();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen < MAX_NODES) {
            AccessibilityNodeInfo n = q.poll();
            seen++;
            if (n == null) continue;
            s.add(n.getText());
            s.add(n.getContentDescription());
            for (int c = 0; c < n.getChildCount(); c++) {
                AccessibilityNodeInfo ch = n.getChild(c);
                if (ch != null) q.add(ch); // getChild can return null; adding it crashed the scan
            }
        }
        return s;
    }

    // ---------- protected Settings pages ----------

    /** Rows only the Developer options page itself has (the Settings list that links to it doesn't). */
    private static final String[] DEV_PAGE_ROWS = {
            "oem unlock", "running services", "bug report", "stay awake", "hci snoop",
            "webview implementation", "quick settings developer tiles", "desktop backup password",
            "system ui demo mode", "revoke usb debugging"};

    private static final String SLEEP_LISTS = "Sleeping-app lists are protected while tamper protection is on.";

    private String protectedSettingsPage(Screen s, boolean english) {
        long now = SystemClock.uptimeMillis();
        String why = settingsPageRule(s, english, rules.inInstallPermissionWindow(), now < sleepListsUntil);
        // Seeing one of the lists also covers the app picker that opens from it, for a short while.
        if (SLEEP_LISTS.equals(why) && s.has("sleeping apps")) sleepListsUntil = now + 90_000;
        return why;
    }

    /**
     * Settings pages that could switch Holdfast off, stop it running, or stop it from reading
     * the screen. Returns the reason to show, or null if the page is fine. Pages that merely
     * link to one of these (a menu with the row in it) are left alone.
     *
     * @param installWindow  Holdfast's own "Install unknown apps" page is allowed right now
     * @param nearSleepLists a sleeping-app list was on screen a moment ago
     */
    static String settingsPageRule(Screen s, boolean english, boolean installWindow, boolean nearSleepLists) {
        if (!english) {
            // Can't read the page: close anything in Settings that mentions Holdfast at all.
            return s.has(APP_NAME)
                    ? "Holdfast's settings are protected. Turning protection off takes 24 hours."
                    : null;
        }

        // The only Holdfast settings page let through: "Install unknown apps", for 90 s after
        // tapping Download update. Pages with other controls (force stop etc.) stay blocked.
        boolean installPermissionPage = installWindow
                && s.has("this source")
                && !s.has("force stop")
                && !s.has("uninstall")
                && !s.has("clear data")
                && !s.has("accessibility");

        if (s.has("usb debugging") || s.has("wireless debugging")
                || (s.exact("developer options") && s.hasAny(DEV_PAGE_ROWS))) {
            return "Developer options are protected while tamper protection is on.";
        }
        // The two reset pages (not the Reset menu, which also leads to the network reset).
        if ((s.has("reset all settings") || s.has("reset accessibility settings"))
                && !s.has("factory data reset") && !s.has("installed apps")) {
            return "Resetting settings is blocked while tamper protection is on.";
        }
        if ((s.has("pin app") || s.has("pin window"))
                && (s.has("to unpin") || s.has("unpinning") || s.has("pin this app"))) {
            return "App pinning is blocked while tamper protection is on.";
        }
        // The power saving options page (not the Battery page, even if it mentions the options).
        if (s.has("limit apps and home screen") && !s.has("background usage limits")) {
            return "Power saving options are protected while tamper protection is on.";
        }
        if (s.has("add language") || s.has("add a language")) {
            return "Changing the phone's language is blocked while tamper protection is on.";
        }
        String mode = phoneModePage(s);
        if (mode != null) return mode;

        // Sleeping, deep sleeping and never-sleeping app lists, and the app pickers behind them.
        // The menu that links to the lists stays open.
        if (s.has("sleeping apps") && !s.has("put unused apps")) return SLEEP_LISTS;
        if (nearSleepLists && (s.exact(APP_NAME) || s.exact("all")
                || s.has("select apps") || s.has("selected"))) {
            return SLEEP_LISTS;
        }

        if (!installPermissionPage && isHoldfastControlPage(s)) {
            return "Holdfast's settings are protected. Turning protection off takes 24 hours.";
        }
        return null;
    }

    /** A page that restarts the phone into a mode where only preinstalled apps run. */
    private static String phoneModePage(Screen s) {
        if (s.has("maintenance mode") && (s.exact("turn on") || s.exact("restart"))
                && !s.exact("battery") && !s.exact("storage")) {
            return "Maintenance mode is blocked while tamper protection is on.";
        }
        return null;
    }

    /** Words that only appear on Settings screens that can switch off, limit or remove an app. */
    // Kept to words that never show up in a plain app list (no "disabled", "storage" etc., which
    // list rows can show for other apps).
    private static final String[] CONTROL_WORDS = {
            "force stop",            // Holdfast's App info page
            "clear data",            // its Storage page
            "clear storage",
            "use holdfast",          // its Accessibility switch
            "holdfast shortcut",     // its Accessibility shortcut page
            "device admin",          // device admin apps
            "deactivate this",       // device admin detail
            "sleeping apps",         // battery: sleeping / deep sleeping lists
            "unrestricted",          // per-app battery page
            "optimize battery",      // battery optimisation list
            "optimise battery",
            "battery optimi",
            "allow restricted settings",
            "volume up",             // accessibility shortcut on the volume keys
            "volume down",
            "side and volume",
            "accessibility button",  // on-screen accessibility button
            "shortcut"};

    /**
     * A Settings screen about Holdfast that has controls to stop it (its App info page, its
     * Accessibility switch, device admin, battery limits...). Plain lists that merely include
     * Holdfast among other apps, like Settings > Apps, are not blocked.
     */
    private static boolean isHoldfastControlPage(Screen s) {
        // The "are you sure" dialogs that follow its Accessibility switch or Deactivate button:
        // by their wording, or by their shape (they name Holdfast and offer Cancel plus a button
        // that goes ahead). Permission prompts (Allow / Deny) are not this.
        if (s.has("turn off holdfast") || s.has("stop holdfast")) return true;
        if (s.has(APP_NAME) && s.exact("cancel") && (s.exact("turn off") || s.exact("stop")
                || s.exact("ok") || s.exact("deactivate"))) return true;
        // Holdfast must be the subject of the page: its name as a title on its own, not just
        // mentioned (e.g. "installed from Holdfast" on Riyal Ledger's App info page).
        boolean aboutHoldfast = s.exact(APP_NAME)
                || s.has("use holdfast")
                || s.has("holdfast shortcut");
        return aboutHoldfast && s.hasAny(CONTROL_WORDS);
    }

    // ---------- mature content on Reddit ----------

    /** Wording on Reddit's age gates and on the settings that switch mature content on. */
    private static final String[] MATURE_PHRASES = {
            "mature content", "mature (18+)", "adult content", "over eighteen",
            "nsfw community", "show nsfw", "blur nsfw", "not safe for work",
            "confirm your age", "verify your age", "age verification", "sexually explicit"};
    /**
     * Age wording as a gate or a setting puts it ("I'm over 18", "You must be 18+ to view"),
     * not as an ordinary sentence does ("over 18 hours", "must be 18 inches").
     */
    private static final Pattern AGE_GATE = Pattern.compile(
            "over 18(?:$|[)?.!,;:'\"]|\\s+(?:to\\s|years?\\s+old|and\\s))"
            + "|must be 18(?:\\+|\\s+(?:or|to|years))"
            + "|at least 18 years"
            + "|18 (?:years )?or older"
            + "|18\\+ (?:to view|community|content)");
    /** A rule or notice that forbids such content is not such content. */
    private static final String[] FORBIDDING = {
            "not allowed", "isn't allowed", "aren't allowed", "is not permitted", "prohibited",
            "banned", "don't post", "do not post"};
    private static final Pattern TAG_SPLIT = Pattern.compile(
            "(?:[,\u2022\u00b7|\n\t:;\\[\\](){}]+|\\s[-\u2013\u2014]\\s|\\.\\s)");

    /**
     * True if the screen shows something Reddit marks as mature: an NSFW or 18+ tag on a post
     * or community (also when it is one item in the line read out for a post), a name with
     * "nsfw" in it, an age gate, or the settings for showing such content. Ordinary sentences
     * that merely use the word are left alone, and so are rules that forbid it.
     */
    static boolean matureContentOnScreen(Screen s) {
        for (String t : s.texts) {
            boolean forbids = t.startsWith("no ") || t.contains(" no ");
            for (int i = 0; !forbids && i < FORBIDDING.length; i++) forbids = t.contains(FORBIDDING[i]);
            if (t.length() <= 90 && !forbids) {
                for (String p : MATURE_PHRASES) {
                    if (t.contains(p)) return true;
                }
                if (t.contains("18") && AGE_GATE.matcher(t).find()) return true;
            }
            if (!t.contains("nsfw") && !t.contains("18+")) continue;
            for (String piece : TAG_SPLIT.split(t)) {
                if (isMatureTag(piece.trim())) return true;
            }
        }
        return false;
    }

    /** Longer ways a post or community is announced as marked. */
    private static final String[] MATURE_LABELS = {
            "marked as nsfw", "marked nsfw", "tagged as nsfw", "tagged nsfw", "flagged as nsfw",
            "this post is nsfw", "this community is nsfw", "this profile is nsfw"};

    /** A short piece standing on its own, like "NSFW", "18+", "NSFW spoiler" or "r/nsfw_gifs". */
    private static boolean isMatureTag(String piece) {
        if (piece.isEmpty() || piece.length() > 40) return false;
        for (String label : MATURE_LABELS) {
            if (piece.equals(label) || piece.startsWith(label + " ") || piece.endsWith(" " + label)) return true;
        }
        String[] words = piece.split("\\s+");
        if (words.length > 2) return false;
        String first = words[0].replaceAll("[^a-z0-9+]", "");
        if (first.equals("no") || first.equals("not") || first.equals("non")) return false;
        for (int i = 0; i < words.length; i++) {
            String core = words[i].replaceAll("[^a-z0-9+_/]", "");
            if (core.equals("nsfw")) return true;
            if (words.length == 1 && core.contains("nsfw") && !core.contains("nonnsfw")
                    && !core.contains("notnsfw")) return true;
            if (core.equals("18+")) {
                // On its own it is a tag. Next to another word it must be a word tags use
                // ("18+ only"), not a quantity ("18+ hours").
                if (words.length == 1) return true;
                String other = words[1 - i].replaceAll("[^a-z0-9+]", "");
                if (AGE_TAG_WORDS.contains(other)) return true;
            }
        }
        return false;
    }

    private static final Set<String> AGE_TAG_WORDS = new HashSet<>(Arrays.asList(
            "only", "mature", "adult", "adults", "community", "content", "nsfw", "post", "profile"));

    // ---------- site detection ----------

    /** Other names Reddit's pages are served under (copies and front ends). Matched in the site name only. */
    private static final String[] REDDIT_HOST_WORDS = {
            "reddit", "redlib", "libredd", "teddit", "troddit", "reveddit", "removeddit", "unddit", "ceddit"};

    /**
     * The blocked site an address points at, or null. Besides the sites on the list this also
     * catches a blocked site reached through a translating or caching address, and Reddit under
     * its other names.
     */
    private static String siteIn(CharSequence text, Set<String> sites) {
        if (text == null) return null;
        String t = text.toString().trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        for (String s : sites) {
            if (s.isEmpty()) continue;
            if (t.contains(s)) return s;
            if (s.indexOf('.') > 0) {
                String dashed = s.replace('.', '-');
                if (t.contains(dashed + ".translate.goog") || t.contains(dashed + ".cdn.ampproject.org")) {
                    return s;
                }
            }
        }
        if (sites.contains("reddit.com") && t.indexOf(' ') < 0) {
            String host = hostOf(t);
            if (host.indexOf('.') > 0) {
                for (String w : REDDIT_HOST_WORDS) {
                    if (host.contains(w)) return "reddit.com";
                }
            }
        }
        return null;
    }

    /** The site name part of an address: no scheme, path, port or query. */
    private static String hostOf(String address) {
        int scheme = address.indexOf("://");
        if (scheme >= 0) address = address.substring(scheme + 3);
        for (int i = 0; i < address.length(); i++) {
            char ch = address.charAt(i);
            if (ch == '/' || ch == '?' || ch == '#' || ch == ':') return address.substring(0, i);
        }
        return address;
    }

    private static String blockedSiteOnScreen(AccessibilityNodeInfo root, String pkg, Set<String> sites) {
        if (sites.isEmpty()) return null;

        String[] ids = URL_BAR_IDS.get(pkg);
        if (ids != null) {
            for (String id : ids) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (nodes == null) continue;
                for (AccessibilityNodeInfo n : nodes) {
                    if (n == null) continue;
                    String hit = siteIn(n.getText(), sites);
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
                String hit = siteIn(n.getText(), sites);
                if (hit != null) return hit;
            }
            for (int c = 0; c < n.getChildCount(); c++) {
                AccessibilityNodeInfo ch = n.getChild(c);
                if (ch != null) q.add(ch); // getChild can return null; adding it crashed the scan
            }
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

    // ---------- app detection ----------

    /** Every app that can open an ordinary web link is a browser. Re-checked every minute. */
    private Set<String> browserPackages() {
        long now = SystemClock.uptimeMillis();
        if (browsersCheckedAt != 0 && now - browsersCheckedAt < 60_000) return browserPkgs;
        browsersCheckedAt = now;
        PackageManager pm = getPackageManager();
        Set<String> found = new HashSet<>();
        int flags = Build.VERSION.SDK_INT >= 23 ? PackageManager.MATCH_ALL : 0;
        for (String url : new String[]{"https://example.com/", "http://example.com/"}) {
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            List<ResolveInfo> list = pm.queryIntentActivities(web, flags);
            for (ResolveInfo ri : list) {
                if (ri.activityInfo != null) found.add(ri.activityInfo.packageName);
            }
        }
        // Search apps (Google Go, Bing...) open result pages in their own viewer. Every one of them
        // answers Android's "web search" request, so catch them by that, not by name.
        List<ResolveInfo> search = pm.queryIntentActivities(
                new Intent(Intent.ACTION_WEB_SEARCH), flags);
        for (ResolveInfo ri : search) {
            if (ri.activityInfo != null) found.add(ri.activityInfo.packageName);
        }
        found.remove(getPackageName());
        found.remove("android");
        browserPkgs = found;

        // Reddit apps by what they do: an app that opens Reddit links itself, without being able
        // to open ordinary web links, is a Reddit app whatever it is called.
        Set<String> reddit = new HashSet<>();
        try {
            for (String url : new String[]{"https://www.reddit.com/r/all/", "https://reddit.com/r/all/",
                    "https://old.reddit.com/r/all/", "https://redd.it/abc123"}) {
                Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE);
                for (ResolveInfo ri : pm.queryIntentActivities(link, flags)) {
                    if (ri.activityInfo != null) reddit.add(ri.activityInfo.packageName);
                }
            }
            reddit.removeAll(found);
            reddit.remove(getPackageName());
            reddit.remove("android");
        } catch (Throwable t) {
            reddit.clear();
        }
        redditLinkApps = reddit;
        return found;
    }

    private Boolean ledgerTrusted = null;
    private long ledgerCheckedAt = 0;

    private boolean isTrustedOwnApp(String pkg) {
        if (!MainActivity.LEDGER_PKG.equals(pkg)) return false;
        long now = SystemClock.uptimeMillis();
        if (ledgerTrusted == null || now - ledgerCheckedAt > 60_000) {
            ledgerCheckedAt = now;
            try {
                ledgerTrusted = getPackageManager().checkSignatures(getPackageName(), pkg)
                        == PackageManager.SIGNATURE_MATCH;
            } catch (Exception e) {
                ledgerTrusted = false;
            }
        }
        return ledgerTrusted;
    }

    /**
     * True for a non-system app that did not come from an app store, however the file got onto
     * the phone. Apps installed that way before the rule began are on a saved list and keep working.
     */
    private boolean isNewSideloadedApp(String pkg) {
        long now = SystemClock.uptimeMillis();
        if (now - sideloadedCacheAt > 60_000) {
            sideloadedCache.clear();
            sideloadedCacheAt = now;
        }
        Boolean cached = sideloadedCache.get(pkg);
        if (cached != null) return cached;
        boolean r = false;
        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            ApplicationInfo ai = pi.applicationInfo;
            boolean system = ai != null && (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            if (!system && !fromStore(pm, pkg)) {
                if (haveSideloadList()) {
                    // The list pairs each app with the moment it was installed, so removing one
                    // and installing something else under its name doesn't inherit the pass.
                    r = !sideloadAllowed.contains(pkg + "|" + pi.firstInstallTime);
                } else {
                    r = pi.firstInstallTime > rules.sideloadSince();
                }
            }
        } catch (Exception e) {
            r = false;
        }
        sideloadedCache.put(pkg, r);
        return r;
    }

    @SuppressWarnings("deprecation")
    private static boolean fromStore(PackageManager pm, String pkg) throws Exception {
        String installer = Build.VERSION.SDK_INT >= 30
                ? pm.getInstallSourceInfo(pkg).getInstallingPackageName()
                : pm.getInstallerPackageName(pkg);
        return installer != null && STORES.contains(installer);
    }

    /**
     * Loads the list of file-installed apps that predate the rule, or makes it the first time:
     * every app the rule already let through. From then on the list decides, not the phone's
     * clock. Returns false if the list couldn't be made (the date rule is used meanwhile).
     */
    private boolean haveSideloadList() {
        if (rules.sideloadListReady()) {
            if (sideloadAllowed == null) sideloadAllowed = rules.sideloadAllowed();
            return true;
        }
        sideloadAllowed = null;
        long now = SystemClock.uptimeMillis();
        if (sideloadListTriedAt != 0 && now - sideloadListTriedAt < 60_000) return false;
        sideloadListTriedAt = now;
        try {
            PackageManager pm = getPackageManager();
            long cutoff = rules.sideloadSince();
            Set<String> ok = new HashSet<>();
            for (PackageInfo pi : pm.getInstalledPackages(0)) {
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null) continue;
                if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                if (pi.firstInstallTime > cutoff) continue;
                if (fromStore(pm, pi.packageName)) continue;
                ok.add(pi.packageName + "|" + pi.firstInstallTime);
            }
            rules.setSideloadAllowed(ok);
            sideloadAllowed = ok;
            sideloadListTriedAt = 0; // the wait before trying again is only for failures
            sideloadedCache.clear();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True while the Google app is showing Gemini. Decided when its screen changes: Gemini's own
     * screens (class names with robin/gemini/bard) or a screen whose input says "Ask Gemini" or
     * whose title is exactly "Gemini". Anything else (search, a web page) counts as not Gemini.
     */
    private boolean isGeminiScreen(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return geminiInFront;
        CharSequence cls = event.getClassName();
        String c = cls == null ? "" : cls.toString().toLowerCase(Locale.ROOT);
        boolean g = c.contains("robin") || c.contains("gemini") || c.contains("bard");
        if (!g) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null
                    && GOOGLE_APP_PKG.equals(r.getPackageName().toString())) {
                g = treeHintContains(r, "ask gemini") || treeContains(r, "ask gemini");
            }
        }
        geminiInFront = g;
        return g;
    }

    private static boolean treeHintContains(AccessibilityNodeInfo root, String needle) {
        if (Build.VERSION.SDK_INT < 26) return false;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen < MAX_NODES) {
            AccessibilityNodeInfo n = q.poll();
            seen++;
            if (n == null) continue;
            CharSequence h = n.getHintText();
            if (h != null && h.toString().toLowerCase(Locale.ROOT).contains(needle)) return true;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch != null) q.add(ch);
            }
        }
        return false;
    }

    /** Remembers which app the user came from, so a browser tab can be traced back to Claude. */
    private void trackForegroundApp(AccessibilityEvent event, String pkg) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        // The home screen and recents DO count as leaving Claude; only overlays don't.
        if (TAB_BROWSERS.contains(pkg) || TRACK_IGNORE_PKGS.contains(pkg)) return;
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null || r.getPackageName() == null || !pkg.equals(r.getPackageName().toString())) return;
        noteAppInFront(pkg);
    }

    private void noteAppInFront(String pkg) {
        if (!pkg.equals(lastApp)) {
            lastApp = pkg;
            if (!CLAUDE_PKG.equals(pkg)) claudeTabOpen = false;
        }
    }

    /**
     * True while a browser shows a link tab opened from the Claude app. Decided on each screen
     * change: a custom tab right after Claude counts; the browser's full window ("Open in
     * Chrome", its own tabs) or a tab opened from any other app does not.
     */
    private boolean isClaudeLinkTab(AccessibilityEvent event, String pkg) {
        if (!TAB_BROWSERS.contains(pkg)) return false;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return claudeTabOpen;
        CharSequence cls = event.getClassName();
        String c = cls == null ? "" : cls.toString().toLowerCase(Locale.ROOT);
        boolean fromClaude = CLAUDE_PKG.equals(lastApp);
        if (c.contains("customtab")) {
            claudeTabOpen = fromClaude || claudeTabOpen;
        } else if (c.contains("tabbed") || c.contains("main") || c.contains("browseractivity")
                || c.contains("webapp") || c.contains("webapk") || c.contains("trustedweb")) {
            claudeTabOpen = false; // the full browser, or a site saved to the home screen
        } else if (fromClaude) {
            claudeTabOpen = true;  // a launcher/trampoline step on the way to the tab
        }
        return claudeTabOpen;
    }

    /** Apps that call themselves a browser ("Web Browser", "Swift Browser"...). */
    private boolean isNamedLikeBrowser(String pkg) {
        Boolean cached = browserNameCache.get(pkg);
        if (cached != null) return cached;
        String id = pkg.toLowerCase(Locale.ROOT);
        String label = appLabel(pkg).toLowerCase(Locale.ROOT);
        boolean r = id.contains("browser") || label.contains("browser") || label.contains("browse ")
                || id.contains("webbrowser") || label.contains("web surf");
        browserNameCache.put(pkg, r);
        return r;
    }

    private final Map<String, Boolean> installedCache = new HashMap<>();
    private long installedCacheAt = 0;

    /** True if the app is installed in this (main) profile. */
    private boolean isInstalledHere(String pkg) {
        long now = SystemClock.uptimeMillis();
        if (now - installedCacheAt > 60_000) {
            installedCache.clear();
            installedCacheAt = now;
        }
        Boolean cached = installedCache.get(pkg);
        if (cached != null) return cached;
        boolean r;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            r = true;
        } catch (PackageManager.NameNotFoundException e) {
            r = false;
        } catch (Exception e) {
            r = true; // unsure: don't block
        }
        installedCache.put(pkg, r);
        return r;
    }

    /** True for an app that came with the phone. Unknown counts as true. */
    private boolean isSystemApp(String pkg) {
        Boolean cached = systemAppCache.get(pkg);
        if (cached != null) return cached;
        boolean r;
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            r = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (Exception e) {
            r = true;
        }
        systemAppCache.put(pkg, r);
        return r;
    }

    // ---------- web pages inside apps ----------

    private static final Pattern URL_LIKE = Pattern.compile(
            "^(https?://)?([a-z0-9-]+\\.)+[a-z]{2,}(:[0-9]+)?(/\\S*)?$");

    /** A link on a page whose whole text is a subreddit or a Reddit user, like r/name or u/name. */
    private static final Pattern REDDIT_LINK = Pattern.compile("^/?[ru]/[a-z0-9_-]{2,24}/?$");
    /** A page title that begins with a subreddit name. */
    private static final Pattern REDDIT_TITLE_START = Pattern.compile("^/?r/[a-z0-9_]{2,21}($|[\\s:|-])");
    /** How far into a web page to look, and how many different r/ and u/ links make it Reddit. */
    private static final int WEB_NODE_BUDGET = 500;
    private static final int REDDIT_LINKS_NEEDED = 4;

    /**
     * A web page (WebView or GeckoView) on screen that is a blocked site. Returns the site, or
     * null. Two ways to tell: the app's own address bar or title strip names a blocked site, or
     * the page itself is Reddit by its title or its links (for viewers that show no address and
     * for copies of Reddit under other names). Ordinary text and links inside the page are never
     * treated as addresses, so a page that merely lists a blocked site stays open.
     */
    private static String blockedSiteInWebViewer(AccessibilityNodeInfo root, Set<String> sites,
                                                 boolean countLinks) {
        boolean reddit = sites.contains("reddit.com");
        boolean webPage = false;
        String hit = null;
        int pagesRead = 0;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen < 600) {
            AccessibilityNodeInfo n = q.poll();
            seen++;
            if (n == null) continue;
            CharSequence cls = n.getClassName();
            String c = cls == null ? "" : cls.toString();
            if (c.contains("WebView") || c.contains("GeckoView")) {
                webPage = true;
                if (hit != null) return hit;
                if (pagesRead < 3) {
                    pagesRead++;
                    HashSet<String> links = countLinks ? new HashSet<String>() : null;
                    String inside = blockedSiteInsidePage(n, 0, new int[]{WEB_NODE_BUDGET}, links, sites, reddit);
                    if (inside != null) return inside;
                }
                continue; // the page is read by the call above, not by this walk
            }
            if (hit == null) {
                CharSequence t = n.getText();
                if (t != null) {
                    String s = t.toString().trim().toLowerCase(Locale.ROOT);
                    // Only short, link-like text (an address bar or a domain header), not chat
                    // messages or posts that happen to mention a site.
                    if (s.length() <= 120 && !s.contains(" ") && !s.contains("@")
                            && (URL_LIKE.matcher(s).matches() || isAddressBar(n, c))) {
                        hit = siteIn(s, sites);
                    }
                    // A title strip above the page that carries Reddit's page title.
                    if (hit == null && reddit && idContains(n, "title") && isRedditTitle(s)) {
                        hit = "reddit.com";
                    }
                }
            }
            if (webPage && hit != null) return hit;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch != null) q.add(ch);
            }
        }
        return null;
    }

    /**
     * Reads a web page top to bottom, as far as the budget allows. Returns the blocked site it
     * is, or null:
     *  - "reddit.com" if the page's title is one of Reddit's, or (when links is given) the page
     *    carries several different r/name and u/name links;
     *  - a blocked site typed into an address-like field inside the page (a viewer whose
     *    address bar is part of the page).
     * Ordinary text and links are not addresses and are ignored.
     */
    private static String blockedSiteInsidePage(AccessibilityNodeInfo n, int depth, int[] budget,
                                                HashSet<String> links, Set<String> sites, boolean reddit) {
        if (n == null || budget[0] <= 0 || depth > 60) return null;
        budget[0]--;
        String t = lower(n.getText());
        if (reddit) {
            String d = lower(n.getContentDescription());
            // The page's title sits on the page's top node (the viewer itself, or the page node
            // directly inside it). Text further in is the page's content, not its title.
            if ((depth == 0 || (depth <= 2 && isWebNode(n))) && (isRedditTitle(t) || isRedditTitle(d))) {
                return "reddit.com";
            }
            if (links != null) {
                if (t != null && t.length() <= 30 && REDDIT_LINK.matcher(t).matches()) links.add(t);
                if (d != null && d.length() <= 30 && REDDIT_LINK.matcher(d).matches()) links.add(d);
                if (links.size() >= REDDIT_LINKS_NEEDED) return "reddit.com";
            }
        }
        if (depth > 0 && t != null && t.length() <= 120 && t.indexOf(' ') < 0 && t.indexOf('@') < 0) {
            CharSequence cls = n.getClassName();
            if (isAddressBar(n, cls == null ? "" : cls.toString())) {
                String hit = siteIn(t, sites);
                if (hit != null) return hit;
            }
        }
        int count = Math.min(n.getChildCount(), 80);
        for (int i = 0; i < count && budget[0] > 0; i++) {
            String hit = blockedSiteInsidePage(n.getChild(i), depth + 1, budget, links, sites, reddit);
            if (hit != null) return hit;
        }
        return null;
    }

    private static boolean isWebNode(AccessibilityNodeInfo n) {
        CharSequence cls = n.getClassName();
        if (cls == null) return false;
        String c = cls.toString();
        return c.contains("WebView") || c.contains("GeckoView");
    }

    private static String lower(CharSequence cs) {
        return cs == null ? null : cs.toString().trim().toLowerCase(Locale.ROOT);
    }

    /** Page titles Reddit and its copies use: the home page, "post : r/name", "r/name", "... - Reddit". */
    private static boolean isRedditTitle(String t) {
        if (t == null || t.isEmpty() || t.length() > 300) return false;
        if (t.equals("reddit") || t.equals("redlib") || t.equals("libreddit") || t.equals("teddit")) return true;
        if (t.startsWith("reddit - the heart of the internet")
                || t.startsWith("reddit - dive into anything")
                || t.startsWith("reddit: the front page of the internet")) return true;
        if (t.endsWith(" - reddit") || t.endsWith(" | reddit") || t.endsWith(" - reddit search!")
                || t.endsWith(" - redlib") || t.endsWith(" - libreddit") || t.endsWith(" - teddit")) return true;
        if (t.contains(" : r/") || t.contains(" - r/") || t.contains(" | r/") || t.contains("(u/")) return true;
        return REDDIT_TITLE_START.matcher(t).find();
    }

    private static boolean idContains(AccessibilityNodeInfo n, String word) {
        String id = n.getViewIdResourceName();
        return id != null && id.toLowerCase(Locale.ROOT).contains(word);
    }

    /** A web page (WebView or GeckoView) on screen together with something that looks like an address bar. */
    private static boolean looksLikeBrowserScreen(AccessibilityNodeInfo root) {
        // First the app's own controls only, so a long page can't crowd the address bar out of
        // the scan; then the old way, page included.
        return browserScreenScan(root, false) || browserScreenScan(root, true);
    }

    private static boolean browserScreenScan(AccessibilityNodeInfo root, boolean intoPages) {
        boolean webPage = false;
        boolean addressBar = false;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen < 600) {
            AccessibilityNodeInfo n = q.poll();
            seen++;
            if (n == null) continue;
            CharSequence cls = n.getClassName();
            String c = cls == null ? "" : cls.toString();
            boolean page = c.contains("WebView") || c.contains("GeckoView");
            if (page) webPage = true;
            if (!addressBar && isAddressBar(n, c)) addressBar = true;
            if (webPage && addressBar) return true;
            if (page && !intoPages) continue;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch != null) q.add(ch);
            }
        }
        return false;
    }

    private static boolean isAddressBar(AccessibilityNodeInfo n, String cls) {
        String id = n.getViewIdResourceName();
        if (id != null) {
            String i = id.toLowerCase(Locale.ROOT);
            if (i.contains("url") || i.contains("omnibar") || i.contains("address")
                    || i.contains("location_bar") || i.contains("search_box_url")) return true;
        }
        if (!cls.endsWith("EditText")) return false;
        CharSequence t = n.getText();
        if (t != null) {
            String s = t.toString().trim().toLowerCase(Locale.ROOT);
            if (!s.contains(" ") && !s.contains("@") && URL_LIKE.matcher(s).matches()) return true;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            CharSequence h = n.getHintText();
            if (h != null) {
                String hint = h.toString().toLowerCase(Locale.ROOT);
                if (hint.contains("url") || hint.contains("web address") || hint.contains("search or type")
                        || hint.contains("type address") || hint.contains("enter address")) return true;
            }
        }
        return false;
    }

    // ---------- Reddit apps ----------

    private static final String[] REDDIT_APP_IDS = {
            "reddit", "redreader", "baconreader", "redlib", "libreddit", "teddit",
            "saket.dank", "o.o.joey", "cygnusx1.continuum"};
    private static final String[] REDDIT_APP_LABELS = {
            "reddit", "redreader", "baconreader", "redlib", "rif is fun"};

    /** Other Reddit apps: by name, or by being the app that opens Reddit links. */
    private boolean isRedditClient(String pkg) {
        if (pkg.equals(OFFICIAL_REDDIT)) return false; // handled by the normal app list
        Boolean cached = redditClientCache.get(pkg);
        if (cached != null) return cached;
        String id = pkg.toLowerCase(Locale.ROOT);
        String label = appLabel(pkg).toLowerCase(Locale.ROOT);
        boolean r = false;
        for (String w : REDDIT_APP_IDS) {
            if (id.contains(w)) r = true;
        }
        for (String w : REDDIT_APP_LABELS) {
            if (label.contains(w)) r = true;
        }
        if (!r) {
            browserPackages(); // refreshes redditLinkApps too
            r = redditLinkApps.contains(pkg);
        }
        redditClientCache.put(pkg, r);
        return r;
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
            for (int c = 0; c < n.getChildCount(); c++) {
                AccessibilityNodeInfo ch = n.getChild(c);
                if (ch != null) q.add(ch); // getChild can return null; adding it crashed the scan
            }
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
