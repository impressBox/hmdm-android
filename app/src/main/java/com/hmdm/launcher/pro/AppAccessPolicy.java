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

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.Application;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.ui.MainActivity;
import com.hmdm.launcher.util.RemoteLogger;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Decides which apps the user may keep in the foreground, and what to do when a forbidden app appears.
 * Shared by the usage-stats watchdog and the accessibility-based watchdog.
 *
 * Allowed: the launcher itself, apps listed in the server configuration (not marked for removal),
 * essential system components (system UI, input methods, telephony, permission dialogs),
 * and Settings while the admin has temporarily enabled it.
 * Nothing is blocked while the configuration is absent, in permissive mode, or in lock task kiosk mode.
 */
public class AppAccessPolicy {

    // Components which must never be blocked, otherwise the device becomes unusable
    private static final Set<String> SYSTEM_ALLOWED = new HashSet<>(Arrays.asList(
            "android",
            Const.SYSTEM_UI_PACKAGE_NAME,
            Const.GSF_PACKAGE_NAME,
            "com.google.android.gms",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.android.emergency",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.managedprovisioning",
            "com.android.keychain",
            "com.android.certinstaller",
            Const.KIOSK_BROWSER_PACKAGE_NAME,
            Const.APUPPET_PACKAGE_NAME
    ));

    // Don't flood the log / UI with the same package
    private static final long BLOCK_REPEAT_MS = 2000;

    private static volatile long permissiveUntil = 0;
    private static volatile long settingsUntil = 0;
    private static volatile boolean permanentPermissive = false;
    private static volatile String lastBlockedPackage = null;
    private static volatile long lastBlockedTime = 0;
    private static boolean receiverRegistered = false;

    private static final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            switch (action) {
                case Const.ACTION_ENABLE_SETTINGS:
                    settingsUntil = SystemClock.elapsedRealtime() + Const.PERMISSIVE_MODE_TIME;
                    break;
                case Const.ACTION_PERMISSIVE_MODE:
                    permissiveUntil = SystemClock.elapsedRealtime() + Const.PERMISSIVE_MODE_TIME;
                    RemoteLogger.log(context, Const.LOG_INFO, "App control: temporary permissive mode enabled");
                    break;
                case Const.ACTION_TOGGLE_PERMISSIVE:
                    permanentPermissive = intent.getBooleanExtra(Const.EXTRA_ENABLED, false);
                    break;
            }
        }
    };

    private AppAccessPolicy() {}

    public static synchronized void register(Context context) {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(Const.ACTION_ENABLE_SETTINGS);
        filter.addAction(Const.ACTION_PERMISSIVE_MODE);
        filter.addAction(Const.ACTION_TOGGLE_PERMISSIVE);
        LocalBroadcastManager.getInstance(context.getApplicationContext()).registerReceiver(receiver, filter);
        receiverRegistered = true;
    }

    public static boolean isControlActive(Context context) {
        ServerConfig config = SettingsHelper.getInstance(context).getConfig();
        if (config == null) {
            return false;
        }
        if (permanentPermissive || config.isPermissive()) {
            return false;
        }
        if (SystemClock.elapsedRealtime() < permissiveUntil) {
            return false;
        }
        // In kiosk mode the lock task mode is the enforcement mechanism
        return !KioskController.isRunning(context);
    }

    public static boolean isAllowed(Context context, String packageName) {
        if (TextUtils.isEmpty(packageName) || !isControlActive(context)) {
            return true;
        }
        if (packageName.equals(context.getPackageName()) || SYSTEM_ALLOWED.contains(packageName)) {
            return true;
        }
        if (isInputMethod(context, packageName)) {
            return true;
        }
        if (packageName.equals(Const.SETTINGS_PACKAGE_NAME)
                && SystemClock.elapsedRealtime() < settingsUntil) {
            // Sliding window: keep Settings allowed while the admin is still using it
            settingsUntil = SystemClock.elapsedRealtime() + Const.PERMISSIVE_MODE_TIME;
            return true;
        }
        ServerConfig config = SettingsHelper.getInstance(context).getConfig();
        if (config.getApplications() != null) {
            for (Application app : config.getApplications()) {
                if (!app.isRemove() && packageName.equals(app.getPkg())) {
                    return true;
                }
            }
        }
        return false;
    }

    // Brings the launcher back and shows the "application not allowed" overlay
    public static void block(Context context, String packageName, String source) {
        long now = SystemClock.elapsedRealtime();
        boolean repeated = packageName.equals(lastBlockedPackage) && now - lastBlockedTime < BLOCK_REPEAT_MS;
        lastBlockedPackage = packageName;
        lastBlockedTime = now;
        if (!repeated) {
            RemoteLogger.log(context, Const.LOG_INFO, "App control (" + source + "): blocked " + packageName);
        }

        Intent home = new Intent(context, MainActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            context.startActivity(home);
        } catch (Exception e) {
            // Background activity starts may be restricted; the overlay below still covers the app
        }

        Intent hide = new Intent(Const.ACTION_HIDE_SCREEN);
        hide.putExtra(Const.PACKAGE_NAME, packageName);
        LocalBroadcastManager.getInstance(context).sendBroadcast(hide);
    }

    private static boolean isInputMethod(Context context, String packageName) {
        String ime = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime == null) {
            return false;
        }
        ComponentName cn = ComponentName.unflattenFromString(ime);
        return cn != null && packageName.equals(cn.getPackageName());
    }
}
