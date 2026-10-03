package com.chesshint.panel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The STOP button of the notification. It is a broadcast receiver on purpose: it still works when
 * the service itself is already gone - which is exactly the case where a floating icon used to
 * stay on screen for ever because there was nothing left to stop it.
 */
public class StopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try { OverlayService.stopEverything(ctx); } catch (Throwable ignored) { }
    }
}
