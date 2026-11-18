package android.content;
import java.util.*;
public class MemPrefs implements SharedPreferences, SharedPreferences.Editor {
    public Map<String, Object> m = new HashMap<>();
    public boolean getBoolean(String k, boolean d) { return m.containsKey(k) ? (Boolean) m.get(k) : d; }
    public long getLong(String k, long d) { return m.containsKey(k) ? (Long) m.get(k) : d; }
    public int getInt(String k, int d) { return m.containsKey(k) ? (Integer) m.get(k) : d; }
    public String getString(String k, String d) { return m.containsKey(k) ? (String) m.get(k) : d; }
    @SuppressWarnings("unchecked") public Set<String> getStringSet(String k, Set<String> d) { return m.containsKey(k) ? (Set<String>) m.get(k) : d; }
    public boolean contains(String k) { return m.containsKey(k); }
    public Editor edit() { return this; }
    public Editor putBoolean(String k, boolean v) { m.put(k, v); return this; }
    public Editor putLong(String k, long v) { m.put(k, v); return this; }
    public Editor putInt(String k, int v) { m.put(k, v); return this; }
    public Editor putString(String k, String v) { m.put(k, v); return this; }
    public Editor putStringSet(String k, Set<String> v) { m.put(k, new HashSet<>(v)); return this; }
    public Editor remove(String k) { m.remove(k); return this; }
    public void apply() { } public boolean commit() { return true; }
}
