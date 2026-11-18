package android.os;
public class SystemClock {
    public static long uptimeMillis() { return android.Sim.now; }
    public static long elapsedRealtime() { return android.Sim.now; }
}
