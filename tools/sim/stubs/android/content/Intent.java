package android.content;
import java.util.*;
public class Intent {
    public String action; public String uri; public Class<?> cls; public Map<String, String> extras = new HashMap<>();
    public Intent() { }
    public Intent(String action) { this.action = action; }
    public Intent(String action, android.net.Uri u) { this.action = action; this.uri = u == null ? null : u.s; }
    public Intent(Context c, Class<?> k) { this.cls = k; }
    public Intent addFlags(int f) { return this; }
    public Intent addCategory(String c) { return this; }
    public Intent putExtra(String k, String v) { extras.put(k, v); return this; }
    public String getAction() { return action; }
}
