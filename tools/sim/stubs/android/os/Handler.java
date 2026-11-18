package android.os;
public class Handler {
    public Handler(Looper l) { }
    public boolean postDelayed(Runnable r, long ms) { android.Sim.timers.add(new Object[]{android.Sim.now + ms, r}); return true; }
    public void removeCallbacks(Runnable r) { android.Sim.timers.removeIf(t -> t[1] == r); }
    public void removeCallbacksAndMessages(Object token) { android.Sim.timers.clear(); }
}
