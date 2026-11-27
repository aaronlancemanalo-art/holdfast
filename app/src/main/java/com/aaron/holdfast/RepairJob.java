package com.aaron.holdfast;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** A look every quarter of an hour, in case Holdfast was switched off in between (see Repair). */
public class RepairJob extends JobService {

    private static final int ID = 3601;

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null || js.getPendingJob(ID) != null) return;
            js.schedule(new JobInfo.Builder(ID, new ComponentName(c, RepairJob.class))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            Repair.apply(this, new Rules(this));
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
