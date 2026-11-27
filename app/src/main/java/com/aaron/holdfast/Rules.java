package com.aaron.holdfast;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * All blocking rules live here.
 *
 * Core idea: tightening is instant, loosening waits DELAY_MS.
 * The wait is counted with the phone's uptime clock (elapsedRealtime), not the
 * wall clock, so changing the date/time in Settings does not skip the wait.
 */
public final class Rules {

    /** How long any loosening of the rules has to wait. Change this to tune it. */
    public static final long DELAY_MS = 24L * 60 * 60 * 1000;

    public static final String TYPE_APP = "app";
    public static final String TYPE_SITE = "site";
    public static final String TYPE_GUARD = "guard";
    public static final String TYPE_BROWSERS = "browsers";
    public static final String TYPE_SIDELOAD = "sideload";
    public static final String TYPE_MATURE = "mature";
    public static final String TYPE_MANGA = "manga";
    /** Waiting for this makes the laptop setup ready to be opened (see Repair). */
    public static final String TYPE_LAPTOP = "laptop";
    /** The laptop setup is ready: its window can be opened once, until this runs out unused. */
    public static final String TYPE_LAPTOP_READY = "laptopReady";
    /** Waiting for this lets the phone start in Safe mode again. */
    public static final String TYPE_SAFEMODE = "safemode";

    private static final String PREFS = "rules";
    private static final long TICK_MIN_MS = 5000;
    private static final long SETUP_WINDOW_MS = 90 * 1000;
    /** How long Developer options stay reachable for the one-time laptop setup. */
    public static final long LAPTOP_WINDOW_MS = 60L * 60 * 1000;
    /** How long the laptop setup stays ready to be opened once its wait is over. */
    public static final long LAPTOP_READY_MS = 24L * 60 * 60 * 1000;
    /** This many crashes of Holdfast in a row and Safe mode is let through again, as a way out. */
    private static final int RESCUE_AFTER_CRASHES = 3;

    /** The official Reddit app. On the blocked list it stays blocked whether or not it is installed. */
    public static final String REDDIT_APP = "com.reddit.frontpage";

    private static final List<String> DEFAULT_APPS = Arrays.asList(
            REDDIT_APP,
            "com.android.chrome");
    private static final List<String> DEFAULT_SITES = Arrays.asList(
            "reddit.com",
            "redd.it");

    public static final class Pending {
        public final String type;
        public final String value;
        public final long remainingMs;

        Pending(String type, String value, long remainingMs) {
            this.type = type;
            this.value = value;
            this.remainingMs = remainingMs;
        }
    }

    private final Context ctx;
    private final SharedPreferences p;

