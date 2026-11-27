package android.app.job;
public abstract class JobService extends android.content.Context {
    public abstract boolean onStartJob(JobParameters p);
    public abstract boolean onStopJob(JobParameters p);
}
