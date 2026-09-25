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

package com.hmdm.launcher.impressbox;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.util.LegacyUtils;
import com.hmdm.launcher.util.RemoteLogger;
import com.hmdm.launcher.util.Utils;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Keeps ADB reachable on impressBox panels, so a technician (or the Writer) can always get in:
 * checked when the launcher process starts and then every few minutes, so ADB switched off in
 * Settings, or a "no_debugging_features" restriction from the server configuration, is undone.
 *
 * USB ADB: the device owner clears DISALLOW_DEBUGGING_FEATURES and sets adb_enabled (and the
 * developer options switch it depends on) back to 1.
 *
 * Remote ADB: the classic "adb connect ip:5555" listener (service.adb.tcp.port) is a system property
 * that no app can set, not even the device owner. The launcher checks whether adbd listens on 5555;
 * if not, on Android 11+ it turns on Wireless debugging (adb_wifi_enabled, needs pairing and Wi-Fi).
 * Every state change is reported to the server log.
 *
 * Writing adb_enabled / development_settings_enabled / adb_wifi_enabled needs WRITE_SECURE_SETTINGS,
 * which the Writer grants during provisioning.
 */
public class AdbKeeper {

    private static final long CHECK_INTERVAL_MS = 5 * 60 * 1000;
    private static final int ADB_TCP_PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 500;
    // Settings.Global.ADB_WIFI_ENABLED is hidden API (Android 11+)
    private static final String ADB_WIFI_ENABLED = "adb_wifi_enabled";

    private static Handler handler;
    private static String lastState;

    private AdbKeeper() {}

    /** Checks now and then every CHECK_INTERVAL_MS, on a background thread. Safe to call more than once. */
    public static synchronized void start(Context context) {
        if (!BuildConfig.KEEP_ADB_ENABLED || handler != null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        HandlerThread thread = new HandlerThread("impressbox-adb");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                ensure(appContext);
                handler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        });
    }

    /** Turns ADB back on where it was switched off. Blocking (checks a local socket): not on the main thread. */
    public static void ensure(Context context) {
        if (!Utils.isDeviceOwner(context)) {
            return;
        }
        try {
            StringBuilder changed = new StringBuilder();
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = LegacyUtils.getAdminComponentName(context);
            ContentResolver resolver = context.getContentResolver();
            boolean secureSettings = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED;

            // Debugging features must not be restricted, otherwise adbd stays off whatever the settings say
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (um != null && um.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES)) {
                try {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES);
                    changed.append(" debugging restriction cleared;");
                } catch (Exception e) {
                    Log.w(Const.LOG_TAG, "ADB: cannot clear DISALLOW_DEBUGGING_FEATURES: " + e.getMessage());
                }
            }

            // Turning developer options off also turns ADB off in Settings
            if (getGlobalInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) != 1 && secureSettings) {
                if (putGlobalInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)) {
                    changed.append(" developer options on;");
                }
            }

            if (getGlobalInt(resolver, Settings.Global.ADB_ENABLED) != 1) {
                try {
                    dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1");
                } catch (Exception e) {
                    Log.w(Const.LOG_TAG, "ADB: setGlobalSetting(adb_enabled) failed: " + e.getMessage());
                }
                if (getGlobalInt(resolver, Settings.Global.ADB_ENABLED) != 1 && secureSettings) {
                    putGlobalInt(resolver, Settings.Global.ADB_ENABLED, 1);
                }
                changed.append(getGlobalInt(resolver, Settings.Global.ADB_ENABLED) == 1
                        ? " USB ADB on;" : " USB ADB could not be turned on;");
            }

            // Remote ADB
            String remote;
            if (isPortListening(ADB_TCP_PORT)) {
                remote = "network ADB on port " + ADB_TCP_PORT;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && secureSettings) {
                if (getGlobalInt(resolver, ADB_WIFI_ENABLED) != 1 && putGlobalInt(resolver, ADB_WIFI_ENABLED, 1)) {
                    changed.append(" wireless debugging on;");
                }
                remote = "no network ADB on port " + ADB_TCP_PORT
                        + (getGlobalInt(resolver, ADB_WIFI_ENABLED) == 1 ? ", wireless debugging on (needs pairing, Wi-Fi only)" : "");
            } else {
                remote = "no network ADB on port " + ADB_TCP_PORT + " (an app cannot enable it; use adb tcpip "
                        + ADB_TCP_PORT + " or the vendor setting)";
            }

            String state = "USB ADB " + (getGlobalInt(resolver, Settings.Global.ADB_ENABLED) == 1 ? "on" : "off")
                    + ", " + remote;
            if (changed.length() > 0) {
                RemoteLogger.log(context, Const.LOG_INFO, "ADB:" + changed + " now " + state);
            } else if (!state.equals(lastState)) {
                RemoteLogger.log(context, Const.LOG_INFO, "ADB: " + state);
            }
            lastState = state;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "ADB check failed: " + e.getMessage());
        }
    }

    private static int getGlobalInt(ContentResolver resolver, String name) {
        return Settings.Global.getInt(resolver, name, 0);
    }

    private static boolean putGlobalInt(ContentResolver resolver, String name, int value) {
        try {
            return Settings.Global.putInt(resolver, name, value);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "ADB: cannot write " + name + ": " + e.getMessage());
            return false;
        }
    }

    // adbd listens on all interfaces when network ADB is on, so a local connect tells us
    private static boolean isPortListening(int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Exception e) {
                // Ignore
            }
        }
    }
}
