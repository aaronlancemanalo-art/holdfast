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
        int[] box; // left, top, right, bottom on a 1080 x 2340 screen; null = the whole screen
        List<Node> kids = new ArrayList<>();
        Node box(int l, int t, int r, int b) { box = new int[]{l, t, r, b}; return this; }
        @Override public void getBoundsInScreen(android.graphics.Rect out) {
            if (box == null) out.set(0, 0, 1080, 2340); else out.set(box[0], box[1], box[2], box[3]);
        }
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

        // ---- Reddit allowed, mature content not ----
        fresh();
        Node feed = page("Home", "Popular", "r/aquariums", "My new tank after three months", "245 upvotes");
        win(REDDIT, "MainActivity", feed);
        eq("reddit still blocked by default", did(), "HOME|Reddit is blocked.");
        Sim.advance(3000); did();
        // the app has been unblocked through its 24 hours; the sites stay blocked
        Context.prefs.get("rules").putStringSet("apps", new HashSet<>(Arrays.asList(CHROME)));
        win(REDDIT, "MainActivity", page("Home", "Popular", "r/aquariums", "My new tank after three months", "245 upvotes"));
        Sim.advance(3000);
        content(REDDIT, page("r/Fallout", "Mod list question", "i.redd.it", "12 comments"));
        Sim.advance(3000);
        eq("reddit app open on ordinary posts", did(), "");
        // an ad's web view next to Reddit's own address must not close the app
        content(REDDIT, view(tv("i.redd.it"), tv("reddit.com"), web("Sponsored", wv("Buy now")), tv("A post title")));
        Sim.advance(3000);
        eq("reddit app with its own address and a web view", did(), "");
        content(REDDIT, page("r/pics", "A normal title", "NSFW", "A marked post"));
        eq("marked post closes reddit", did(), "BACK,HOME|Mature (NSFW) content on Reddit is blocked.");
        Sim.advance(3000); did();
        win(REDDIT, "SettingsActivity", page("Settings", "View options", "Show mature content (I'm over 18)", "Blur mature (18+) images and media"));
        eq("the setting for mature content closes reddit", did(), "BACK,HOME|Mature (NSFW) content on Reddit is blocked.");
        Sim.advance(3000); did();
        win(REDDIT, "MainActivity", page("r/whatever", "This community contains adult content", "Are you over 18?", "Cancel", "Continue"));
        eq("age gate closes reddit", did(), "BACK,HOME|Mature (NSFW) content on Reddit is blocked.");
        Sim.advance(3000); did();
        // marked content that appears a moment after the screen, with no further events
        Node later = page("r/all", "Loading");
        win(REDDIT, "MainActivity", later);
        eq("reddit loading", did(), "");
        later.add(tv("NSFW"));
        Sim.advance(400);
        eq("marked post that appears late", did(), "BACK,HOME|Mature (NSFW) content on Reddit is blocked.");
        Sim.advance(3000); did();
        // other Reddit apps and Reddit on the web are still blocked while the sites are
        win("com.example.snoo", "Main", view());
        eq("other reddit app still blocked", did(), "HOME|Snoo Reader is a Reddit app, and Reddit is blocked.");
        Sim.advance(3000); did();
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        win(CHROME, TAB, chromeTab("reddit.com/r/aquariums", "r/aquariums"));
        starts("reddit on the web still blocked", did(), "HOME|reddit.com is blocked");
        Sim.advance(3000); did();
        // the rule switched off (after its own 24 hours)
        Context.prefs.get("rules").putBoolean("noMature", false);
        win(REDDIT, "MainActivity", page("r/pics", "NSFW", "A marked post"));
        Sim.advance(3000);
        eq("mature rule off", did(), "");
        Context.prefs.get("rules").putBoolean("noMature", true);
        noError("reddit app");

        // ---- Reddit sites unblocked too: the web and other Reddit apps get the same filter ----
        fresh();
        Context.prefs.get("rules").putStringSet("apps", new HashSet<>(Arrays.asList(CHROME)));
        Context.prefs.get("rules").putStringSet("sites", new HashSet<String>());
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        win(CHROME, TAB, view(tv("reddit.com/r/aquariums").id("com.android.chrome:id/url_bar"), web("r/aquariums", wv("My new tank"), wv("245 upvotes"))));
        Sim.advance(3000);
        eq("web reddit, ordinary page", did(), "");
        content(CHROME, view(tv("reddit.com/r/x").id("com.android.chrome:id/url_bar"), web("r/x", wv("Mature Content"), wv("You must be 18+ to view this community"))));
        eq("web reddit, age gate", did(), "BACK,HOME|Mature (NSFW) content on Reddit is blocked.");
        Sim.advance(3000); did();
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        win(CHROME, TAB, view(tv("example.com").id("com.android.chrome:id/url_bar"), web("News", wv("NSFW"), wv("An article that uses the word"))));
        Sim.advance(3000);
        eq("another site that says nsfw", did(), "");
        win("com.example.snoo", "Main", page("r/aquariums", "My new tank"));
        Sim.advance(3000);
        eq("other reddit app allowed once the site is", did(), "");
        content("com.example.snoo", page("r/pics", "NSFW", "A marked post"));
        eq("other reddit app, marked post", did(), "BACK,HOME|Mature (NSFW) content on Reddit is blocked.");
        Sim.advance(3000); did();
        win(MSGR, "BrowserLiteActivity", view(tv("old.reddit.com"), web("pics", wv("NSFW"), wv("a post"))));
        eq("reddit in an in-app viewer, marked post", did(), "BACK,HOME|Mature (NSFW) content on Reddit is blocked.");
        noError("reddit sites off");

        // ---- the mature-content rule follows the same 24-hour wait as every other rule ----
        fresh();
        Rules rr = new Rules(svc);
        rr.requestRemoval(Rules.TYPE_MATURE, "");
        eq("mature rule: waiting", rr.isPending(Rules.TYPE_MATURE, ""), true);
        eq("mature rule: still on during the wait", rr.matureBlocked(), true);
        Sim.advance(23L * 3600 * 1000);
        rr.tick();
        eq("mature rule: still on at 23 hours", rr.matureBlocked(), true);
        Sim.advance(2L * 3600 * 1000);
        rr.tick();
        eq("mature rule: off after 24 hours", rr.matureBlocked(), false);
        rr.blockMature();
        eq("mature rule: back on at once", rr.matureBlocked(), true);
        rr.requestRemoval(Rules.TYPE_APP, REDDIT);
        Sim.advance(25L * 3600 * 1000);
        rr.tick();
        eq("reddit app unblocked after its own wait", rr.getApps().contains(REDDIT), false);
        eq("mature rule untouched by that", rr.matureBlocked(), true);
        win(REDDIT, "MainActivity", page("Home", "r/aquariums", "My new tank"));
        Sim.advance(3000);
        eq("reddit opens after the wait", did(), "");
        content(REDDIT, page("r/pics", "NSFW"));
        eq("and the filter is already there", did(), "BACK,HOME|Mature (NSFW) content on Reddit is blocked.");
        noError("mature rule wait");

        // ---- all of Reddit blocked in one go, and once on the update that added it ----
        fresh();
        Rules w0 = new Rules(svc);
        eq("new install: reddit fully blocked", w0.redditBlocked(), true);
        eq("new install: nothing waiting", w0.redditUnblockWaiting(), false);
        // The phone as it was before the update: the Reddit app and one address opened by their
        // waits, the app uninstalled, and more changes waiting.
        w0.requestRemoval(Rules.TYPE_APP, REDDIT);
        w0.requestRemoval(Rules.TYPE_SITE, "redd.it");
        Sim.advance(25L * 3600 * 1000);
        w0.tick();
        w0.requestRemoval(Rules.TYPE_MATURE, "");
        w0.requestRemoval(Rules.TYPE_SITE, "reddit.com");
        w0.requestRemoval(Rules.TYPE_GUARD, "");
        w0.requestRemoval(Rules.TYPE_APP, CHROME);
        Sim.advance(2L * 3600 * 1000);
        w0.tick();
        svc.pm.apps.remove(REDDIT);
        svc.pm.redditApps.remove(REDDIT);
        eq("before the update: reddit app open", w0.getApps().contains(REDDIT), false);
        eq("before the update: redd.it open", w0.getSites().contains("redd.it"), false);
        eq("before the update: not fully blocked", w0.redditBlocked(), false);
        eq("before the update: more waiting", w0.redditUnblockWaiting(), true);
        Context.prefs.get("rules").m.remove("redditWall33"); // older builds never set this
        Rules w1 = new Rules(svc); // the first time the new build runs
        eq("update: reddit app back on the list though uninstalled", w1.getApps().contains(REDDIT), true);
        eq("update: both addresses blocked", w1.getSites().containsAll(Arrays.asList("reddit.com", "redd.it")), true);
        eq("update: mature rule on", w1.matureBlocked(), true);
        eq("update: fully blocked", w1.redditBlocked(), true);
        eq("update: nothing waiting that opens reddit", w1.redditUnblockWaiting(), false);
        StringBuilder left = new StringBuilder();
        for (Rules.Pending x : w1.getPending()) left.append(x.type).append(":").append(x.value).append(":").append(x.remainingMs / 3600000).append("h ");
        eq("update: other waiting changes left alone, with their time", left.toString(), "guard::22h app:com.android.chrome:22h ");
        eq("update: other rules untouched", w1.isGuardOn() && w1.allBrowsersBlocked() && w1.sideloadBlocked() && w1.getApps().contains(CHROME), true);
        // Reinstalled later: it is blocked from the first screen, and so is everything else Reddit.
        svc.pm.add(REDDIT, "Reddit", false, STORE, CUTOFF + 1000);
        svc.pm.redditApps.add(REDDIT);
        Sim.advance(3000); did();
        win(REDDIT, "MainActivity", page("Home", "r/aquariums", "My new tank"));
        eq("after the update: reddit app blocked again", did(), "HOME|Reddit is blocked.");
        Sim.advance(3000); did();
        win("com.example.snoo", "Main", page("Home", "r/aquariums"));
        eq("after the update: other reddit app", did(), "HOME|Snoo Reader is a Reddit app, and Reddit is blocked.");
        Sim.advance(3000); did();
        win(MSGR, "BrowserLiteActivity", view(tv("old.reddit.com"), web("pics", wv("a post"))));
        starts("after the update: reddit in an in-app viewer", did(), "HOME|reddit.com is blocked");
        Sim.advance(3000); did();
        win(MSGR, "BrowserLiteActivity", view(tv("i.redd.it/abc.jpg"), web("image", wv("a picture"))));
        starts("after the update: redd.it in an in-app viewer", did(), "HOME|redd.it is blocked");
        Sim.advance(3000); did();
        win("com.whatsapp", "Main", view(tv("Chats")));
        Sim.advance(3000);
        eq("after the update: ordinary app untouched", did(), "");
        // The waits that were left alone still run their course.
        Sim.advance(23L * 3600 * 1000);
        w1.tick();
        eq("left-alone wait still applies on time", w1.isGuardOn(), false);
        w1.guardOn();
        // It happens once. A later unblock by the usual wait is respected.
        w1.requestRemoval(Rules.TYPE_APP, REDDIT);
        eq("unblocking again: waiting", w1.redditUnblockWaiting(), true);
        eq("unblocking again: still blocked during the wait", w1.redditBlocked(), true);
        Sim.advance(25L * 3600 * 1000);
        w1.tick();
        eq("unblocking again: open after the wait", w1.getApps().contains(REDDIT), false);
        eq("update does not block it a second time", new Rules(svc).getApps().contains(REDDIT), false);
        eq("shown as not fully blocked", w1.redditBlocked(), false);
        Sim.advance(3000); did();
        win(REDDIT, "MainActivity", page("Home", "r/aquariums", "My new tank"));
        Sim.advance(3000);
        eq("unblocked app opens", did(), "");
        // One tap puts everything back.
        w1.requestRemoval(Rules.TYPE_MATURE, "");
        w1.requestRemoval(Rules.TYPE_SITE, "reddit.com");
        w1.requestRemoval(Rules.TYPE_SIDELOAD, "");
        w1.blockAllReddit();
        eq("one tap: fully blocked", w1.redditBlocked(), true);
        eq("one tap: reddit waits dropped", w1.redditUnblockWaiting(), false);
        eq("one tap: mature rule on", w1.matureBlocked(), true);
        eq("one tap: unrelated wait kept", w1.isPending(Rules.TYPE_SIDELOAD, ""), true);
        Sim.advance(3000); did();
        win(REDDIT, "MainActivity", page("Home", "r/aquariums", "My new tank"));
        eq("one tap: app blocked at once", did(), "HOME|Reddit is blocked.");
        Sim.advance(25L * 3600 * 1000);
        w1.tick();
        eq("one tap: dropped waits never apply", w1.redditBlocked() && w1.matureBlocked(), true);
        noError("reddit wall");

        // ---- manga: reading apps ----
        fresh();
        final String MPLUS = "jp.co.shueisha.mangaplus", PANEL = "com.example.panel";
        svc.pm.add(MPLUS, "MANGA Plus", false, STORE, CUTOFF + 1000);
        svc.pm.add("com.tapastic", "Tapas", false, STORE, CUTOFF + 1000);
        svc.pm.add(PANEL, "Panel", false, STORE, CUTOFF + 1000);              // only its links give it away
        svc.pm.add("com.fun.hangmangame", "Hangman Game", false, STORE, CUTOFF + 1000);
        svc.pm.add("com.samsung.comics", "Comics", true, null, 0);            // came with the phone
        // Fast Net is a browser: it answers for every address, so it is not a manga app for that.
        svc.pm.linkHandlers.put("webtoons.com", new HashSet<>(Arrays.asList(PANEL, "com.example.fastnet")));
        for (android.content.BroadcastReceiver r : new ArrayList<>(svc.receivers)) r.onReceive(svc, new Intent());
        Sim.advance(4000); did();
        win(MPLUS, "Main", view(tv("Updates")));
        eq("manga app by name", did(), "HOME|MANGA Plus is a manga app, and manga is blocked.");
        Sim.advance(1000);
        win("com.tapastic", "Main", view(tv("Home")));
        eq("manga app from the known list", did(), "HOME|Tapas is a manga app, and manga is blocked.");
        Sim.advance(1000);
        win(PANEL, "Main", view(tv("Home")));
        eq("manga app by what it does", did(), "HOME|Panel is a manga app, and manga is blocked.");
        Sim.advance(1000);
        win("com.example.fastnet", "Main", view());
        eq("browser keeps its own reason", did(), "HOME|Fast Net is blocked (all browsers are).");
        Sim.advance(1000);
        win("com.fun.hangmangame", "Main", view(tv("Play")));
        Sim.advance(3000);
        eq("hangman game is not manga", did(), "");
        win("com.samsung.comics", "Main", view(tv("Library")));
        Sim.advance(3000);
        eq("app that came with the phone is left alone", did(), "");
        win("com.whatsapp", "Main", view(tv("Chats")));
        Sim.advance(3000);
        eq("ordinary app untouched by the manga rule", did(), "");
        win("com.google.android.apps.bard", "Main", view(tv("Gemini")));
        Sim.advance(3000);
        eq("gemini untouched by the manga rule", did(), "");
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        Sim.advance(3000);
        eq("claude untouched by the manga rule", did(), "");
        // Link lookups happen between events, never inside them.
        int lookups = svc.pm.linkLookups;
        for (int i = 0; i < 20; i++) {
            win(i % 2 == 0 ? "com.whatsapp" : MSGR, "Main", view(tv("Chats")));
            content(MPLUS, view(tv("Updates")));
        }
        eq("no link lookups while handling events", svc.pm.linkLookups, lookups);
        Sim.advance(3000); did();
        eq("the app's own list of what the rule closes",
                Manga.installedApps(svc.pm, "com.aaron.holdfast").toString(), "[MANGA Plus, Panel, Tapas]");
        noError("manga apps");

        // A reading app known only by its links, opened in the first moment after the service starts.
        fresh();
        svc.pm.add(PANEL, "Panel", false, STORE, CUTOFF + 1000);
        svc.pm.linkHandlers.put("tapas.io", new HashSet<>(Arrays.asList(PANEL)));
        win(PANEL, "Main", view(tv("Home")));
        eq("link app in the first moment: not known yet", did(), "");
        Sim.advance(400);
        content(PANEL, view(tv("Home")));
        eq("link app: closed as soon as it is known", did(), "HOME|Panel is a manga app, and manga is blocked.");
        // Installed later: known a few seconds after the install.
        Sim.advance(3000); did();
        svc.pm.add("com.example.inkwell", "Inkwell", false, STORE, CUTOFF + 2000);
        svc.pm.linkHandlers.put("mangadex.org", new HashSet<>(Arrays.asList("com.example.inkwell")));
        for (android.content.BroadcastReceiver r : new ArrayList<>(svc.receivers)) r.onReceive(svc, new Intent());
        Sim.advance(3500);
        win("com.example.inkwell", "Main", view(tv("Library")));
        eq("link app installed later", did(), "HOME|Inkwell is a manga app, and manga is blocked.");
        Sim.advance(1000);
        svc.pm.add("com.example.newmanga", "Manga Shelf", false, STORE, CUTOFF + 3000);
        for (android.content.BroadcastReceiver r : new ArrayList<>(svc.receivers)) r.onReceive(svc, new Intent());
        win("com.example.newmanga", "Main", view(tv("Library")));
        eq("named manga app installed later: closed at once", did(), "HOME|Manga Shelf is a manga app, and manga is blocked.");
        noError("manga link apps");

        // ---- manga: reader sites inside other apps ----
        fresh();
        win(MSGR, "BrowserLiteActivity", view(tv("mangadex.org"), web("One Piece", wv("Chapter 1"))));
        eq("manga site in an in-app viewer", did(), "HOME|mangadex.org is a manga site, and manga is blocked in every app.");
        Sim.advance(3000); did();
        win(MSGR, "BrowserLiteActivity", view(tv("https://www.webtoons.com/en/fantasy/x/list?title_no=1"), web("x", wv("Episode 1"))));
        eq("webtoon site, full address", did(), "HOME|webtoons.com is a manga site, and manga is blocked in every app.");
        Sim.advance(3000); did();
        win(MSGR, "BrowserLiteActivity", view(tv("tapas.io"), web("Tapas", wv("Series"))));
        eq("reader site from the list", did(), "HOME|tapas.io is a manga site, and manga is blocked in every app.");
        Sim.advance(3000); did();
        win(MSGR, "BrowserLiteActivity", view(tv("mangadex-org.translate.goog"), web("x", wv("Chapter 1"))));
        eq("manga site through a translating address", did(), "HOME|mangadex-org.translate.goog is a manga site, and manga is blocked in every app.");
        Sim.advance(3000); did();
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        win(CHROME, TAB, chromeTab("mangadex.org/title/abc", "One Piece"));
        eq("manga site in a Claude link tab", did(), "HOME|mangadex.org is a manga site, and manga is blocked in every app.");
        Sim.advance(3000); did();
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        win(CHROME, TAB, chromeTab("en.wikipedia.org/wiki/Manga", "Manga - Wikipedia"));
        Sim.advance(3000);
        eq("a page about manga, in a Claude link tab", did(), "");
        win(MSGR, "BrowserLiteActivity", view(tv("en.wikipedia.org"), web("Manga - Wikipedia", wv("mangadex.org"), wv("Manga are comics from Japan"))));
        Sim.advance(3000);
        eq("a page that lists manga sites", did(), "");
        win(MSGR, "BrowserLiteActivity", view(tv("google.com/search?q=manga"), web("manga - Search", wv("mangadex.org"))));
        Sim.advance(3000);
        eq("a search for the word", did(), "");
        win(MSGR, "BrowserLiteActivity", view(tv("amazon.com/One-Piece-Manga-Vol-1/dp/1"), web("One Piece", wv("Buy now"))));
        Sim.advance(3000);
        eq("a shop page for a manga book", did(), "");
        win(MSGR, "BrowserLiteActivity", view(tv("mangaloretoday.com"), web("News", wv("Headlines"))));
        Sim.advance(3000);
        eq("a news site with the letters in its name", did(), "");
        win(CLAUDE, "MainActivity", page("You could look on mangadex.org", "mangadex.org"));
        Sim.advance(3000);
        eq("a chat that names a manga site, no web page", did(), "");
        win(GOOGLE, ROBIN, view(edit(null).hint("Ask Gemini")));
        Sim.advance(3000);
        content(GOOGLE, view(edit("x").hint("Ask Gemini"), tv("mangadex.org"), web("Answer", wv("text"))));
        Sim.advance(3000);
        eq("a Gemini answer that names a manga site", did(), "");
        win(MSGR, "BrowserLiteActivity", view(tv("old.reddit.com"), web("pics", wv("a post"))));
        starts("reddit keeps its own reason", did(), "HOME|reddit.com is blocked");
        noError("manga sites");

        // ---- the manga rule follows the same 24-hour wait as every other rule ----
        fresh();
        svc.pm.add(MPLUS, "MANGA Plus", false, STORE, CUTOFF + 1000);
        Rules mr = new Rules(svc);
        eq("manga rule: on from the start", mr.mangaBlocked(), true);
        Context.prefs.get("rules").m.remove("noManga"); // a phone updating from an older build
        eq("manga rule: on after the update that adds it", new Rules(svc).mangaBlocked(), true);
        eq("manga rule: saved as on", Context.prefs.get("rules").m.get("noManga"), true);
        mr.requestRemoval(Rules.TYPE_MANGA, "");
        eq("manga rule: waiting", mr.isPending(Rules.TYPE_MANGA, ""), true);
        Sim.advance(23L * 3600 * 1000);
        mr.tick();
        eq("manga rule: still on at 23 hours", mr.mangaBlocked(), true);
        win(MPLUS, "Main", view(tv("Updates")));
        eq("manga app still closed during the wait", did(), "HOME|MANGA Plus is a manga app, and manga is blocked.");
        Sim.advance(2L * 3600 * 1000);
        mr.tick();
        eq("manga rule: off after 24 hours", mr.mangaBlocked(), false);
        Sim.advance(3000); did();
        win(MPLUS, "Main", view(tv("Updates")));
        Sim.advance(3000);
        eq("manga app opens once the rule is off", did(), "");
        win(MSGR, "BrowserLiteActivity", view(tv("mangadex.org"), web("One Piece", wv("Chapter 1"))));
        Sim.advance(3000);
        eq("manga site opens once the rule is off", did(), "");
        eq("other rules untouched by that", mr.redditBlocked() && mr.allBrowsersBlocked() && mr.isGuardOn() && mr.matureBlocked(), true);
        mr.blockManga();
        eq("manga rule: back on at once", mr.mangaBlocked(), true);
        win(MPLUS, "Main", view(tv("Updates")));
        eq("manga app closed again at once", did(), "HOME|MANGA Plus is a manga app, and manga is blocked.");
        Sim.advance(3000); did();
        win(MSGR, "BrowserLiteActivity", view(tv("mangadex.org"), web("One Piece", wv("Chapter 1"))));
        eq("manga site closed again at once", did(), "HOME|mangadex.org is a manga site, and manga is blocked in every app.");
        mr.requestRemoval(Rules.TYPE_MANGA, "");
        mr.blockManga();
        eq("blocking again drops the wait", mr.isPending(Rules.TYPE_MANGA, ""), false);
        mr.requestRemoval(Rules.TYPE_MANGA, "");
        mr.blockAllReddit();
        eq("blocking Reddit leaves a manga wait alone", mr.isPending(Rules.TYPE_MANGA, ""), true);
        eq("a manga wait is not a Reddit change", Rules.opensReddit(Rules.TYPE_MANGA, ""), false);
        // A site he put on the list himself keeps the plain reason.
        mr.blockManga();
        mr.blockSite("mangadex.org");
        Sim.advance(3000); did();
        win(MSGR, "BrowserLiteActivity", view(tv("mangadex.org"), web("One Piece", wv("Chapter 1"))));
        eq("site on his own list: plain reason", did(), "HOME|mangadex.org is blocked, in every app.");
        noError("manga rule wait");

        // ---- windows that going home does not close (a pop-up window) ----
        fresh();
        svc.homeWorks = false; // the window stays where it is when Home is pressed
        Node pop = view(tv("Home feed")).box(90, 300, 990, 1700);
        win(REDDIT, "Main", pop);
        eq("blocked app in a small window: stepped back out of", did(), "BACK,HOME|Reddit is blocked.");
        for (int i = 2; i <= 3; i++) {
            Sim.advance(900);
            content(REDDIT, pop);
            eq("small window, touch " + i + ": stepped back again", did(), "BACK,HOME|Reddit is blocked.");
        }
        eq("small window: no lock yet", Toast.shown.size(), 0);
        Sim.advance(900);
        content(REDDIT, pop);
        eq("small window that keeps coming back: the screen locks", did(), "BACK,LOCK");
        eq("small window: says why", Toast.shown.size() == 1 && Toast.shown.get(0).contains("small window"), true);
        Sim.advance(5000);
        content(REDDIT, pop);
        eq("small window: locked again for as long as it stays", did(), "BACK,LOCK");
        Sim.advance(31L * 60 * 1000);
        content(REDDIT, pop);
        eq("small window: much later it starts over", did(), "BACK,HOME|Reddit is blocked.");
        noError("small window");
        // Back closes most windows: then that is the end of it.
        fresh();
        svc.homeWorks = false;
        svc.backLeaves = svc.homeRoot;
        win(REDDIT, "Main", view(tv("Home feed")).box(90, 300, 990, 1700));
        eq("small window that Back closes", did(), "BACK,HOME|Reddit is blocked.");
        Sim.advance(3000);
        eq("small window that Back closes: nothing more", did(), "");
        svc.backLeaves = null;
        // The same for every kind of block: a browser, a manga app, a blocked site in a viewer.
        fresh();
        svc.homeWorks = false;
        svc.pm.add("jp.co.shueisha.mangaplus", "MANGA Plus", false, STORE, CUTOFF + 1000);
        win("com.example.fastnet", "Main", view(tv("Start page")).box(0, 1170, 1080, 2340));
        eq("browser in split screen", did(), "BACK,HOME|Fast Net is blocked (all browsers are).");
        Sim.advance(3000); did();
        win("jp.co.shueisha.mangaplus", "Main", view(tv("Updates")).box(90, 300, 990, 1700));
        eq("manga app in a small window", did(), "BACK,HOME|MANGA Plus is a manga app, and manga is blocked.");
        Sim.advance(3000); did();
        win(MSGR, "BrowserLiteActivity", view(tv("old.reddit.com"), web("pics", wv("a post"))).box(0, 700, 1080, 2340));
        eq("blocked site in a small viewer window", did(), "BACK,HOME|reddit.com is blocked, in every app.");
        Sim.advance(3000); did();
        win("com.whatsapp", "Main", view(tv("Chats")).box(90, 300, 990, 1700));
        Sim.advance(3000);
        eq("ordinary app in a small window is left alone", did(), "");
        // A window just short of the whole screen is not small.
        win(REDDIT, "Main", view(tv("Home feed")).box(0, 80, 1080, 2340));
        eq("nearly full screen counts as full screen", did(), "HOME|Reddit is blocked.");
        noError("small window kinds");

        // An ordinary full-screen app is closed the old way, however often, and never locks.
        fresh();
        svc.homeWorks = false; // even when going home is slow
        for (int i = 0; i < 8; i++) {
            content(REDDIT, view(tv("Home feed")));
            eq("full screen, time " + (i + 1), did(), "HOME|Reddit is blocked.");
            Sim.advance(900);
        }
        eq("full screen: no lock", Toast.shown.size(), 0);
        // Protected Settings dialogs are small windows too: they keep their own handling.
        fresh();
        svc.homeWorks = false;
        for (int i = 0; i < 6; i++) {
            win(SETTINGS, "SubSettings", page("Turn off Holdfast?", "Cancel", "Turn off").box(100, 900, 980, 1500));
            starts("protected dialog, time " + (i + 1), did(), "BACK,HOME|Holdfast's settings are protected");
            Sim.advance(3000); did();
        }
        eq("protected dialog: no lock", Toast.shown.size(), 0);
        noError("full screen and dialogs");

        // ---- a blocked app that keeps showing while another app is in front ----
        fresh();
        svc.homeWorks = false;
        Node chats = view(tv("Chats"));
        for (int i = 1; i <= 4; i++) {
            fire(REDDIT, 2048, null, chats, "com.whatsapp");
            eq("showing behind another app, time " + i, did(), "HOME|Reddit is blocked.");
            Sim.advance(900);
        }
        fire(REDDIT, 2048, null, chats, "com.whatsapp");
        eq("still showing after that: the screen locks", did(), "LOCK");
        eq("showing behind: says why", Toast.shown.size(), 1);
        Sim.advance(900);
        fire(REDDIT, 2048, null, chats, "com.whatsapp");
        eq("showing behind: counting starts again", did(), "HOME|Reddit is blocked.");
        // Now and then is not "keeps showing".
        fresh();
        svc.homeWorks = false;
        for (int i = 1; i <= 10; i++) {
            fire(REDDIT, 2048, null, chats, "com.whatsapp");
            eq("now and then, time " + i, did(), "HOME|Reddit is blocked.");
            Sim.advance(3000);
        }
        eq("now and then: no lock", Toast.shown.size(), 0);
        // Opening a blocked app the ordinary way: its first event can arrive while the home
        // screen is still the window in front.
        fresh();
        for (int i = 1; i <= 10; i++) {
            Node h = view(tv("Home screen"));
            fire(REDDIT, 32, "Main", h, LAUNCHER);
            eq("ordinary open, time " + i, did(), "HOME|Reddit is blocked.");
            Sim.advance(2500);
        }
        eq("ordinary opens: no lock", Toast.shown.size(), 0);
        noError("showing behind");

        // ---- manga reader pages, whatever address they are served from ----
        fresh();
        win(MSGR, "BrowserLiteActivity", view(tv("One Piece - MangaDex").id("com.facebook.orca:id/title"), web(null, wv("page 1"))));
        eq("reader site named in the viewer's title strip", did(), "HOME|This page is a manga reader, and manga is blocked in every app.");
        Sim.advance(3000); did();
        win(MSGR, "BrowserLiteActivity", view(tv("webproxy.example"), web("Read Solo Leveling Manga Online Free - Manganato", wv("page 1"))));
        eq("reader site named in the page's own title", did(), "HOME|This page is a manga reader, and manga is blocked in every app.");
        Sim.advance(3000); did();
        win(MSGR, "BrowserLiteActivity", view(tv("some-name.example"), web("Solo Leveling",
                wv("Home"), wv("Manga List"), wv("\u2190 Prev Chapter"), wv("Next Chapter \u2192"), wv("page 1"))));
        eq("reader page by what is on it", did(), "HOME|This page is a manga reader, and manga is blocked in every app.");
        Sim.advance(3000); did();
        Node series = web("Solo Leveling", wv("Home"), wv("Read Manhwa Online"));
        for (int i = 1; i <= 9; i++) series.add(wv("Chapter " + i));
        win(MSGR, "BrowserLiteActivity", view(tv("some-name.example"), series));
        eq("series page by its run of chapters", did(), "HOME|This page is a manga reader, and manga is blocked in every app.");
        Sim.advance(3000); did();
        win(CLAUDE, "MainActivity", view(tv("Chat")));
        win(CHROME, TAB, view(tv("some-name.example").id("com.android.chrome:id/url_bar"),
                web("Chapter 7", wv("Manga"), wv("Previous Chapter"), wv("Next Chapter"))));
        eq("reader page in a Claude link tab", did(), "HOME|This page is a manga reader, and manga is blocked in every app.");
        Sim.advance(3000); did();
        // Pages that are not readers stay open.
        win(MSGR, "BrowserLiteActivity", view(tv("doc.rust-lang.org"), web("The Rust Programming Language",
                wv("Previous chapter"), wv("Next chapter"), wv("Chapter 1"), wv("Chapter 2"))));
        Sim.advance(3000);
        eq("online book with chapters", did(), "");
        win(MSGR, "BrowserLiteActivity", view(tv("en.wikipedia.org"), web("Manga - Wikipedia",
                wv("Manga"), wv("Manga are comics or graphic novels originating from Japan."), wv("Chapter 11"))));
        Sim.advance(3000);
        eq("article about manga", did(), "");
        win(MSGR, "BrowserLiteActivity", view(tv("google.com/search?q=mangadex"), web("mangadex - Google Search",
                wv("MangaDex"), wv("mangadex.org"), wv("Read manga online for free"))));
        Sim.advance(3000);
        eq("search for a reader site", did(), "");
        win(MSGR, "BrowserLiteActivity", view(tv("shop.example"), web("One Piece Box Set | Shop",
                wv("One Piece manga box set 1"), wv("Add to cart"), wv("Next"), wv("Previous"))));
        Sim.advance(3000);
        eq("shop page for a manga book", did(), "");
        win(CLAUDE, "MainActivity", page("Manga List", "Prev Chapter", "Next Chapter", "Chapter 1", "Chapter 2"));
        Sim.advance(3000);
        eq("the same words in an app's own screen, not a web page", did(), "");
        win(GOOGLE, ROBIN, view(edit(null).hint("Ask Gemini")));
        Sim.advance(3000);
        content(GOOGLE, view(edit("x").hint("Ask Gemini"), web("Answer", wv("Manga"), wv("Prev Chapter"), wv("Next Chapter"))));
        Sim.advance(3000);
        eq("gemini is judged by its own rules", did(), "");
        // With the manga rule off, none of this applies.
        Rules off = new Rules(svc);
        off.requestRemoval(Rules.TYPE_MANGA, "");
        Sim.advance(25L * 3600 * 1000);
        off.tick();
        did();
        win(MSGR, "BrowserLiteActivity", view(tv("some-name.example"), web("One Piece - MangaDex",
                wv("Manga List"), wv("Prev Chapter"), wv("Next Chapter"))));
        Sim.advance(3000);
        eq("reader page with the manga rule off", did(), "");
        noError("manga pages");

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