    public Rules(Context c) {
        ctx = c.getApplicationContext();
        p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("init", false)) {
            p.edit()
                    .putStringSet("apps", new HashSet<>(DEFAULT_APPS))
                    .putStringSet("sites", new HashSet<>(DEFAULT_SITES))
                    .putBoolean("guard", true)
                    .putBoolean("init", true)
                    .apply();
        }
        // Added in build 7: he asked for every browser to be blocked, so this starts on.
        if (!p.contains("allBrowsers")) p.edit().putBoolean("allBrowsers", true).apply();
        // Added in build 8: installing apps from files (APKs) is blocked, except Holdfast's own updates.
        if (!p.contains("noSideload")) p.edit().putBoolean("noSideload", true).apply();
        // Apps installed after this moment must come from an app store. Older apps are left alone.
        // For phones that already had the rule, use the day it shipped (build 8, 29 Oct 2026).
        if (!p.contains("sideloadSince")) p.edit().putLong("sideloadSince", 1793271600000L).apply();
        // Added in build 32: wherever Reddit is allowed, mature (NSFW) content on it is not.
        if (!p.contains("noMature")) p.edit().putBoolean("noMature", true).apply();
        // Added in build 34: manga reading apps and manga sites are blocked, so this starts on.
        if (!p.contains("noManga")) p.edit().putBoolean("noManga", true).apply();
        // Build 33: all of Reddit is blocked again, once, when this build first runs. The app had
        // been uninstalled, and the app picker only lists installed apps, so it could not be put
        // back on the list by hand. After this one time the usual rule applies: unblocking waits.
        if (!p.getBoolean("redditWall33", false)) {
            blockAllReddit();
            p.edit().putBoolean("redditWall33", true).apply();
        }
    }

    // ---------- reads ----------

    public Set<String> getApps() {
        return new HashSet<>(p.getStringSet("apps", new HashSet<String>()));
    }

    public Set<String> getSites() {
        return new HashSet<>(p.getStringSet("sites", new HashSet<String>()));
    }

    public boolean isGuardOn() {
        return p.getBoolean("guard", true);
    }

    public boolean sideloadBlocked() {
        return p.getBoolean("noSideload", true);
    }

    /** Holdfast opens this right before showing the installer for its own update. */
    public void openSelfUpdateWindow() {
        openWindow("selfUpdateUntil", 2 * 60 * 1000);
    }

    public boolean inSelfUpdateWindow() {
        return inWindow("selfUpdateUntil");
    }

    /**
     * A short opening in the rules, timed on the uptime clock. It belongs to the restart it was
     * opened in: the uptime clock starts over when the phone restarts, so without that an old
     * closing time would look far away and the window would stay open.
     */
    private void openWindow(String key, long ms) {
        p.edit().putLong(key, SystemClock.elapsedRealtime() + ms).putInt(key + "Boot", bootCount()).apply();
    }

    private boolean inWindow(String key) {
        return p.getInt(key + "Boot", Integer.MIN_VALUE) == bootCount()
                && SystemClock.elapsedRealtime() < p.getLong(key, 0);
    }

    /** Blocks every installed browser, including ones installed later. */
    public boolean allBrowsersBlocked() {
        return p.getBoolean("allBrowsers", true);
    }

    /** Reddit closes when something marked mature (NSFW, 18+) is on screen. Only matters where Reddit is allowed. */
    public boolean matureBlocked() {
        return p.getBoolean("noMature", true);
    }

    /** Manga reading apps don't open, and manga sites are closed inside every app. */
    public boolean mangaBlocked() {
        return p.getBoolean("noManga", true);
    }

    /** True while the Reddit app and both Reddit addresses are on the blocked lists. */
    public boolean redditBlocked() {
        return getApps().contains(REDDIT_APP) && getSites().containsAll(DEFAULT_SITES);
    }

    /** True if a change that would open some part of Reddit is waiting to apply. */
    public boolean redditUnblockWaiting() {
        for (Pending x : getPending()) {
            if (opensReddit(x.type, x.value)) return true;
        }
        return false;
    }

    public List<Pending> getPending() {
        List<Pending> out = new ArrayList<>();
        JSONArray arr = pendingArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            out.add(new Pending(o.optString("type"), o.optString("value"), o.optLong("rem")));
        }
        return out;
    }

    public boolean isPending(String type, String value) {
        for (Pending x : getPending()) {
            if (x.type.equals(type) && x.value.equals(value)) return true;
        }
        return false;
    }

    // ---------- tightening: instant ----------

    public synchronized void blockApp(String pkg) {
        Set<String> s = getApps();
        s.add(pkg);
        p.edit().putStringSet("apps", s).apply();
        cancelPending(TYPE_APP, pkg);
    }

    public synchronized void blockSite(String raw) {
        String d = normalizeDomain(raw);
        if (d.isEmpty()) return;
        Set<String> s = getSites();
        s.add(d);
        p.edit().putStringSet("sites", s).apply();
        cancelPending(TYPE_SITE, d);
    }

    public synchronized void guardOn() {
        p.edit().putBoolean("guard", true).apply();
        cancelPending(TYPE_GUARD, "");
    }

    /** Wall-clock time from which new installs must come from an app store. */
    public long sideloadSince() {
        return p.getLong("sideloadSince", 1793271600000L);
    }

    public synchronized void blockSideload() {
        if (!sideloadBlocked()) {
            // Whatever is installed at this moment stays allowed: the saved list is made again.
            p.edit().putLong("sideloadSince", System.currentTimeMillis())
                    .putBoolean("sideloadOkInit", false).apply();
        }
        p.edit().putBoolean("noSideload", true).apply();
        cancelPending(TYPE_SIDELOAD, "");
    }

    /** True once the list of older file-installed apps has been saved. */
    public boolean sideloadListReady() {
        return p.getBoolean("sideloadOkInit", false);
    }

    /**
     * Apps installed from files before the rule began, as "package|install time". They keep
     * working; any other app that didn't come from an app store doesn't open. A saved list
     * (instead of comparing dates) means the phone's clock has no say in it.
     */
    public Set<String> sideloadAllowed() {
        return new HashSet<>(p.getStringSet("sideloadOk", new HashSet<String>()));
    }

    public void setSideloadAllowed(Set<String> apps) {
        p.edit().putStringSet("sideloadOk", new HashSet<>(apps))
                .putBoolean("sideloadOkInit", true).apply();
    }

    public synchronized void blockAllBrowsers() {
        p.edit().putBoolean("allBrowsers", true).apply();
        cancelPending(TYPE_BROWSERS, "");
    }

    public synchronized void blockMature() {
        p.edit().putBoolean("noMature", true).apply();
        cancelPending(TYPE_MATURE, "");
    }

    public synchronized void blockManga() {
        p.edit().putBoolean("noManga", true).apply();
        cancelPending(TYPE_MANGA, "");
    }

    /**
     * Everything that keeps Reddit closed, in one go: the app (installed or not), the two Reddit
     * addresses (which also close other Reddit apps and Reddit under other web addresses), the
     * mature-content rule, and no waiting change left that would open any of them. Other
     * waiting changes are left alone.
     */
    public synchronized void blockAllReddit() {
        Set<String> apps = getApps();
        apps.add(REDDIT_APP);
        Set<String> sites = getSites();
        sites.addAll(DEFAULT_SITES);
        JSONArray arr = pendingArray();
        JSONArray keep = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || opensReddit(o.optString("type"), o.optString("value"))) continue;
            keep.put(o);
        }
        p.edit().putStringSet("apps", apps)
                .putStringSet("sites", sites)
                .putBoolean("noMature", true)
                .putString("pending", keep.toString())
                .apply();
    }

    /** True for a waiting change that would open some part of Reddit. */
    static boolean opensReddit(String type, String value) {
        if (TYPE_MATURE.equals(type)) return true;
        String v = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (TYPE_APP.equals(type)) return v.contains("reddit");
        if (TYPE_SITE.equals(type)) return v.contains("reddit") || v.contains("redd.it");
        return false;
    }

    /** Cancelling a pending loosening is itself a tightening, so it is instant. */
    public synchronized void cancelPending(int index) {
        JSONArray arr = pendingArray();
        JSONArray keep = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            if (i != index) keep.put(arr.opt(i));
        }
        p.edit().putString("pending", keep.toString()).apply();
    }

    // ---------- loosening: delayed ----------

    public synchronized void requestRemoval(String type, String value) {
        tick(true);
        if (isPending(type, value)) return;
        JSONArray arr = pendingArray();
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("value", value);
            o.put("rem", DELAY_MS);
            arr.put(o);
        } catch (JSONException ignored) {
            return;
        }
        SharedPreferences.Editor e = p.edit().putString("pending", arr.toString());
        stampClock(e);
        e.apply();
    }

    /** Counts down pending changes and applies any that are due. Cheap to call often. */
    public void tick() {
        tick(false);
    }

    private synchronized void tick(boolean force) {
        JSONArray arr = pendingArray();
        if (arr.length() == 0) return;

        long now = SystemClock.elapsedRealtime();
        int boot = bootCount();
        long last = p.getLong("lastTick", -1);
        int lastBoot = p.getInt("lastBoot", Integer.MIN_VALUE);

        long delta;
        if (last < 0) {
            delta = 0;
        } else if (boot != lastBoot || now < last) {
            delta = now; // rebooted: count only time since this boot
        } else {
            delta = now - last;
        }
        if (!force && last >= 0 && boot == lastBoot && delta < TICK_MIN_MS) return;

        Set<String> apps = getApps();
        Set<String> sites = getSites();
        boolean guard = isGuardOn();
        boolean browsers = allBrowsersBlocked();
        boolean sideload = sideloadBlocked();
        boolean mature = matureBlocked();
        boolean manga = mangaBlocked();
        boolean safeMode = safeModeBlocked();
        boolean laptopReady = false;
        JSONArray keep = new JSONArray();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            long rem = o.optLong("rem") - delta;
            if (rem <= 0) {
                String type = o.optString("type");
                String value = o.optString("value");
                if (TYPE_APP.equals(type)) apps.remove(value);
                else if (TYPE_SITE.equals(type)) sites.remove(value);
                else if (TYPE_GUARD.equals(type)) guard = false;
                else if (TYPE_BROWSERS.equals(type)) browsers = false;
                else if (TYPE_SIDELOAD.equals(type)) sideload = false;
                else if (TYPE_MATURE.equals(type)) mature = false;
                else if (TYPE_MANGA.equals(type)) manga = false;
                else if (TYPE_SAFEMODE.equals(type)) safeMode = false;
                else if (TYPE_LAPTOP.equals(type)) laptopReady = true;
                // TYPE_LAPTOP_READY running out: the setup was not opened in time, and lapses.
            } else {
                try {
                    o.put("rem", rem);
                } catch (JSONException ignored) {
                }
                keep.put(o);
            }
        }

        if (laptopReady) {
            try {
                JSONObject ready = new JSONObject();
                ready.put("type", TYPE_LAPTOP_READY);
                ready.put("value", "");
                ready.put("rem", LAPTOP_READY_MS);
                keep.put(ready);
            } catch (JSONException ignored) {
            }
        }

        SharedPreferences.Editor e = p.edit()
                .putStringSet("apps", apps)
                .putStringSet("sites", sites)
                .putBoolean("guard", guard)
                .putBoolean("allBrowsers", browsers)
                .putBoolean("noSideload", sideload)
                .putBoolean("noMature", mature)
                .putBoolean("noManga", manga)
                .putBoolean("noSafeMode", safeMode)
                .putString("pending", keep.toString());
        stampClock(e);
        e.apply();
    }

    // ---------- setup window for activating device admin ----------

    /**
     * Opening the "activate device admin" screen means visiting a Settings page that
     * shows this app's name, which the guard would normally block. This opens a short
     * window for that, and it only applies while the admin is NOT yet active.
     */
    public void openSetupWindow() {
        openWindow("setupUntil", SETUP_WINDOW_MS);
    }

    public boolean inSetupWindow() {
        return inWindow("setupUntil");
    }

    /**
     * The "Install unknown apps" page for Holdfast shows its name, so the guard would block it.
     * This short window lets only that page through (the service also checks the page text).
     */
    public void openInstallPermissionWindow() {
        openWindow("installUntil", SETUP_WINDOW_MS);
    }

    public boolean inInstallPermissionWindow() {
        return inWindow("installUntil");
    }

    // ---------- self-repair: the one-time laptop setup, and Safe mode ----------

    /**
     * The laptop setup in three steps. Asking for it starts the usual 24-hour wait. When the
     * wait is over the setup is "ready" for LAPTOP_READY_MS: its window can then be opened once,
     * at a moment of his choosing (he has to be at the laptop). The window lets Developer
     * options through for LAPTOP_WINDOW_MS and closes early once the setup is done.
     */
    public boolean laptopReady() {
        return isPending(TYPE_LAPTOP_READY, "");
    }

    /** Opens the window if the setup is ready. Returns true if it did. */
    public synchronized boolean openLaptopWindow() {
        tick(true);
        if (!laptopReady()) return false;
        cancelPending(TYPE_LAPTOP_READY, "");
        boolean devOn = false;
        try {
            devOn = Settings.Global.getInt(ctx.getContentResolver(),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
        } catch (Throwable ignored) {
        }
        p.edit().putBoolean("laptopDevWasOn", devOn).apply();
        openWindow("laptopUntil", LAPTOP_WINDOW_MS);
        return true;
    }

    /** True while Developer options are let through for the laptop setup. */
    public boolean inLaptopWindow() {
        return inWindow("laptopUntil");
    }

    public long laptopWindowLeftMs() {
        return inLaptopWindow() ? p.getLong("laptopUntil", 0) - SystemClock.elapsedRealtime() : 0;
    }

    /** Ends the laptop setup: the window closes, and a wait for it or a ready setup is dropped. */
    public synchronized void closeLaptopWindow() {
        p.edit().putLong("laptopUntil", 0).apply();
        cancelPending(TYPE_LAPTOP, "");
        cancelPending(TYPE_LAPTOP_READY, "");
    }

    /** True from asking for the laptop setup until it is done, given up or has lapsed. */
    public boolean laptopSetupUnderWay() {
        return inLaptopWindow() || laptopReady() || isPending(TYPE_LAPTOP, "");
    }

    /** Whether Developer options were already switched on when the laptop window opened. */
    public boolean devOptionsWereOn() {
        return p.getBoolean("laptopDevWasOn", false);
    }

    /** The phone ignores the Safe mode key at startup. Only takes effect with self-repair set up. */
    public boolean safeModeBlocked() {
        return p.getBoolean("noSafeMode", false);
    }

    public synchronized void blockSafeMode() {
        p.edit().putBoolean("noSafeMode", true).apply();
        cancelPending(TYPE_SAFEMODE, "");
    }

    /** Remembers whether Holdfast itself has set the phone's Safe mode switch, so it only undoes its own doing. */
    public boolean safeModeSwitchSet() {
        return p.getBoolean("safeSwitchSet", false);
    }

    public void noteSafeModeSwitch(boolean set) {
        if (safeModeSwitchSet() != set) p.edit().putBoolean("safeSwitchSet", set).apply();
    }

    /**
     * True after Holdfast has crashed several times in a row without once drawing its screen.
     * Safe mode is then let through again, so a broken build can't lock the phone in.
     */
    public boolean rescue() {
        return p.getInt("crashes", 0) >= RESCUE_AFTER_CRASHES;
    }

    public void countCrash() {
        p.edit().putInt("crashes", p.getInt("crashes", 0) + 1).commit();
    }

    /** Holdfast drew its screen: whatever crashed before, it works now. */
    public void healthy() {
        if (p.getInt("crashes", 0) != 0) p.edit().putInt("crashes", 0).apply();
    }

    // ---------- stats ----------

    public void countBlock() {
        String key = "blocks_" + today();
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply();
    }

    public int blocksToday() {
        return p.getInt("blocks_" + today(), 0);
    }

    // ---------- diagnostics ----------

    public void recordError(Throwable t) {
        StackTraceElement top = t.getStackTrace().length > 0 ? t.getStackTrace()[0] : null;
        String msg = t.getClass().getSimpleName()
                + (top == null ? "" : " at " + top.getFileName() + ":" + top.getLineNumber())
                + " (" + new SimpleDateFormat("d MMM HH:mm", Locale.ROOT).format(new Date()) + ")";
        p.edit().putString("lastError", msg).apply();
    }

    public String lastError() {
        return p.getString("lastError", null);
    }

    // ---------- helpers ----------

    public static String normalizeDomain(String raw) {
        String d = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        int scheme = d.indexOf("://");
        if (scheme >= 0) d = d.substring(scheme + 3);
        int slash = d.indexOf('/');
        if (slash >= 0) d = d.substring(0, slash);
        if (d.startsWith("www.")) d = d.substring(4);
        return d;
    }

    private void cancelPending(String type, String value) {
        JSONArray arr = pendingArray();
        JSONArray keep = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (type.equals(o.optString("type")) && value.equals(o.optString("value"))) continue;
            keep.put(o);
        }
        p.edit().putString("pending", keep.toString()).apply();
    }

    private JSONArray pendingArray() {
        try {
            return new JSONArray(p.getString("pending", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void stampClock(SharedPreferences.Editor e) {
        e.putLong("lastTick", SystemClock.elapsedRealtime()).putInt("lastBoot", bootCount());
    }

    private int bootCount() {
        return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
    }
}
