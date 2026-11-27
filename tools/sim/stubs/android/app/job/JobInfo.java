package android.app.job;
public class JobInfo {
    public int id; public long period; public boolean persisted;
    public static class Builder {
        JobInfo j = new JobInfo();
        public Builder(int id, android.content.ComponentName c) { j.id = id; }
        public Builder setPeriodic(long ms) { j.period = ms; return this; }
        public Builder setPersisted(boolean p) { j.persisted = p; return this; }
        public JobInfo build() { return j; }
    }
}
