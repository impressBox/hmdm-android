/*
 * Copyright (C) 2026 impressBox
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.launcher.pro.service;

import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.pro.AppAccessPolicy;
import com.hmdm.launcher.pro.ProUtils;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Watchdog which detects the foreground app via UsageStatsManager and returns the user
 * to the launcher if the app is not allowed by the configuration (see {@link AppAccessPolicy}).
 * Requires the "Usage access" special permission.
 */
public class CheckForegroundApplicationService extends Service {

    private static final long POLL_INTERVAL_MS = 1000;
    // Look-back window for usage events; the last known foreground app is kept between polls
    private static final long EVENTS_WINDOW_MS = 10000;

    private ScheduledExecutorService executor;
    private String foregroundPackage;
    private long lastQueryTime = 0;

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            stopSelf();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppAccessPolicy.register(this);
        LocalBroadcastManager.getInstance(this).registerReceiver(stopReceiver, new IntentFilter(Const.ACTION_SERVICE_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!ProUtils.checkUsageStatistics(this)) {
            Log.w(Const.LOG_TAG, "Foreground app control: usage access not granted, service stopped");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor();
            executor.scheduleWithFixedDelay(this::check, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            Log.i(Const.LOG_TAG, "Foreground app control started");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stopReceiver);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        Log.i(Const.LOG_TAG, "Foreground app control stopped");
        super.onDestroy();
    }

    private void check() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                return;
            }
            if (!AppAccessPolicy.isControlActive(this)) {
                return;
            }
            String pkg = queryForegroundPackage();
            if (pkg != null && !AppAccessPolicy.isAllowed(this, pkg)) {
                AppAccessPolicy.block(this, pkg, "usage stats");
                // Assume the launcher is in front now; the next event will correct it
                foregroundPackage = getPackageName();
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Foreground app control: check failed: " + e.getMessage());
        }
    }

    private String queryForegroundPackage() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return foregroundPackage;
        }
        long now = System.currentTimeMillis();
        long from = lastQueryTime > 0 ? Math.max(lastQueryTime - 1000, now - EVENTS_WINDOW_MS) : now - EVENTS_WINDOW_MS;
        lastQueryTime = now;
        UsageEvents events = usm.queryEvents(from, now);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            if (isForegroundEvent(event.getEventType())) {
                foregroundPackage = event.getPackageName();
            }
        }
        return foregroundPackage;
    }

    @SuppressWarnings("deprecation")
    private static boolean isForegroundEvent(int type) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return type == UsageEvents.Event.ACTIVITY_RESUMED;
        }
        return type == UsageEvents.Event.MOVE_TO_FOREGROUND;
    }
}
