package android.widget;
public class Toast {
    public static java.util.List<String> shown = new java.util.ArrayList<>();
    String t;
    public static Toast makeText(android.content.Context c, CharSequence t, int d) { Toast x = new Toast(); x.t = t.toString(); return x; }
    public void show() { shown.add(t); }
}
