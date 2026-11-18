package com.aaron.holdfast;

import android.Sim;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import java.util.*;

/** Whole-flow simulations of BlockerService against stand-in Android classes. */
public class Scenarios {
    static int fails = 0, passes = 0;
    static BlockerService svc;
    static final long CUTOFF = 1793271600000L;
    static final String SETTINGS = "com.android.settings", INSTALLER = "com.google.android.packageinstaller",
            CHROME = "com.android.chrome", CLAUDE = "com.anthropic.claude", GOOGLE = "com.google.android.googlequicksearchbox",
            LAUNCHER = "com.sec.android.app.launcher", SYSUI = "com.android.systemui", MSGR = "com.facebook.orca",
            REDDIT = "com.reddit.frontpage", STORE = "com.android.vending", LOOL = "com.samsung.android.lool";
    static final String TAB = "org.chromium.chrome.browser.customtabs.CustomTabActivity";

    public static class Node extends AccessibilityNodeInfo {
        String cls, text, desc, id, hint, pkg;
        List<Node> kids = new ArrayList<>();
        Node(String cls, String text) { this.cls = cls; this.text = text; }
        Node id(String v) { id = v; return this; }
        Node hint(String v) { hint = v; return this; }
        Node add(Node... ns) { kids.addAll(Arrays.asList(ns)); return this; }
        @Override public CharSequence getClassName() { return cls; }
        @Override public CharSequence getText() { return text; }
        @Override public CharSequence getContentDescription() { return desc; }
        @Override public CharSequence getHintText() { return hint; }
        @Override public String getViewIdResourceName() { return id; }
        @Override public CharSequence getPackageName() { return pkg; }
        @Override public int getChildCount() { return kids.size(); }
        @Override public AccessibilityNodeInfo getChild(int i) { return kids.get(i); }
        @Override public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewId(String want) {
            List<AccessibilityNodeInfo> out = new ArrayList<>();
            collect(this, want, out);
            return out;
        }
        static void collect(Node n, String want, List<AccessibilityNodeInfo> out) {
            if (want.equals(n.id)) out.add(n);
            for (Node k : n.kids) collect(k, want, out);
        }
    }
    static Node view(Node... kids) { return new Node("android.widget.FrameLayout", null).add(kids); }
    static Node tv(String t) { return new Node("android.widget.TextView", t); }
    static Node edit(String t) { return new Node("android.widget.EditText", t); }
    static Node web(String title, Node... kids) { return new Node("android.webkit.WebView", title).add(kids); }
    static Node wv(String t) { return new Node("android.view.View", t); }
    static Node page(String... texts) { Node v = view(); for (String t : texts) v.add(tv(t)); return v; }
    static Node chromeTab(String url, String title) {
        return view(tv(url).id("com.android.chrome:id/url_bar"), web(title, wv("page text")));
    }

    static void fresh() {
        Sim.reset();
        Context.prefs.clear();
        Toast.shown.clear();
        BlockerService.instance = null;
        svc = new BlockerService();
        PackageManager pm = svc.pm;
        pm.add("com.aaron.holdfast", "Holdfast", false, INSTALLER, CUTOFF - 5000);
        pm.add(REDDIT, "Reddit", false, STORE, CUTOFF + 1000);
        pm.add(CHROME, "Chrome", true, null, 0);
        pm.add("com.sec.android.app.sbrowser", "Samsung Internet", true, null, 0);
        pm.add(GOOGLE, "Google", true, null, 0);
        pm.add("com.google.android.apps.bard", "Gemini", false, STORE, CUTOFF + 1000);
        pm.add(CLAUDE, "Claude", false, STORE, CUTOFF - 1000);
        pm.add(SETTINGS, "Settings", true, null, 0);
        pm.add(LOOL, "Device care", true, null, 0);
        pm.add(INSTALLER, "Package installer", true, null, 0);
        pm.add(SYSUI, "System UI", true, null, 0);
        pm.add("com.samsung.android.globalactions", "Power menu", true, null, 0);
        pm.add(LAUNCHER, "One UI Home", true, null, 0);
        pm.add("com.whatsapp", "WhatsApp", false, STORE, CUTOFF + 1000);
        pm.add(MSGR, "Messenger", false, STORE, CUTOFF + 1000);
        pm.add("com.example.fastnet", "Fast Net", false, STORE, CUTOFF + 1000);
        pm.add("com.example.swift", "Swift Browser", false, STORE, CUTOFF + 1000);
        pm.add("com.example.snoo", "Snoo Reader", false, STORE, CUTOFF + 1000);
        pm.add("com.old.sideload", "OldTool", false, null, CUTOFF - 1000);
        pm.add("com.new.sideload", "NewTool", false, "com.sec.android.app.myfiles", CUTOFF + 1000);
        pm.add("com.aaron.riyalledger", "Riyal Ledger", false, INSTALLER, CUTOFF + 1000);
        pm.add("com.samsung.android.maintenance", "Maintenance mode", true, null, 0);
        pm.browsers.addAll(Arrays.asList(CHROME, "com.sec.android.app.sbrowser", "com.example.fastnet"));
        pm.searchApps.add(GOOGLE);
        pm.redditApps.addAll(Arrays.asList(REDDIT, "com.example.snoo"));
        Node home = view(tv("Home screen"));
        home.pkg = LAUNCHER;
        svc.homeRoot = home;
        svc.onServiceConnected();
    }

