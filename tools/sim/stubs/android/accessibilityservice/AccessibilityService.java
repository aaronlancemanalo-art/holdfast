package android.accessibilityservice;
import java.util.*;
import android.view.accessibility.AccessibilityNodeInfo;
public abstract class AccessibilityService extends android.content.Context {
    public AccessibilityNodeInfo fakeRoot;
    public List<String> actions = new ArrayList<>();
    public AccessibilityNodeInfo getRootInActiveWindow() { return fakeRoot; }
    public AccessibilityNodeInfo homeRoot;
    public boolean homeWorks = true;
    public boolean performGlobalAction(int a) {
        actions.add(a == 1 ? "BACK" : a == 2 ? "HOME" : a == 8 ? "LOCK" : "ACTION" + a);
        if (a == 2 && homeWorks) fakeRoot = homeRoot;
        return true;
    }
    protected void onServiceConnected() { }
    public boolean onUnbind(android.content.Intent i) { return false; }
    public void onDestroy() { }
    public abstract void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent e);
    public abstract void onInterrupt();
}
