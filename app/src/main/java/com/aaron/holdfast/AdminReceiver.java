package com.aaron.holdfast;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/** Being a device admin means Android refuses to uninstall Holdfast until admin is turned off. */
public class AdminReceiver extends DeviceAdminReceiver {
    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "This removes Holdfast's uninstall protection.";
    }
}
