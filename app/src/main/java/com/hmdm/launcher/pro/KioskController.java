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

package com.hmdm.launcher.pro;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.Application;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.pro.service.CheckForegroundApplicationService;
import com.hmdm.launcher.util.LegacyUtils;
import com.hmdm.launcher.util.RemoteLogger;
import com.hmdm.launcher.util.Utils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Single-app ("COSU") kiosk mode based on Android lock task mode.
 * Requires the launcher to be the device owner.
 */
public class KioskController {

    private KioskController() {}

    public static boolean isKioskAppInstalled(Context context) {
        ServerConfig config = SettingsHelper.getInstance(context).getConfig();
        if (config == null || config.getMainApp() == null || config.getMainApp().trim().isEmpty()) {
            return false;
        }
        return context.getPackageManager().getLaunchIntentForPackage(config.getMainApp().trim()) != null;
    }

    public static boolean isRunning(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return false;
        }
        // LOCK_TASK_MODE_PINNED (screen pinning by the user) is not our kiosk
        return am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_LOCKED;
    }

    public static Intent getLaunchIntent(Context context, String kioskApp) {
        if (kioskApp == null || kioskApp.trim().isEmpty()) {
            Log.w(Const.LOG_TAG, "Kiosk: main app is not configured");
            return null;
        }
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(kioskApp.trim());
        if (intent == null) {
            Log.w(Const.LOG_TAG, "Kiosk: no launchable activity in " + kioskApp);
        }
        return intent;
    }

    public static boolean start(Activity activity, String kioskApp, boolean enableSettings) {
        Intent launchIntent = getLaunchIntent(activity, kioskApp);
        if (launchIntent == null) {
            return false;
        }
        if (!Utils.isDeviceOwner(activity)) {
            RemoteLogger.log(activity, Const.LOG_WARN, "Kiosk: device owner rights are required for lock task mode");
            return false;
        }
        try {
            applyAllowedPackages(activity, kioskApp, enableSettings);
            applyFeatures(activity);
            if (!isRunning(activity)) {
                activity.startLockTask();
                RemoteLogger.log(activity, Const.LOG_INFO, "Kiosk: lock task mode started for " + kioskApp);
            }
        } catch (Exception e) {
            RemoteLogger.log(activity, Const.LOG_WARN, "Kiosk: failed to start lock task mode: " + e.getMessage());
            return false;
        }

        // The foreground app watchdog is not needed: lock task mode already prevents leaving the allowed apps
        try {
            activity.stopService(new Intent(activity, CheckForegroundApplicationService.class));
        } catch (Exception e) {
            // Ignore
        }

        // If the launcher itself is the kiosk app, this re-enters MainActivity which then draws its content
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        activity.startActivity(launchIntent);
        return true;
    }

    public static void stop(Activity activity) {
        try {
            if (isRunning(activity)) {
                activity.stopLockTask();
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Kiosk: stopLockTask failed: " + e.getMessage());
        }
        DevicePolicyManager dpm = devicePolicyManager(activity);
        if (dpm != null && Utils.isDeviceOwner(activity)) {
            try {
                dpm.setLockTaskPackages(LegacyUtils.getAdminComponentName(activity), new String[0]);
            } catch (Exception e) {
                Log.w(Const.LOG_TAG, "Kiosk: failed to reset lock task packages: " + e.getMessage());
            }
        }
        RemoteLogger.log(activity, Const.LOG_INFO, "Kiosk: lock task mode stopped");
    }

    public static void applyAllowedPackages(Context context, String kioskApp, boolean enableSettings) {
        DevicePolicyManager dpm = devicePolicyManager(context);
        if (dpm == null || !Utils.isDeviceOwner(context)) {
            return;
        }
        Set<String> packages = new LinkedHashSet<>();
        packages.add(context.getPackageName());
        if (kioskApp != null && !kioskApp.trim().isEmpty()) {
            packages.add(kioskApp.trim());
        }
        ServerConfig config = SettingsHelper.getInstance(context).getConfig();
        if (config != null && config.getApplications() != null) {
            for (Application app : config.getApplications()) {
                if (!app.isRemove() && app.getPkg() != null) {
                    packages.add(app.getPkg());
                }
            }
        }
        if (enableSettings) {
            packages.add(Const.SETTINGS_PACKAGE_NAME);
        }
        try {
            dpm.setLockTaskPackages(LegacyUtils.getAdminComponentName(context), packages.toArray(new String[0]));
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Kiosk: setLockTaskPackages failed: " + e.getMessage());
        }
    }

    // Translates the kiosk options from the server configuration into lock task features (Android 9+)
    public static void applyFeatures(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        DevicePolicyManager dpm = devicePolicyManager(context);
        ServerConfig config = SettingsHelper.getInstance(context).getConfig();
        if (dpm == null || config == null || !Utils.isDeviceOwner(context)) {
            return;
        }
        int features = DevicePolicyManager.LOCK_TASK_FEATURE_NONE;
        if (isTrue(config.getKioskHome())) {
            features |= DevicePolicyManager.LOCK_TASK_FEATURE_HOME;
        }
        if (isTrue(config.getKioskRecents())) {
            // Overview requires the Home button
            features |= DevicePolicyManager.LOCK_TASK_FEATURE_HOME | DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
        }
        if (isTrue(config.getKioskNotifications())) {
            // Notifications require the Home button
            features |= DevicePolicyManager.LOCK_TASK_FEATURE_HOME | DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS;
        }
        if (isTrue(config.getKioskSystemInfo())) {
            features |= DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO;
        }
        if (isTrue(config.getKioskKeyguard())) {
            features |= DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD;
        }
        if (!isTrue(config.getKioskLockButtons())) {
            // Power button menu is available unless the admin locks the buttons
            features |= DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS;
        }
        try {
            dpm.setLockTaskFeatures(LegacyUtils.getAdminComponentName(context), features);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Kiosk: setLockTaskFeatures failed: " + e.getMessage());
        }
    }

    private static boolean isTrue(Boolean value) {
        return value != null && value;
    }

    private static DevicePolicyManager devicePolicyManager(Context context) {
        return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }
}
