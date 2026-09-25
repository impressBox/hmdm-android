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
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManager;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.pro.service.CheckForegroundAppAccessibilityService;
import com.hmdm.launcher.util.LegacyUtils;
import com.hmdm.launcher.util.RemoteLogger;
import com.hmdm.launcher.util.Utils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Device preparation for impressBox signage panels, done by the launcher itself as device owner.
 *
 * This replaces most of the adb commands the impressBox Writer used to run after
 * "dpm set-device-owner". The Writer now only installs the APK, sets the device owner,
 * grants the three app ops Android does not let a device owner grant to itself
 * (overlay, usage access, modify system settings) plus vendor shell-only keys in one call,
 * and starts the launcher with provisioning extras:
 *
 *   am start -n com.hmdm.launcher/.ui.MainActivity \
 *       --ez com.impressbox.LOCK_ROTATION true
 *
 * The device ID is the hardware serial number, read by the launcher itself and passed on to the player.
 *
 * Everything here is idempotent and is re-applied on each start and each configuration update,
 * so a setting changed on the device (or by the server) is put back.
 */
public class DeviceSetup {

    // Intent extras accepted by MainActivity (see consumeProvisioningExtras)
    public static final String EXTRA_DEVICE_ID = Const.QR_DEVICE_ID_ATTR;           // "com.hmdm.DEVICE_ID"
    public static final String EXTRA_LOCK_ROTATION = "com.impressbox.LOCK_ROTATION";

    // Launcher app setting (server: Applications > launcher settings): extra packages to hide, comma separated
    private static final String APP_SETTING_HIDE_PACKAGES = "hide_packages";

    // Vendor players / launchers which compete with our launcher or the impress player
    private static final String[] DEFAULT_HIDDEN_PACKAGES = {
            "com.xbh.universal.player",
            "com.google.android.tvlauncher"
    };

    // DEVICE_ID_CHOICE of the impressbox flavor: hardware serial number, as Android returns it
    public static final String CHOICE_SERIAL = "impressbox_serial";

    // The impress player and the managed configuration key it reads the serial number from
    private static final String PLAYER_PACKAGE = "com.impressplayer";
    private static final String PLAYER_KEY_SERIAL = "serial_number";

    private static final String IMMERSIVE_POLICY = "immersive.full=com.impressplayer";
    // BatteryManager.BATTERY_PLUGGED_AC | BATTERY_PLUGGED_USB, as the Writer used
    private static final String STAY_ON_PLUGGED = "3";

    private static final String PREFS = "impressbox_setup";
    private static final String PREF_LOCK_ROTATION = "lock_rotation";

    private DeviceSetup() {}

    /**
     * Reads the provisioning extras the Writer passes with "am start".
     * The device ID is only accepted while none is set, so an enrolled device keeps its identity.
     */
    public static void consumeProvisioningExtras(Context context, Intent intent) {
        if (intent == null || intent.getExtras() == null) {
            return;
        }
        SettingsHelper settingsHelper = SettingsHelper.getInstance(context.getApplicationContext());
        String deviceId = intent.getStringExtra(EXTRA_DEVICE_ID);
        if (isAutoDeviceId()) {
            // The launcher reads the device ID itself (serial number); an ID passed by the Writer is ignored
            deviceId = null;
        }
        if (!TextUtils.isEmpty(deviceId) && TextUtils.isEmpty(settingsHelper.getDeviceId())) {
            settingsHelper.setDeviceId(deviceId.trim());
            Log.i(Const.LOG_TAG, "Provisioning: device ID set from launch intent: " + deviceId);
        }
        if (intent.hasExtra(EXTRA_LOCK_ROTATION)) {
            prefs(context).edit()
                    .putBoolean(PREF_LOCK_ROTATION, intent.getBooleanExtra(EXTRA_LOCK_ROTATION, false))
                    .apply();
        }
    }

    /** True when the build reads the device ID itself and never takes it from outside (impressbox flavor). */
    public static boolean isAutoDeviceId() {
        return CHOICE_SERIAL.equals(BuildConfig.DEVICE_ID_CHOICE) || "android_id".equals(BuildConfig.DEVICE_ID_CHOICE);
    }

