package android.view.accessibility;
public class AccessibilityEvent {
    public String pkg, cls; public int type;
    public AccessibilityEvent(String pkg, int type, String cls) { this.pkg = pkg; this.type = type; this.cls = cls; }
    public CharSequence getPackageName() { return pkg; }
    public int getEventType() { return type; }
    public CharSequence getClassName() { return cls; }
}
