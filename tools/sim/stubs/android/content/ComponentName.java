package android.content;
public class ComponentName {
    public final String pkg, cls;
    public ComponentName(Context c, Class<?> k) { pkg = c.getPackageName(); cls = k.getName(); }
    public ComponentName(String pkg, String cls) { this.pkg = pkg; this.cls = cls; }
    public String flattenToString() { return pkg + "/" + cls; }
    public static ComponentName unflattenFromString(String s) {
        int i = s == null ? -1 : s.indexOf('/');
        if (i <= 0 || i + 1 >= s.length()) return null;
        String p = s.substring(0, i), c = s.substring(i + 1);
        return new ComponentName(p, c.startsWith(".") ? p + c : c);
    }
    @Override public boolean equals(Object o) { return o instanceof ComponentName && ((ComponentName) o).pkg.equals(pkg) && ((ComponentName) o).cls.equals(cls); }
    @Override public int hashCode() { return pkg.hashCode() * 31 + cls.hashCode(); }
}
