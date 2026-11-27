package android.provider;
import java.util.*;
import android.content.ContentResolver;
/** Phone settings as two small tables. Writing needs the permission, as on a real phone. */
public class Settings {
    public static Map<String, String> secure = new HashMap<>(), global = new HashMap<>();
    public static List<String> writes = new ArrayList<>();
    public static void reset() { secure.clear(); global.clear(); writes.clear(); }
    static void mayWrite() { if (!android.content.Context.secureSettingsGranted) throw new SecurityException("WRITE_SECURE_SETTINGS not granted"); }
    public static class Secure {
        public static String getString(ContentResolver r, String k) { return secure.get(k); }
        public static boolean putString(ContentResolver r, String k, String v) { mayWrite(); secure.put(k, v); writes.add(k + "=" + v); return true; }
        public static int getInt(ContentResolver r, String k, int d) { String v = secure.get(k); return v == null ? d : Integer.parseInt(v); }
        public static boolean putInt(ContentResolver r, String k, int v) { mayWrite(); secure.put(k, "" + v); writes.add(k + "=" + v); return true; }
    }
    public static class Global {
        public static int getInt(ContentResolver r, String k, int d) {
            if ("boot_count".equals(k)) return android.Sim.boot;
            String v = global.get(k); return v == null ? d : Integer.parseInt(v);
        }
        public static boolean putInt(ContentResolver r, String k, int v) { mayWrite(); global.put(k, "" + v); writes.add(k + "=" + v); return true; }
    }
}
