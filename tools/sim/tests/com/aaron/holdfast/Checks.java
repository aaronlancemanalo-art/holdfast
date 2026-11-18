package com.aaron.holdfast;

import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Method;
import java.util.*;

public class Checks {
    static int fails = 0, passes = 0;

    // ---- fake screen nodes ----
    public static class Node extends AccessibilityNodeInfo {
        String cls, text, desc, id, hint, pkg;
        List<Node> kids;
        Node init(String cls, String text) { this.cls = cls; this.text = text; this.kids = new ArrayList<>(); return this; }
        Node id(String v) { id = v; return this; }
        Node desc(String v) { desc = v; return this; }
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
    }
    static Node n(String cls, String text) throws Exception {
        return new Node().init(cls, text);
    }
    static Node view(Node... kids) throws Exception { return n("android.widget.FrameLayout", null).add(kids); }
    static Node tv(String t) throws Exception { return n("android.widget.TextView", t); }
    static Node web(String title, Node... kids) throws Exception { return n("android.webkit.WebView", title).add(kids); }
    static Node wv(String t) throws Exception { return n("android.view.View", t); }

    static Method m(String name, Class<?>... types) throws Exception {
        Method x = BlockerService.class.getDeclaredMethod(name, types); x.setAccessible(true); return x;
    }
    static void eq(String what, Object got, Object want) {
        if (Objects.equals(got, want)) passes++;
        else { fails++; System.out.println("FAIL " + what + "\n   got : " + got + "\n   want: " + want); }
    }
    static BlockerService.Screen screen(String... texts) {
        BlockerService.Screen s = new BlockerService.Screen();
        for (String t : texts) s.add(t);
        return s;
    }
    static String rule(boolean window, boolean near, String... texts) {
        return BlockerService.settingsPageRule(screen(texts), true, window, near);
    }
    static String kind(String reason) {
        if (reason == null) return null;
        String r = reason.toLowerCase();
        if (r.startsWith("developer")) return "dev";
        if (r.startsWith("resetting")) return "reset";
        if (r.startsWith("app pinning")) return "pin";
        if (r.startsWith("power saving")) return "power";
        if (r.startsWith("changing the phone")) return "lang";
        if (r.startsWith("maintenance")) return "maint";
        if (r.startsWith("sleeping")) return "sleep";
        if (r.startsWith("holdfast's settings")) return "holdfast";
        return "??" + reason;
    }