    /**
     * The launcher's ANDROID_ID, exactly as Android returns it. Since Android 8 every signing key gets its
     * own ANDROID_ID, so it differs from the player's unless both are signed with the same key.
     */
    public static String getAndroidId(Context context) {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        return TextUtils.isEmpty(androidId) ? null : androidId;
    }

    /**
     * The hardware serial number exactly as Android returns it (Build.getSerial(), readable by the device owner),
     * or ro.serialno. Null when not available, e.g. before the launcher is the device owner.
     */
    public static String getSerialNumber() {
        String serial = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                serial = Build.getSerial();
            }
        } catch (SecurityException e) {
            // Not device owner yet
        }
        if (TextUtils.isEmpty(serial) || Build.UNKNOWN.equals(serial)) {
            serial = readSystemProperty("ro.serialno");
        }
        return TextUtils.isEmpty(serial) || Build.UNKNOWN.equals(serial) ? null : serial.trim();
    }

    /**
     * Device ID used when nothing else provides one. With DEVICE_ID_CHOICE=android_id: the ANDROID_ID.
     * Otherwise: the hardware serial number as is, or the ANDROID_ID if there is no serial.
     */
    public static String getDefaultDeviceId(Context context) {
        if ("android_id".equals(BuildConfig.DEVICE_ID_CHOICE)) {
            return getAndroidId(context);
        }
        String serial = getSerialNumber();
        return serial != null ? serial : getAndroidId(context);
    }

    /**
     * The impress player cannot read the serial number itself (Android 10+ only lets the device owner do it),
     * so the launcher hands it over as a managed configuration value of the player ("serial_number").
     * The player reports it to the server instead of its ANDROID_ID, so both apps use the same ID.
     * Merged into the player's existing restrictions (app settings from the server are kept); may be set
     * before the player is installed. Re-applied on each start and each configuration update.
     */
    public static void publishSerialToPlayer(Context context) {
        if (!Utils.isDeviceOwner(context)) {
            return;
        }
        String serial = getSerialNumber();
        if (serial == null) {
            return;
        }
        try {
            DevicePolicyManager dpm = dpm(context);
            ComponentName admin = LegacyUtils.getAdminComponentName(context);
            Bundle restrictions = dpm.getApplicationRestrictions(admin, PLAYER_PACKAGE);
            if (restrictions == null) {
                restrictions = new Bundle();
            }
            if (serial.equals(restrictions.getString(PLAYER_KEY_SERIAL))) {
                return;
            }
            restrictions.putString(PLAYER_KEY_SERIAL, serial);
            dpm.setApplicationRestrictions(admin, PLAYER_PACKAGE, restrictions);
            Log.i(Const.LOG_TAG, "Setup: serial number passed to " + PLAYER_PACKAGE);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Setup: cannot pass the serial number to the player: " + e.getMessage());
        }
    }

    /** Turns the screen on and shows the launcher over the lock screen. */
    public static void wakeUp(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setTurnScreenOn(true);
                activity.setShowWhenLocked(true);
            } else {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            }
            KeyguardManager km = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked() && !km.isKeyguardSecure()) {
                km.requestDismissKeyguard(activity, null);
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Setup: cannot wake up the screen: " + e.getMessage());
        }
    }

    /** Applies all device settings. Safe to call often; does nothing unless we are the device owner. */
    public static void apply(Context context) {
        if (!Utils.isDeviceOwner(context)) {
            return;
        }
        if (BuildConfig.FORCE_SCREEN_ALWAYS_ON) {
            enforceScreenAlwaysOn(context);
        }
        hideVendorPackages(context);
        disableLockScreen(context);
        applyImmersiveMode(context);
        applyRotationLock(context);
        enableAccessibilityService(context);
        publishSerialToPlayer(context);
    }

    /**
     * The screen must never turn off on signage devices, whatever the server configuration says.
     * The user cannot change the timeout either.
     */
    public static void enforceScreenAlwaysOn(Context context) {
        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = LegacyUtils.getAdminComponentName(context);
        if (dpm == null || !Utils.isDeviceOwner(context)) {
            return;
        }
        try {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Setup: cannot lock the screen timeout setting: " + e.getMessage());
        }
        String never = Integer.toString(Integer.MAX_VALUE);
        boolean done = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dpm.setSystemSetting(admin, Settings.System.SCREEN_OFF_TIMEOUT, never);
                done = true;
            } catch (Exception e) {
                Log.w(Const.LOG_TAG, "Setup: setSystemSetting(screen_off_timeout) failed: " + e.getMessage());
            }
        }
        if (!done && Settings.System.canWrite(context)) {
            try {
                Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, Integer.MAX_VALUE);
            } catch (Exception e) {
                Log.w(Const.LOG_TAG, "Setup: cannot write screen_off_timeout: " + e.getMessage());
            }
        }
        try {
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, STAY_ON_PLUGGED);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Setup: cannot set stay_on_while_plugged_in: " + e.getMessage());
        }
        try {
            // No maximum time to lock
            dpm.setMaximumTimeToLock(admin, 0);
        } catch (Exception e) {
            // Ignore
        }
    }

    // Signage panels have no user to unlock them: disable the (insecure) lock screen.
    // Has no effect when a PIN / password is set.
    private static void disableLockScreen(Context context) {
        try {
            dpm(context).setKeyguardDisabled(LegacyUtils.getAdminComponentName(context), true);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Setup: cannot disable the lock screen: " + e.getMessage());
        }
    }

    private static void hideVendorPackages(Context context) {
        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = LegacyUtils.getAdminComponentName(context);
        Set<String> packages = new LinkedHashSet<>(Arrays.asList(DEFAULT_HIDDEN_PACKAGES));
        String extra = SettingsHelper.getInstance(context).getAppPreference(context.getPackageName(), APP_SETTING_HIDE_PACKAGES);
        if (extra != null) {
            for (String p : extra.split(",")) {
                if (!p.trim().isEmpty()) {
                    packages.add(p.trim());
                }
            }
        }
        for (String pkg : packages) {
            if (pkg.equals(context.getPackageName()) || !Utils.isPackageInstalled(context, pkg)) {
                continue;
            }
            try {
                if (!dpm.isApplicationHidden(admin, pkg)) {
                    dpm.setApplicationHidden(admin, pkg, true);
                    RemoteLogger.log(context, Const.LOG_INFO, "Setup: hidden " + pkg);
                }
            } catch (Exception e) {
                Log.w(Const.LOG_TAG, "Setup: cannot hide " + pkg + ": " + e.getMessage());
            }
        }
    }

    // Only when WRITE_SECURE_SETTINGS was granted ("pm grant"); the Writer does not grant it and sets
    // these two keys itself (policy_control only exists up to Android 10 anyway)
    private static void applyImmersiveMode(Context context) {
        if (!hasWriteSecureSettings(context)) {
            return;
        }
        try {
            Settings.Global.putString(context.getContentResolver(), "policy_control", IMMERSIVE_POLICY);
            Settings.Secure.putString(context.getContentResolver(), "immersive_mode_confirmations", "confirmed");
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Setup: cannot apply immersive mode: " + e.getMessage());
        }
    }

    // Requires "Modify system settings" (WRITE_SETTINGS app op), granted once by the Writer
    private static void applyRotationLock(Context context) {
        if (!prefs(context).getBoolean(PREF_LOCK_ROTATION, false) || !Settings.System.canWrite(context)) {
            return;
        }
        try {
            Settings.System.putInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
            Settings.System.putInt(context.getContentResolver(), Settings.System.USER_ROTATION, 0);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Setup: cannot lock rotation: " + e.getMessage());
        }
    }

    // Turns our app-control accessibility service on without user interaction (WRITE_SECURE_SETTINGS)
    private static void enableAccessibilityService(Context context) {
        if (!BuildConfig.USE_ACCESSIBILITY || !hasWriteSecureSettings(context)) {
            return;
        }
        try {
            String ours = new ComponentName(context, CheckForegroundAppAccessibilityService.class).flattenToString();
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || !Arrays.asList(enabled.split(":")).contains(ours)) {
                String value = TextUtils.isEmpty(enabled) ? ours : enabled + ":" + ours;
                Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value);
            }
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Setup: cannot enable the accessibility service: " + e.getMessage());
        }
    }

    private static boolean hasWriteSecureSettings(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    private static String readSystemProperty(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            return (String) c.getMethod("get", String.class).invoke(null, key);
        } catch (Exception e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static DevicePolicyManager dpm(Context context) {
        return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }
}