    static void fire(String pkg, int type, String cls, Node root, String rootPkg) {
        if (root != null) root.pkg = rootPkg;
        svc.fakeRoot = root;
        svc.onAccessibilityEvent(new AccessibilityEvent(pkg, type, cls));
    }
    static void win(String pkg, String cls, Node root) { fire(pkg, 32, cls, root, pkg); }
    static void content(String pkg, Node root) { fire(pkg, 2048, null, root, pkg); }
    /** What Holdfast did since the last call: actions, then the reason on the block screen. */
    static String did() {
        String a = String.join(",", svc.actions);
        StringBuilder r = new StringBuilder();
        for (Intent i : svc.started) r.append("|").append(i.extras.get("reason"));
        svc.actions.clear();
        svc.started.clear();
        return a + r;
    }
    static void eq(String what, Object got, Object want) {
        if (Objects.equals(got, want)) passes++;
        else { fails++; System.out.println("FAIL " + what + "\n   got : " + got + "\n   want: " + want); }
    }
    static void starts(String what, String got, String wantPrefix) {
        if (got != null && got.startsWith(wantPrefix)) passes++;
        else { fails++; System.out.println("FAIL " + what + "\n   got : " + got + "\n   want prefix: " + wantPrefix); }
    }
    static void noError(String where) {
        String e = new Rules(svc).lastError();
        if (e != null) { fails++; System.out.println("FAIL hidden error in " + where + ": " + e); }
        else passes++;
    }