    public static void main(String[] a) throws Exception {
        // ---------- Settings pages that must stay open ----------
        eq("settings main", kind(rule(false, false, "Settings", "Connections", "Battery", "Security and privacy", "Device care",
                "Storage • Memory • App protection", "Apps", "General management", "Language and keyboard • Date and time",
                "Accessibility", "Software update", "About phone", "Developer options", "Developer options")), null);
        eq("apps list", kind(rule(false, false, "Apps", "Choose default apps", "Samsung app settings", "Your apps", "Holdfast", "12.3 MB",
                "Riyal Ledger", "8 MB", "WhatsApp")), null);
        eq("other app info", kind(rule(false, false, "App info", "WhatsApp", "Installed", "Notifications", "Allowed", "Permissions",
                "Battery", "Storage", "Install unknown apps", "Not allowed", "Open", "Uninstall", "Force stop",
                "App downloaded from Google Play Store")), null);
        eq("ledger app info", kind(rule(false, false, "App info", "Riyal Ledger", "Battery", "Storage", "Open", "Uninstall", "Force stop",
                "App installed from Holdfast")), null);
        eq("general management", kind(rule(false, false, "General management", "Language", "English (Philippines)", "App languages",
                "Date and time", "Samsung Keyboard settings", "Keyboard list and default", "Reset", "Contact us")), null);
        eq("reset menu", kind(rule(false, false, "Reset", "Reset all settings", "Reset mobile network settings",
                "Reset Wi-Fi and Bluetooth settings", "Reset accessibility settings", "Factory data reset")), null);
        eq("reset network page", kind(rule(false, false, "Reset mobile network settings",
                "This will reset all network settings, including those for Wi-Fi, mobile data, and Bluetooth.", "Reset settings")), null);
        eq("battery page", kind(rule(false, false, "Battery", "Power saving", "Background usage limits",
                "Limit battery usage for apps that you don't use often", "Battery protection", "Battery information")), null);
        eq("battery page that lists the options", kind(rule(false, false, "Battery", "Power saving", "Limit apps and Home screen",
                "Background usage limits", "Battery protection")), null);
        eq("usage limits menu", kind(rule(false, false, "Background usage limits", "Put unused apps to sleep", "Sleeping apps", "4",
                "Deep sleeping apps", "0", "Never auto sleeping apps", "2")), null);
        eq("device care", kind(rule(false, false, "Device care", "Battery", "Storage", "Memory", "App protection", "Turn on",
                "Performance profile", "Auto optimization", "Software update", "Diagnostics", "Maintenance mode")), null);
        eq("accessibility main", kind(rule(false, false, "Accessibility", "Recommended for you", "Vision enhancements", "TalkBack",
                "Interaction and dexterity", "Advanced settings", "Installed apps", "1 app", "About Accessibility")), null);
        eq("a11y installed list", kind(rule(false, false, "Installed apps", "Holdfast", "On", "Voice Access", "Off")), null);
        eq("security hub", kind(rule(false, false, "Security and privacy", "Lock screen", "Accounts", "Lost device protection",
                "App security", "Updates", "Privacy", "Biometrics", "Auto Blocker", "More security settings")), null);
        eq("more security list", kind(rule(false, false, "More security settings", "SIM card security", "Make passwords visible",
                "Device admin apps", "1 active app", "Credential storage", "Pin app", "Off")), null);
        eq("holdfast install page in window", kind(rule(true, false, "Install unknown apps", "Holdfast", "1.0.31", "Allow from this source",
                "Your phone and personal data are more vulnerable to attack by unknown apps. By installing apps from this source, you agree that you are responsible.")), null);
        eq("picker far from sleep lists", kind(rule(false, false, "Select apps", "All", "Holdfast", "WhatsApp")), null);
        eq("notifications list", kind(rule(false, false, "App notifications", "All", "Holdfast", "WhatsApp")), null);

        // ---------- Settings pages that must close ----------
        eq("holdfast app info", kind(rule(false, false, "App info", "Holdfast", "Battery", "Storage", "Open", "Uninstall", "Force stop")), "holdfast");
        eq("holdfast a11y page", kind(rule(false, false, "Holdfast", "On", "Holdfast shortcut", "Off")), "holdfast");
        eq("turn off dialog", kind(rule(false, false, "Turn off Holdfast?", "Cancel", "Turn off")), "holdfast");
        eq("stop dialog", kind(rule(false, false, "Stop Holdfast?", "Tapping Stop will stop Holdfast.", "Cancel", "Stop")), "holdfast");
        eq("turn off dialog, other wording", kind(rule(false, false, "Turn off?", "Holdfast will stop working.", "Cancel", "Turn off")), "holdfast");
        eq("deactivate confirm", kind(rule(false, false, "This removes Holdfast's uninstall protection.", "Cancel", "OK")), "holdfast");
        eq("holdfast storage page", kind(rule(false, false, "Storage", "Holdfast", "12 MB", "Clear data", "Clear cache")), "holdfast");
        eq("battery prompt allowed", kind(rule(false, false, "Let app always run in background?",
                "Allowing Holdfast to always run in the background may reduce battery life.", "Deny", "Allow")), null);
        eq("enable prompt allowed", kind(rule(false, false, "Allow Holdfast to have full control of your device?", "Allow", "Deny")), null);
        eq("other app dialog", kind(rule(false, false, "Force stop?", "If you force stop an app, it may cause errors.", "Cancel", "OK")), null);
        eq("device admin list", kind(rule(false, false, "Device admin apps", "Find My Mobile", "Holdfast")), "holdfast");
        eq("battery optimisation list", kind(rule(false, false, "Optimise battery usage", "All", "Holdfast", "WhatsApp")), "holdfast");
        eq("volume key page", kind(rule(false, false, "Volume up and down buttons", "Holdfast", "TalkBack")), "holdfast");
        eq("language page", kind(rule(false, false, "Language", "English (Philippines)", "Add language")), "lang");
        eq("reset all page", kind(rule(false, false, "Reset all settings", "This will reset all settings except:", "Security settings",
                "Language settings", "Accounts", "Personal data", "Reset settings")), "reset");
        eq("reset a11y page", kind(rule(false, false, "Reset accessibility settings",
                "Your accessibility settings will be reset to defaults.", "Reset settings")), "reset");
        eq("power saving page", kind(rule(false, false, "Power saving", "Turn off Always On Display", "Limit CPU speed to 70%",
                "Decrease brightness by 10%", "Limit apps and Home screen")), "power");
        eq("deep sleeping list", kind(rule(false, false, "Deep sleeping apps", "Deep sleeping apps will never run in the background.")), "sleep");
        eq("sleeping list", kind(rule(false, false, "Sleeping apps", "WhatsApp")), "sleep");
        eq("picker near sleep lists", kind(rule(false, true, "Select apps", "All", "WhatsApp")), "sleep");
        eq("picker near, counted", kind(rule(false, true, "3 selected", "WhatsApp")), "sleep");
        eq("maintenance page", kind(rule(false, false, "Maintenance mode",
                "Keep your personal information private while someone else is using your phone, such as when it is being repaired.", "Turn on")), "maint");
        eq("maintenance dialog", kind(rule(false, false, "Restart your phone to turn on Maintenance mode?", "Restart without creating log", "Cancel", "Restart")), "maint");
        eq("pin page", kind(rule(false, false, "Pin app", "Off", "To unpin an app, touch and hold the Back and Recents buttons.")), "pin");
        eq("pin page on", kind(rule(false, false, "Pin app", "On", "Ask for PIN before unpinning")), "pin");
        eq("dev options top", kind(rule(false, false, "Developer options", "On", "Memory", "Bug report", "Stay awake", "OEM unlocking", "Running services")), "dev");
        eq("dev options debugging", kind(rule(false, false, "USB debugging", "Debug mode when USB is connected", "Wireless debugging")), "dev");
        eq("holdfast install page, no window", kind(rule(false, false, "Install unknown apps", "Holdfast", "Allow from this source")), null);

        // other phone language: only pages that mention Holdfast
        eq("non-english holdfast", BlockerService.settingsPageRule(screen("Holdfast", "Naka-on"), false, false, false) != null, true);
        eq("non-english other", BlockerService.settingsPageRule(screen("Mga setting", "Baterya"), false, false, false), null);

        // ---------- addresses ----------
        Method siteIn = m("siteIn", CharSequence.class, Set.class);
        Set<String> sites = new HashSet<>(Arrays.asList("reddit.com", "redd.it", "instagram.com"));
        Set<String> noReddit = new HashSet<>(Arrays.asList("instagram.com"));
        String[][] urls = {
                {"reddit.com/r/all", "reddit.com"}, {"https://www.reddit.com", "reddit.com"}, {"old.reddit.com", "reddit.com"},
                {"i.redd.it/abc.jpg", "redd.it"}, {"www-reddit-com.translate.goog/r/all", "reddit.com"},
                {"www-instagram-com.translate.goog", "instagram.com"}, {"web.archive.org/web/2026/https://www.reddit.com/r/x", "reddit.com"},
                {"redlib.example.org/r/all", "reddit.com"}, {"libreddit.example.net", "reddit.com"}, {"teddit.net", "reddit.com"},
                {"safereddit.com", "reddit.com"}, {"example.com", null}, {"duckduckgo.com/?q=reddit", null},
                {"google.com/search?q=best+reddit+threads", null}, {"reddit alternatives", null}, {"reddit", null},
                {"credit.com", null}, {"edit.org", null}, {"accredited.org", null}, {"wikipedia.org/wiki/Reddit", null},
                {"instagram.com/p/1", "instagram.com"}, {"", null}};
        for (String[] u : urls) eq("siteIn " + u[0], siteIn.invoke(null, u[0], sites), u[1]);
        eq("siteIn mirror when reddit not blocked", siteIn.invoke(null, "teddit.net", noReddit), null);

        // ---------- page titles ----------
        Method title = m("isRedditTitle", String.class);
        String[] yes = {"reddit - the heart of the internet", "what is the best phone? : r/android", "r/askreddit", "r/all - reddit",
                "someuser (u/someuser) - reddit", "cats - reddit search!", "a post title - r/pics", "redlib", "r/popular", "reddit"};
        String[] no = {"reddit - wikipedia", "why reddit is down today", "r/k selection theory - wikipedia", "google", "",
                "how to read/write files", "duckduckgo", "best reddit alternatives in 2026", "claude", "r/"};
        for (String t : yes) eq("title yes: " + t, title.invoke(null, t), true);
        for (String t : no) eq("title no: " + t, title.invoke(null, t), false);

        // ---------- web pages inside apps ----------
        Method inViewer = m("blockedSiteInWebViewer", AccessibilityNodeInfo.class, Set.class, boolean.class);
        // in-app browser showing the address
        eq("viewer with reddit address", inViewer.invoke(null, view(tv("m.reddit.com"), web("anything", wv("hello"))), sites, true), "reddit.com");
        eq("viewer address after page", inViewer.invoke(null, view(web("anything", wv("hello")), tv("reddit.com/r/x")), sites, true), "reddit.com");
        eq("viewer with other address", inViewer.invoke(null, view(tv("example.com"), web("Example Domain", wv("hello"))), sites, true), null);
        // no address shown at all, the page is Reddit by title
        eq("viewer hidden address, post title", inViewer.invoke(null, view(tv("Done"), web("Best budget phone? : r/Android", wv("x"))), sites, true), "reddit.com");
        eq("viewer hidden address, home title", inViewer.invoke(null, view(web("Reddit - The heart of the internet")), sites, true), "reddit.com");
        // nested: host view then the page node
        eq("viewer nested page node", inViewer.invoke(null, view(n("android.webkit.WebView", null).add(web("r/pics", wv("x")))), sites, true), "reddit.com");
        // copy of Reddit under another name: links
        Node feed = web("Front page", view(wv("r/pics"), wv("u/alice")), view(wv("r/aww"), wv("u/bob")), view(wv("r/news")));
        eq("viewer mirror by links", inViewer.invoke(null, view(tv("somewhere.example"), feed), sites, true), "reddit.com");
        eq("viewer mirror by links, links off", inViewer.invoke(null, view(tv("somewhere.example"), feed), sites, false), null);
        // a page that merely lists a reddit address or a few subreddits stays open
        eq("search results listing reddit", inViewer.invoke(null, view(tv("duckduckgo.com"), web("phones at DuckDuckGo",
                wv("https://www.reddit.com/r/Android"), wv("reddit.com"), wv("Best phone? : r/Android - Reddit"), wv("r/Android"))), sites, true), null);
        eq("article with three subs", inViewer.invoke(null, view(web("Ten communities", wv("r/a1"), wv("r/b2"), wv("r/c3"))), sites, true), null);
        eq("repeated same sub", inViewer.invoke(null, view(web("x", wv("r/pics"), wv("r/pics"), wv("r/pics"), wv("r/pics"), wv("r/pics"))), sites, true), null);
        // no web page: chat mentioning the address is not a web viewer
        eq("chat with address, no page", inViewer.invoke(null, view(tv("reddit.com"), tv("check reddit.com/r/x")), sites, true), null);
        // title strip above the page
        eq("title strip", inViewer.invoke(null, view(tv("What phone : r/Android").id("com.x:id/title_bar"), web(null, wv("y"))), sites, true), "reddit.com");
        eq("title strip no reddit rule", inViewer.invoke(null, view(tv("What phone : r/Android").id("com.x:id/title_bar"), web(null, wv("y"))), noReddit, true), null);
        // reddit not on the list: nothing reddit-specific fires
        eq("reddit page but not blocked", inViewer.invoke(null, view(web("Reddit - The heart of the internet")), noReddit, true), null);
        // deep page: links far down are still found within the budget
        Node deep = wv(null); Node cur = deep;
        for (int i = 0; i < 30; i++) { Node nx = wv(null); cur.add(wv("filler " + i), nx); cur = nx; }
        cur.add(wv("r/one"), wv("r/two"), wv("u/three"), wv("u/four"));
        eq("deep links", inViewer.invoke(null, view(web("Home", deep)), sites, true), "reddit.com");

        // an address typed into a field that is part of the page itself
        eq("address field inside page", inViewer.invoke(null, view(web("Start page", n("android.widget.EditText", "old.reddit.com/r/all"))), sites, true), "reddit.com");
        eq("address field inside page, other site", inViewer.invoke(null, view(web("Start page", n("android.widget.EditText", "example.com"))), sites, true), null);
        eq("address field inside page, instagram", inViewer.invoke(null, view(web("Start page", n("android.widget.EditText", "instagram.com"))), noReddit, true), "instagram.com");
        eq("search words inside page", inViewer.invoke(null, view(web("Search", n("android.widget.EditText", "best reddit threads"))), sites, true), null);

        Method browserScreen = m("looksLikeBrowserScreen", AccessibilityNodeInfo.class);
        eq("browser screen native bar", browserScreen.invoke(null, view(n("android.widget.EditText", "example.com"), web("t"))), true);
        eq("browser screen bar in page", browserScreen.invoke(null, view(web("t", n("android.widget.EditText", null).hint("Search or type URL")))), true);
        eq("page without bar", browserScreen.invoke(null, view(tv("Ask Gemini"), web("t", wv("hello")))), false);
        eq("bar without page", browserScreen.invoke(null, view(n("android.widget.EditText", "example.com"))), false);

        Method read = m("readScreen", AccessibilityNodeInfo.class);
        BlockerService.Screen s = (BlockerService.Screen) read.invoke(null, view(tv(" Holdfast "), tv("Force Stop").desc("Button")));
        eq("screen exact", s.exact("holdfast"), true);
        eq("screen has", s.has("force stop"), true);
        eq("screen desc", s.exact("button"), true);
        eq("screen miss", s.has("uninstall"), false);

        System.out.println(passes + " passed, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
