package android;
import java.util.*;
/** Test controls: a fake clock and timer queue. */
public class Sim {
    public static long now = 10_000_000L;
    public static List<Object[]> timers = new ArrayList<>();
    public static void reset() { now = 10_000_000L; timers.clear(); }
    public static void advance(long ms) {
        long end = now + ms;
        while (true) {
            Object[] next = null;
            for (Object[] t : timers) if ((Long) t[0] <= end && (next == null || (Long) t[0] < (Long) next[0])) next = t;
            if (next == null) break;
            timers.remove(next);
            now = Math.max(now, (Long) next[0]);
            ((Runnable) next[1]).run();
        }
        now = end;
    }
}
