package android.content;
import java.util.Set;
public interface SharedPreferences {
    boolean getBoolean(String k, boolean d); long getLong(String k, long d); int getInt(String k, int d);
    String getString(String k, String d); Set<String> getStringSet(String k, Set<String> d); boolean contains(String k);
    Editor edit();
    interface Editor {
        Editor putBoolean(String k, boolean v); Editor putLong(String k, long v); Editor putInt(String k, int v);
        Editor putString(String k, String v); Editor putStringSet(String k, Set<String> v); Editor remove(String k);
        void apply(); boolean commit();
    }
}
