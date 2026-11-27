package android.app.job;
public class JobScheduler {
    public JobInfo pending;
    public JobInfo getPendingJob(int id) { return pending != null && pending.id == id ? pending : null; }
    public int schedule(JobInfo j) { pending = j; return 1; }
}
