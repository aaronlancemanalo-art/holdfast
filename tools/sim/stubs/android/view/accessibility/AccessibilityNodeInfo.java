package android.view.accessibility;
import java.util.List;
public class AccessibilityNodeInfo {
    public AccessibilityNodeInfo() { }
    public CharSequence getClassName() { return null; }
    public CharSequence getText() { return null; }
    public CharSequence getContentDescription() { return null; }
    public CharSequence getHintText() { return null; }
    public String getViewIdResourceName() { return null; }
    public CharSequence getPackageName() { return null; }
    public int getChildCount() { return 0; }
    public AccessibilityNodeInfo getChild(int i) { return null; }
    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewId(String id) { return null; }
}
