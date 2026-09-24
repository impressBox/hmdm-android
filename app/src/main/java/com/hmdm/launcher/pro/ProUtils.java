/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 * Copyright (C) 2026 impressBox (independent implementation of the extended features)
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
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.location.Location;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.pro.service.CheckForegroundAppAccessibilityService;
import com.hmdm.launcher.ui.custom.BlockingBar;
import com.hmdm.launcher.util.Utils;

import java.util.Calendar;

/**
 * Entry points for the extended (formerly "Pro") features.
 *
 * This is an independent implementation written against the public stub API of the
 * open-source launcher and the Android SDK. Kiosk logic lives in {@link KioskController},
 * app access control in {@link AppAccessPolicy}, location history in {@link LocationUploader}.
 */
public class ProUtils {

    private static final String BRANDING_PREFS = "impressbox_branding";
    private static final String BRANDING_APP_NAME = "app_name";
    private static final String BRANDING_VENDOR = "vendor";

    public static boolean isPro() {
        return true;
    }

    public static boolean kioskModeRequired(Context context) {
        ServerConfig config = SettingsHelper.getInstance(context).getConfig();
        return config != null && config.isKioskMode();
    }

    public static void initCrashlytics(Context context) {
        // Crash reporting is intentionally not bundled (no third-party Firebase project)
    }

    public static void sendExceptionToCrashlytics(Throwable e) {
        Log.w(Const.LOG_TAG, "Unhandled exception", e);
    }

    // Returns true if our accessibility service is enabled by the user
    public static boolean checkAccessibilityService(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        ComponentName ours = new ComponentName(context, CheckForegroundAppAccessibilityService.class);
        for (String item : enabled.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(item.trim());
            if (ours.equals(cn)) {
                return true;
            }
        }
        return false;
    }

    // Returns true if the app has been granted the "Usage access" special permission
    public static boolean checkUsageStatistics(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) {
            return false;
        }
        int mode;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), context.getPackageName());
            } else {
                mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), context.getPackageName());
            }
        } catch (Exception e) {
            return false;
        }
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    // Adds a transparent touch-consuming view over the status bar.
    // Returns the added view (so the caller can remove it later) or null on failure.
    public static View preventStatusBarExpansion(Activity activity) {
        int height = 0;
        int resId = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            height = activity.getResources().getDimensionPixelSize(resId);
        }
        if (height <= 0) {
            height = (int) (24 * activity.getResources().getDisplayMetrics().density);
        }
        WindowManager.LayoutParams lp = createBlockingLayoutParams(WindowManager.LayoutParams.MATCH_PARENT, height);
        lp.gravity = Gravity.TOP | Gravity.START;
        return addBlockingView(activity, lp);
    }

    // Adds a narrow transparent view on the right edge (blocks the edge panel / app list swipe)
    public static View preventApplicationsList(Activity activity) {
        int width = activity.getResources().getDimensionPixelOffset(R.dimen.prevent_applications_list_width);
        WindowManager.LayoutParams lp = createBlockingLayoutParams(width, WindowManager.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.END | Gravity.TOP;
        return addBlockingView(activity, lp);
    }

    // Small, nearly invisible button in the top left corner used to exit kiosk mode.
    // The click handling (counting clicks, asking for the password) is done by MainActivity.
    public static View createKioskUnlockButton(Activity activity) {
        if (!Utils.canDrawOverlays(activity)) {
            return null;
        }
        int size = activity.getResources().getDimensionPixelOffset(R.dimen.exit_overlay_size);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
                Utils.OverlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        ImageView button = new ImageView(activity);
        button.setImageResource(R.drawable.ic_vpn_key_transparent_24dp);
        try {
            WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            wm.addView(button, lp);
            return button;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to add the kiosk unlock button: " + e.getMessage());
            return null;
        }
    }

    public static boolean isKioskAppInstalled(Context context) {
        return KioskController.isKioskAppInstalled(context);
    }

    public static boolean isKioskModeRunning(Context context) {
        return KioskController.isRunning(context);
    }

    public static Intent getKioskAppIntent(String kioskApp, Activity activity) {
        return KioskController.getLaunchIntent(activity, kioskApp);
    }

    // Start COSU kiosk mode
    public static boolean startCosuKioskMode(String kioskApp, Activity activity, boolean enableSettings) {
        return KioskController.start(activity, kioskApp, enableSettings);
    }

    // Set/update kiosk mode options (lock task features)
    public static void updateKioskOptions(Activity activity) {
        KioskController.applyFeatures(activity);
    }

    // Update app list in the kiosk mode
    public static void updateKioskAllowedApps(String kioskApp, Activity activity, boolean enableSettings) {
        KioskController.applyAllowedPackages(activity, kioskApp, enableSettings);
    }

    public static void unlockKiosk(Activity activity) {
        KioskController.stop(activity);
    }

    public static void processConfig(Context context, ServerConfig config) {
        SharedPreferences.Editor editor = brandingPrefs(context).edit();
        putOrRemove(editor, BRANDING_APP_NAME, config.getAppName());
        putOrRemove(editor, BRANDING_VENDOR, config.getVendor());
        editor.apply();
    }

    public static void processLocation(Context context, Location location, String provider) {
        LocationUploader.getInstance().onLocation(context, location, provider);
    }

    public static String getAppName(Context context) {
        return brandingPrefs(context).getString(BRANDING_APP_NAME, context.getString(R.string.app_name));
    }

    public static String getCopyright(Context context) {
        String vendor = brandingPrefs(context).getString(BRANDING_VENDOR, context.getString(R.string.vendor));
        return "(c) " + Calendar.getInstance().get(Calendar.YEAR) + " " + vendor;
    }

    private static SharedPreferences brandingPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(BRANDING_PREFS, Context.MODE_PRIVATE);
    }

    private static void putOrRemove(SharedPreferences.Editor editor, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            editor.putString(key, value.trim());
        } else {
            editor.remove(key);
        }
    }

    private static WindowManager.LayoutParams createBlockingLayoutParams(int width, int height) {
        return new WindowManager.LayoutParams(width, height,
                Utils.OverlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSPARENT);
    }

    private static View addBlockingView(Activity activity, WindowManager.LayoutParams lp) {
        if (!Utils.canDrawOverlays(activity)) {
            Log.w(Const.LOG_TAG, "Cannot block system areas: overlay permission not granted");
            return null;
        }
        View view = new BlockingBar(activity);
        try {
            WindowManager wm = (WindowManager) activity.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
            wm.addView(view, lp);
            return view;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to add blocking view: " + e.getMessage());
            return null;
        }
    }
}
