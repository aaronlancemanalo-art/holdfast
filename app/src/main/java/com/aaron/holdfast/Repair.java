package com.aaron.holdfast;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Self-repair. With one permission that only a computer can hand out (see the README),
 * Holdfast can put back what keeps it running:
 *  - its own switch under Accessibility, which Android turns off when the phone has been in
 *    Safe mode, and which anyone can turn off by hand;
 *  - USB debugging, kept off while tamper protection is on;
 *  - the phone's own "no Safe mode" switch, if that rule is on. Android reads that switch at
 *    startup, before it looks at the Safe mode key.
 * Without the permission nothing here does anything.
 */
final class Repair {

    private Repair() {
    }

    static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    /** What a laptop has to send, once. */
    static final String COMMAND = "adb shell pm grant com.aaron.holdfast " + PERMISSION;

    /** Android's switch for "ignore the Safe mode key at startup". */
    private static final String SAFE_BOOT_DISALLOWED = "safe_boot_disallowed";
    private static final String ADB_WIFI_ENABLED = "adb_wifi_enabled";

    static boolean granted(Context c) {
        try {
            return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True if USB or wireless debugging is known to be switched on. */
    static boolean debuggingOn(Context c) {
        return usbDebugging(c) || wirelessDebugging(c) == 1;
    }

    private static boolean usbDebugging(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 1 on, 0 off, -1 if Android won't say (newer versions don't let apps read this one). */
    private static int wirelessDebugging(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), ADB_WIFI_ENABLED, 0) != 0 ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** True if Holdfast is on Android's list of switched-on accessibility services. */
    static boolean serviceListed(Context c) {
        ComponentName me = new ComponentName(c, BlockerService.class);
        String cur = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (cur == null) return false;
        for (String one : cur.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(one);
            if (me.equals(cn)) return true;
        }
        return false;
    }

    /**
     * Puts things back the way the rules say. Safe to call often: it only writes what is not
     * already so. While tamper protection is off it leaves the blocker and USB debugging
     * alone, and undoes its own Safe mode switch.
     */
    static void apply(Context c, Rules rules) {
        if (!granted(c)) return;
        ContentResolver cr = c.getContentResolver();
        boolean guard = rules.isGuardOn();
        if (guard) {
            try {
                if (!serviceListed(c)) {
                    String me = new ComponentName(c, BlockerService.class).flattenToString();
                    String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            cur == null || cur.isEmpty() ? me : cur + ":" + me);
                }
                if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                }
            } catch (Throwable t) {
                rules.recordError(t);
            }
            try {
                if (usbDebugging(c)) Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 0);
                // Where it can't be read, it is simply written off again.
                if (wirelessDebugging(c) != 0) Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 0);
            } catch (Throwable t) {
                rules.recordError(t);
            }
        }
        try {
            boolean block = guard && rules.safeModeBlocked() && !rules.rescue();
            if (block) {
                Settings.Global.putInt(cr, SAFE_BOOT_DISALLOWED, 1);
                rules.noteSafeModeSwitch(true);
            } else if (rules.safeModeSwitchSet()) {
                Settings.Global.putInt(cr, SAFE_BOOT_DISALLOWED, 0);
                rules.noteSafeModeSwitch(false);
            }
        } catch (Throwable t) {
            rules.recordError(t);
        }
    }

    /**
     * Wraps up the laptop setup once the permission has arrived: the window closes, Developer
     * options go back to how they were, and USB debugging goes off. Returns true if it did.
     */
    static boolean finishSetup(Context c, Rules rules) {
        if (!granted(c)) return false;
        if (!rules.laptopSetupUnderWay()) return false;
        boolean devWasOn = rules.devOptionsWereOn();
        rules.closeLaptopWindow();
        apply(c, rules);
        if (!devWasOn) {
            try {
                Settings.Global.putInt(c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);
            } catch (Throwable t) {
                rules.recordError(t);
            }
        }
        return true;
    }

    /**
     * Counts Holdfast's own crashes. After a few in a row with no working screen in between,
     * Safe mode is let through again (see Rules.rescue), so a broken build can still be removed.
     */
    static void watchCrashes(Context c) {
        final Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
        if (next instanceof CrashWatch) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashWatch(c.getApplicationContext(), next));
    }

    private static final class CrashWatch implements Thread.UncaughtExceptionHandler {
        private final Context app;
        private final Thread.UncaughtExceptionHandler next;

        CrashWatch(Context app, Thread.UncaughtExceptionHandler next) {
            this.app = app;
            this.next = next;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            try {
                Rules rules = new Rules(app);
                rules.countCrash();
                if (rules.rescue()) apply(app, rules);
            } catch (Throwable ignored) {
            }
            if (next != null) next.uncaughtException(t, e);
        }
    }
}