    public static void main(String[] a) throws Exception {
        // ---- apps ----
        fresh();
        win(REDDIT, "Main", view(tv("Home")));
        eq("blocked app", did(), "HOME|Reddit is blocked.");
        Sim.advance(1000);
        win("com.whatsapp", "Main", view(tv("Chats")));
        eq("ordinary app", did(), "");
        win("com.example.fastnet", "Main", view());
        eq("browser by function", did(), "HOME|Fast Net is blocked (all browsers are).");
        Sim.advance(1000);
        win("com.example.swift", "Main", view());
        eq("browser by name", did(), "HOME|Swift Browser is blocked (all browsers are).");
        Sim.advance(1000);
        win("com.example.snoo", "Main", view());
        eq("reddit app by function", did(), "HOME|Snoo Reader is a Reddit app, and Reddit is blocked.");
        Sim.advance(1000);
        win("com.new.sideload", "Main", view());
        eq("new file-installed app", did(), "HOME|NewTool was installed from a file, not an app store, so it's blocked.");
        Sim.advance(1000);
        win("com.old.sideload", "Main", view());
        eq("old file-installed app", did(), "");
        // installed later with the clock set back: the saved list decides, not the date
        svc.pm.add("com.fake.old", "Sneaky", false, null, CUTOFF - 999999);
        for (android.content.BroadcastReceiver r : new ArrayList<>(svc.receivers)) r.onReceive(svc, new Intent());
        win("com.fake.old", "Main", view());
        eq("backdated install", did(), "HOME|Sneaky was installed from a file, not an app store, so it's blocked.");
        Sim.advance(1000);
        // an allowed old app removed and something else installed under its name
        svc.pm.add("com.old.sideload", "OldTool", false, null, CUTOFF - 500);
        for (android.content.BroadcastReceiver r : new ArrayList<>(svc.receivers)) r.onReceive(svc, new Intent());
        win("com.old.sideload", "Main", view());
        eq("replaced old app", did(), "HOME|OldTool was installed from a file, not an app store, so it's blocked.");
        Sim.advance(1000);
        win("com.unknown.securefolder", "Main", view());
        starts("app outside main profile", did(), "HOME|This app runs outside your main profile");
        Sim.advance(1000);
        win("com.aaron.riyalledger", "LedgerActivity", view(web("Reddit - The heart of the internet")));
        eq("ledger trusted", did(), "");
        noError("apps");

        // ---- pinned app ----
        fresh();
        svc.am.lockTask = 2;
        win(REDDIT, "Main", view());
        eq("pinned: lock instead of home", did(), "BACK,LOCK");
        eq("pinned: toast", Toast.shown.size(), 1);
        noError("pinned");

        // ---- debounce ----
        fresh();
        win(REDDIT, "Main", view());
        content(REDDIT, view());
        eq("same block twice", did(), "HOME|Reddit is blocked.");
        win("com.example.swift", "Main", view());
        eq("another app right after: same moment, one block", did(), "");
        Sim.advance(900);
        content("com.example.swift", view());
        eq("another app a moment later", did(), "HOME|Swift Browser is blocked (all browsers are).");
        // a protected page straight after another block is never skipped
        win(SETTINGS, "SubSettings", page("Holdfast", "On", "Holdfast shortcut", "Off"));
        starts("protected page right after a block", did(), "BACK,HOME|Holdfast's settings are protected");
        // going home is slow: the same screen is still in front for the next looks
        Sim.advance(3000); did();
        svc.homeWorks = false;
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        win(CHROME, TAB, chromeTab("reddit.com/r/all", "x"));
        Sim.advance(600);
        starts("slow home: one block, not two", did(), "HOME|reddit.com is blocked, in every app.");
        Sim.advance(3000);
        eq("slow home: blocked again while it is still there", did().contains("reddit.com is blocked"), true);
        svc.homeWorks = true;
        noError("debounce");

        // ---- Claude link tabs ----
        fresh();
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        win(CHROME, TAB, chromeTab("example.com", "Example Domain"));
        eq("claude tab opens", did(), "");
        Sim.advance(500);
        content(CHROME, chromeTab("example.com", "Example Domain"));
        eq("claude tab stays", did(), "");
        Sim.advance(3000);
        eq("claude tab after rechecks", did(), "");
        content(CHROME, chromeTab("reddit.com/r/all", "whatever"));
        starts("claude tab reddit address", did(), "HOME|reddit.com is blocked");
        Sim.advance(2000);
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        win(CHROME, TAB, chromeTab("somewhere.example", "Best phone? : r/Android"));
        starts("claude tab reddit copy by title", did(), "HOME|reddit.com is blocked");
        Sim.advance(2000);
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        win(CHROME, TAB, chromeTab("www-reddit-com.translate.goog", "x"));
        starts("claude tab reddit via translate", did(), "HOME|reddit.com is blocked");
        Sim.advance(2000);
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        win(CHROME, "com.google.android.apps.chrome.Main", chromeTab("example.com", "x"));
        eq("full chrome from claude", did(), "HOME|Chrome is blocked.");
        Sim.advance(2000);
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        win(CHROME, "org.chromium.chrome.browser.webapps.WebappActivity", chromeTab("example.com", "x"));
        eq("saved site from claude", did(), "HOME|Chrome is blocked.");
        Sim.advance(2000);
        win(LAUNCHER, "Launcher", view());
        win(CHROME, TAB, chromeTab("example.com", "x"));
        eq("tab not from claude", did(), "HOME|Chrome is blocked.");
        Sim.advance(2000);
        win("com.whatsapp", "Main", view(tv("Chats")));
        win(CHROME, TAB, chromeTab("example.com", "x"));
        eq("tab from another app", did(), "HOME|Chrome is blocked.");
        Sim.advance(2000);
        // leaving the tab for the home screen and coming back to it
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        win(CHROME, TAB, chromeTab("example.com", "x"));
        win(LAUNCHER, "Launcher", view());
        win(CHROME, TAB, chromeTab("example.com", "x"));
        eq("tab reopened from recents", did(), "HOME|Chrome is blocked.");
        noError("claude tabs");

        // ---- Google app and Gemini ----
        fresh();
        String ROBIN = "com.google.android.apps.search.assistant.surfaces.voice.robin.main.MainActivity";
        win(GOOGLE, ROBIN, view(edit(null).hint("Ask Gemini")));
        Sim.advance(3000);
        content(GOOGLE, view(edit("hello").hint("Ask Gemini"), tv("An answer")));
        eq("gemini allowed", did(), "");
        win(GOOGLE, "com.google.android.apps.search.SearchActivity", view(edit("cats"), tv("Results")));
        eq("google search: not at once", did(), "");
        Sim.advance(400);
        eq("google search: after the wait", did(), "HOME|Google is blocked (all browsers are).");
        Sim.advance(2000);
        win(GOOGLE, "com.google.android.Splash", view());
        Sim.advance(100);
        win(GOOGLE, ROBIN, view(edit(null).hint("Ask Gemini")));
        Sim.advance(3000);
        eq("step on the way to gemini", did(), "");
        content(GOOGLE, view(edit("x").id("com.google.android.googlequicksearchbox:id/url"), web("Some page", wv("t"))));
        eq("web page inside gemini", did(), "HOME|Web pages in the Google app are blocked (Gemini itself is fine).");
        Sim.advance(2000);
        win(GOOGLE, "com.google.android.apps.search.SearchActivity", view(edit("cats")));
        content(GOOGLE, view(edit("cats")));
        eq("google search content during wait", did(), "");
        Sim.advance(300);
        eq("google search blocked once", did(), "HOME|Google is blocked (all browsers are).");
        Sim.advance(2000);
        win("com.google.android.apps.bard", "Main", view(tv("Gemini")));
        eq("gemini app itself", did(), "");
        noError("google");

        // ---- Settings ----
        fresh();
        win(SETTINGS, "Settings", page("Settings", "Connections", "Battery", "Apps", "Accessibility", "Developer options"));
        Sim.advance(3000);
        eq("settings main", did(), "");
        win(SETTINGS, "SubSettings", page("Holdfast", "On", "Holdfast shortcut", "Off"));
        eq("holdfast page", did(), "BACK,HOME|Holdfast's settings are protected. Turning protection off takes 24 hours.");
        Sim.advance(3000);
        eq("holdfast page: no repeat after it closed", did(), "");
        win(SETTINGS, "SubSettings", page("Reset accessibility settings", "Your accessibility settings will be reset to defaults.", "Reset settings"));
        eq("reset page", did(), "BACK,HOME|Resetting settings is blocked while tamper protection is on.");
        Sim.advance(3000); did();
        // page that fills in after it opens, with no further events
        Node late = page("Loading");
        win(SETTINGS, "SubSettings", late);
        eq("empty page", did(), "");
        late.add(tv("Language"), tv("Add language"));
        Sim.advance(200);
        eq("late content caught by the second look", did(), "BACK,HOME|Changing the phone's language is blocked while tamper protection is on.");
        Sim.advance(3000); did();
        // system UI event while a protected page is in front
        Node dev = page("Developer options", "On", "Bug report", "Stay awake");
        fire(SYSUI, 2048, null, dev, SETTINGS);
        eq("protected page seen through a system event", did(), "BACK,HOME|Developer options are protected while tamper protection is on.");
        Sim.advance(3000); did();
        // device care
        win(LOOL, "DeviceCare", page("Device care", "Battery", "Storage", "Memory", "Maintenance mode"));
        Sim.advance(3000);
        eq("device care", did(), "");
        win(LOOL, "Maint", page("Maintenance mode", "Keep your personal information private while your phone is being repaired.", "Turn on"));
        eq("maintenance mode", did(), "BACK,HOME|Maintenance mode is blocked while tamper protection is on.");
        Sim.advance(3000); did();
        win("com.samsung.android.maintenance", "Maint", page("Maintenance mode", "…", "Turn on"));
        eq("maintenance mode in its own app", did(), "HOME|Maintenance mode is blocked while tamper protection is on.");
        Sim.advance(3000); did();
        // sleeping lists then their picker
        win(LOOL, "Limits", page("Background usage limits", "Put unused apps to sleep", "Sleeping apps", "Deep sleeping apps", "Never auto sleeping apps"));
        Sim.advance(3000);
        eq("limits menu", did(), "");
        win(LOOL, "List", page("Deep sleeping apps", "0 apps"));
        eq("deep sleeping list", did(), "BACK,HOME|Sleeping-app lists are protected while tamper protection is on.");
        Sim.advance(3000); did();
        win(LOOL, "Picker", page("Select apps", "All", "WhatsApp"));
        eq("picker right after", did(), "BACK,HOME|Sleeping-app lists are protected while tamper protection is on.");
        Sim.advance(120000); did();
        win(SETTINGS, "Picker", page("Select apps", "All", "WhatsApp"));
        Sim.advance(3000);
        eq("unrelated picker later", did(), "");
        // power menu
        win("com.samsung.android.globalactions", "Dialog", page("Power off", "Safe mode"));
        eq("safe mode", did(), "BACK,HOME|Safe mode is blocked while tamper protection is on.");
        noError("settings");

        // ---- setup windows ----
        fresh();
        svc.dpm.active = false;
        new Rules(svc).openSetupWindow();
        win(SETTINGS, "DeviceAdminAdd", page("Holdfast", "Activate this device admin app?", "Activate", "Cancel"));
        Sim.advance(3000);
        eq("admin setup allowed", did(), "");
        svc.dpm.active = true;
        win(SETTINGS, "DeviceAdminAdd", page("Holdfast", "Deactivate this device admin app", "Cancel"));
        starts("admin page once active", did(), "BACK,HOME|Holdfast's settings are protected");
        Sim.advance(3000); did();
        Node unknownSources = page("Install unknown apps", "Holdfast", "Allow from this source",
                "By installing apps from this source, you agree that you are responsible.");
        win(SETTINGS, "ManageExternalSources", unknownSources);
        eq("install permission page, no window", did(), "BACK,HOME|Letting apps install other apps is blocked while installs from files are.");
        Sim.advance(3000); did();
        new Rules(svc).openInstallPermissionWindow();
        win(SETTINGS, "ManageExternalSources", unknownSources);
        Sim.advance(3000);
        eq("install permission page in window", did(), "");
        noError("setup windows");

        // ---- installer ----
        fresh();
        Node install = page("WhatsApp", "Do you want to install this app?", "Cancel", "Install");
        win(INSTALLER, "PackageInstallerActivity", install);
        eq("install screen", did(), "BACK,HOME|Installing apps from files is blocked. Use the Play Store.");
        Sim.advance(3000); did();
        win(INSTALLER, "PackageInstallerActivity", page("Easy Uninstaller", "Do you want to install this app?", "Cancel", "Install"));
        eq("install screen, app named uninstaller", did(), "BACK,HOME|Installing apps from files is blocked. Use the Play Store.");
        Sim.advance(3000); did();
        win(INSTALLER, "UninstallerActivity", page("WhatsApp", "Do you want to uninstall this app?", "Cancel", "OK"));
        Sim.advance(3000);
        eq("uninstalling another app", did(), "");
        win(INSTALLER, "UninstallerActivity", page("Holdfast", "Do you want to uninstall this app?", "Cancel", "OK"));
        starts("uninstalling holdfast", did(), "BACK,HOME|Uninstalling Holdfast is blocked");
        Sim.advance(3000); did();
        new Rules(svc).openSelfUpdateWindow();
        win(INSTALLER, "PackageInstallerActivity", page("Holdfast", "Do you want to update this app?", "Cancel", "Update"));
        Sim.advance(3000);
        content(INSTALLER, page("Installing…"));
        content(INSTALLER, page("App installed.", "Done", "Open"));
        eq("own update passes", did(), "");
        Sim.advance(130000);
        win(INSTALLER, "PackageInstallerActivity", install);
        eq("install screen after own update window", did(), "BACK,HOME|Installing apps from files is blocked. Use the Play Store.");
        noError("installer");

        // ---- web pages inside apps ----
        fresh();
        win(MSGR, "BrowserLiteActivity", view(tv("Done"), web("Reddit - The heart of the internet", wv("feed"))));
        eq("viewer without address, reddit title", did(), "HOME|reddit.com is blocked, in every app.");
        Sim.advance(3000); did();
        win(MSGR, "BrowserLiteActivity", view(tv("news.example"), web("News", wv("story"))));
        Sim.advance(3000);
        eq("viewer on another site", did(), "");
        // page still loading at the first look, loaded just after, then no more events
        Node loading = view(tv("Done"), web("Loading", wv("…")));
        win(MSGR, "BrowserLiteActivity", loading);
        Sim.advance(300);
        content(MSGR, view(tv("Done"), web("A post : r/pics", wv("…"))));
        eq("just-loaded page: not yet", did(), "");
        Sim.advance(1200);
        eq("just-loaded page: caught by the later look", did(), "HOME|reddit.com is blocked, in every app.");
        Sim.advance(3000); did();
        win("com.google.android.apps.bard", "Main", view(web("r/all", wv("x"))));
        eq("gemini app showing reddit", did(), "HOME|reddit.com is blocked, in every app.");
        Sim.advance(3000); did();
        win(CLAUDE, "MainActivity", view(tv("reddit.com"), tv("Here are some subreddits: r/a, r/b")));
        Sim.advance(3000);
        eq("chat that mentions reddit", did(), "");
        noError("web");

        // ---- another phone language ----
        fresh();
        svc.res.c.l.first = new Locale("fil");
        win(SETTINGS, "SubSettings", page("Mga setting", "Baterya"));
        Sim.advance(3000);
        eq("other language: ordinary page", did(), "");
        win(SETTINGS, "SubSettings", page("Holdfast", "Naka-on"));
        starts("other language: holdfast page", did(), "BACK,HOME|Holdfast's settings are protected");
        Sim.advance(3000); did();
        win(INSTALLER, "PackageInstallerActivity", page("WhatsApp", "Gusto mo bang i-install ang app na ito?", "Kanselahin", "I-install"));
        eq("other language: installer", did(), "BACK,HOME|Installing apps from files is blocked. Use the Play Store.");
        noError("language");

        // ---- events that arrive before the new screen can be read ----
        fresh();
        Node chat = view(tv("Chat")); chat.pkg = CLAUDE;
        win("com.whatsapp", "Main", view(tv("Chats")));
        // Claude's event arrives while WhatsApp is still the readable screen
        fire(CLAUDE, 32, "MainActivity", svc.fakeRoot == null ? null : (Node) svc.fakeRoot, "com.whatsapp");
        svc.fakeRoot = chat;
        Sim.advance(100);
        win(CHROME, TAB, chromeTab("example.com", "Example"));
        Sim.advance(3000);
        eq("tab from claude, claude noticed late", did(), "");
        // a protected page whose event arrives early
        Node homeNow = view(tv("Home screen")); homeNow.pkg = LAUNCHER;
        Node protectedPage = page("Reset all settings", "This will reset all settings except:", "Reset settings");
        protectedPage.pkg = SETTINGS;
        fire(SETTINGS, 32, "SubSettings", homeNow, LAUNCHER);
        eq("protected page, not readable yet", did(), "");
        svc.fakeRoot = protectedPage;
        Sim.advance(100);
        eq("protected page, read a moment later", did(), "BACK,HOME|Resetting settings is blocked while tamper protection is on.");
        Sim.advance(3000); did();
        // an app outside the main profile whose event arrives early
        Node sf = view(tv("Private")); sf.pkg = "com.unknown.securefolder";
        fire("com.unknown.securefolder", 32, "Main", homeNow, LAUNCHER);
        eq("outside app, not readable yet", did(), "");
        svc.fakeRoot = sf;
        Sim.advance(100);
        starts("outside app, read a moment later", did(), "HOME|This app runs outside your main profile");
        Sim.advance(3000); did();
        // gemini screen recognised by its wording only, readable a moment after its event
        Node gem = view(edit(null).hint("Ask Gemini")); gem.pkg = GOOGLE;
        fire(GOOGLE, 32, "com.google.SomeNewScreen", homeNow, LAUNCHER);
        svc.fakeRoot = gem;
        Sim.advance(3000);
        content(GOOGLE, gem);
        eq("gemini by wording, read late", did(), "");
        noError("early events");

        // ---- more link tab sequences ----
        fresh();
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        fire(SYSUI, 32, "NotificationShade", view(tv("Notifications")), SYSUI);
        fire("com.samsung.android.honeyboard", 32, "Keyboard", view(tv("q")), CLAUDE);
        win(CHROME, TAB, chromeTab("example.com", "Example"));
        win(CHROME, "android.widget.FrameLayout", chromeTab("example.com", "Example"));
        fire(CHROME, 2048, null, chromeTab("example.com", "Example"), CHROME);
        Sim.advance(3000);
        eq("tab after shade and keyboard, with a menu popup", did(), "");
        // page that loads a moment after the tab opens
        Sim.advance(2000);
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        win(CHROME, TAB, chromeTab("somewhere.example", "Loading"));
        Sim.advance(300);
        content(CHROME, chromeTab("somewhere.example", "A post : r/pics"));
        eq("tab still loading", did(), "");
        Sim.advance(1500);
        starts("tab finished loading a reddit copy", did(), "HOME|reddit.com is blocked");
        Sim.advance(3000); did();
        String SB = "com.sec.android.app.sbrowser";
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        win(SB, "com.sec.android.app.sbrowser.customtabs.CustomTabActivity",
                view(tv("old.reddit.com").id("com.sec.android.app.sbrowser:id/custom_tab_toolbar_url_bar_text"), web("x")));
        starts("samsung internet tab on reddit", did(), "HOME|reddit.com is blocked");
        noError("more tabs");

        // ---- a browser installed mid-session ----
        fresh();
        svc.pm.add("com.late.zippy", "Zippy", false, STORE, CUTOFF + 5000);
        win("com.late.zippy", "Main", view());
        eq("not a browser yet", did(), "");
        svc.pm.browsers.add("com.late.zippy");
        for (android.content.BroadcastReceiver r : new ArrayList<>(svc.receivers)) r.onReceive(svc, new Intent());
        content("com.late.zippy", view());
        eq("browser noticed as soon as apps change", did(), "HOME|Zippy is blocked (all browsers are).");
        noError("late browser");

        // ---- rules switched off (after their 24 hours) ----
        fresh();
        Context.prefs.get("rules").putBoolean("allBrowsers", false);
        String SB2 = "com.sec.android.app.sbrowser";
        win(SB2, "com.sec.android.app.sbrowser.SBrowserMainActivity",
                view(edit("example.com").id("com.sec.android.app.sbrowser:id/location_bar_edit_text"), web("Example")));
        Sim.advance(3000);
        eq("browsers allowed: other site", did(), "");
        content(SB2, view(edit("reddit.com/r/all").id("com.sec.android.app.sbrowser:id/location_bar_edit_text"), web("x")));
        starts("browsers allowed: reddit still blocked", did(), "HOME|reddit.com is blocked");
        Sim.advance(3000); did();
        win(GOOGLE, "com.google.android.apps.search.SearchActivity", view(edit("cats")));
        Sim.advance(3000);
        eq("browsers allowed: google app", did(), "");
        Context.prefs.get("rules").putBoolean("guard", false);
        win(SETTINGS, "SubSettings", page("Holdfast", "On", "Holdfast shortcut", "Off"));
        Sim.advance(3000);
        eq("protection off: holdfast page opens", did(), "");
        win("com.unknown.securefolder", "Main", view());
        Sim.advance(3000);
        eq("protection off: app outside main profile", did(), "");
        win(SETTINGS, "ManageExternalSources", page("Install unknown apps", "My Files", "Allow from this source"));
        eq("protection off: install permission still blocked", did(), "BACK,HOME|Letting apps install other apps is blocked while installs from files are.");
        Sim.advance(3000); did();
        Context.prefs.get("rules").putBoolean("noSideload", false);
        svc.pm.add("com.during.off", "DuringOff", false, null, CUTOFF + 9000);
        win("com.during.off", "Main", view());
        win("com.new.sideload", "Main", view());
        Sim.advance(3000);
        eq("install rule off: file-installed apps open", did(), "");
        new Rules(svc).blockSideload();
        win("com.during.off", "Main", view());
        Sim.advance(3000);
        eq("install rule back on: what was there stays", did(), "");
        svc.pm.add("com.after.on", "AfterOn", false, null, CUTOFF - 5);
        win("com.after.on", "Main", view());
        eq("install rule back on: new one blocked", did(), "HOME|AfterOn was installed from a file, not an app store, so it's blocked.");
        noError("rules off");

        // ---- odds and ends ----
        fresh();
        svc.pm.ledgerSigned = false;
        win("com.aaron.riyalledger", "LedgerActivity", view());
        eq("ledger with another signature", did(), "HOME|Riyal Ledger was installed from a file, not an app store, so it's blocked.");
        Sim.advance(3000); did();
        win(CLAUDE, "MainActivity", page("Maintenance mode", "Turn on", "Restart"));
        Sim.advance(3000);
        eq("chat that talks about maintenance mode", did(), "");
        win(SETTINGS, "SubSettings", page("Apps", "Your apps", "Holdfast", "Riyal Ledger", "WhatsApp"));
        Sim.advance(3000);
        eq("apps list", did(), "");
        noError("odds");

        System.out.println(passes + " passed, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
