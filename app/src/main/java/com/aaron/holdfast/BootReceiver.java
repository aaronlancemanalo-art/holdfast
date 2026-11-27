package com.aaron.holdfast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After every restart, and after Holdfast updates itself: put things back (see Repair). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            Repair.apply(c, new Rules(c));
            RepairJob.schedule(c);
        } catch (Throwable ignored) {
            // Never crash at startup: a crash here would show as "Holdfast keeps stopping".
        }
    }
}
