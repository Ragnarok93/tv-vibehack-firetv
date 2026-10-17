package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the Home key listener after boot and after the app is updated, and shows this home screen. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        HomeKeyService.start(context);
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) HomeKeyService.showHome(context);
    }
}
