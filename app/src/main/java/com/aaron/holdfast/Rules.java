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

    private static final String PREFS = "rules";
    private static final long TICK_MIN_MS = 5000;
    private static final long SETUP_WINDOW_MS = 90 * 1000;

    private static final List<String> DEFAULT_APPS = Arrays.asList(
            "com.reddit.frontpage",
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

    /** Blocks every installed browser, including ones installed later. */
    public boolean allBrowsersBlocked() {
        return p.getBoolean("allBrowsers", true);
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

    public synchronized void blockAllBrowsers() {
        p.edit().putBoolean("allBrowsers", true).apply();
        cancelPending(TYPE_BROWSERS, "");
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
            } else {
                try {
                    o.put("rem", rem);
                } catch (JSONException ignored) {
                }
                keep.put(o);
            }
        }

        SharedPreferences.Editor e = p.edit()
                .putStringSet("apps", apps)
                .putStringSet("sites", sites)
                .putBoolean("guard", guard)
                .putBoolean("allBrowsers", browsers)
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
        p.edit().putLong("setupUntil", SystemClock.elapsedRealtime() + SETUP_WINDOW_MS).apply();
    }

    public boolean inSetupWindow() {
        return SystemClock.elapsedRealtime() < p.getLong("setupUntil", 0);
    }

    /**
     * The "Install unknown apps" page for Holdfast shows its name, so the guard would block it.
     * This short window lets only that page through (the service also checks the page text).
     */
    public void openInstallPermissionWindow() {
        p.edit().putLong("installUntil", SystemClock.elapsedRealtime() + SETUP_WINDOW_MS).apply();
    }

    public boolean inInstallPermissionWindow() {
        return SystemClock.elapsedRealtime() < p.getLong("installUntil", 0);
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
