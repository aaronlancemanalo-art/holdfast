package android.content;
import java.util.*;
public abstract class Context {
    public static Map<String, MemPrefs> prefs = new HashMap<>();
    public android.content.pm.PackageManager pm = new android.content.pm.PackageManager();
    public android.content.res.Resources res = new android.content.res.Resources();
    public android.app.ActivityManager am = new android.app.ActivityManager();
    public android.app.admin.DevicePolicyManager dpm = new android.app.admin.DevicePolicyManager();
    public List<Intent> started = new ArrayList<>();
    public List<BroadcastReceiver> receivers = new ArrayList<>();
    /** The permission only a computer can hand out (adb shell pm grant ...). */
    public static boolean secureSettingsGranted = false;
    public static android.app.job.JobScheduler jobs = new android.app.job.JobScheduler();
    public int checkSelfPermission(String p) { return secureSettingsGranted ? 0 : -1; }
    public Context getApplicationContext() { return this; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return prefs.computeIfAbsent(name, k -> new MemPrefs()); }
    public ContentResolver getContentResolver() { return new ContentResolver(); }
    public String getPackageName() { return "com.aaron.holdfast"; }
    public android.content.pm.PackageManager getPackageManager() { return pm; }
    public android.content.res.Resources getResources() { return res; }
    public Object getSystemService(String name) {
        if ("activity".equals(name)) return am;
        if ("device_policy".equals(name)) return dpm;
        if ("jobscheduler".equals(name)) return jobs;
        return null;
    }
    public void startActivity(Intent i) { started.add(i); }
    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f) { receivers.add(r); return null; }
    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f, int flags) { receivers.add(r); return null; }
    public void unregisterReceiver(BroadcastReceiver r) { receivers.remove(r); }
}
