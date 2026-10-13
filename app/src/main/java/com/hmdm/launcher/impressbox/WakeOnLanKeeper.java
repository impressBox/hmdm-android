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
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.util.LegacyUtils;
import com.hmdm.launcher.util.RemoteLogger;
import com.hmdm.launcher.util.Utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps Wake-on-LAN switched on, on every kind of panel, at start, on each configuration update and every
 * CHECK_INTERVAL_MS.
 *
 * Android has no standard Wake-on-LAN switch: every vendor (XBH / RK3568 boards, "hsetting" panels,
 * NovaStar, Amlogic / Android TV boxes...) keeps it under its own name, in Settings (global / secure / system),
 * in a system property, or in the kernel (sysfs). So the launcher does not rely on a fixed list; it looks:
 *
 * 1. Settings: reads every key of Settings.Global, Settings.Secure and Settings.System and picks the ones whose
 *    name looks like Wake-on-LAN (wol, wake_on_lan, wakeonlan, lan_wake, eth_wake, network_standby...).
 *    A key that is off ("0", "false", "off", "disable[d]") is switched on ("1", "true", "on", "enable[d]").
 *    Global keys: DevicePolicyManager.setGlobalSetting, else Settings.Global (WRITE_SECURE_SETTINGS, granted by
 *    the Writer); secure keys: Settings.Secure (WRITE_SECURE_SETTINGS); system keys: Settings.System
 *    (WRITE_SETTINGS app op, granted by the Writer).
 * 2. System properties: the ones whose name looks like Wake-on-LAN are read and reported. An app cannot set
 *    persist.* properties (not even the device owner); the launcher tries, and reports when it is not allowed.
 * 3. Kernel: Wake-on-LAN files under /sys/class/net/<eth*>/ (and the device's power/wakeup) are read and,
 *    if writable, switched on. Usually root-only: reported.
 * 4. Keys set from the server: launcher app setting "wol_settings", for panels whose key has another name or does
 *    not exist until it is set once, e.g. "system:xbh_wol_enable=1;global:wake_on_lan=1". These are written as
 *    given, even when the key does not exist yet.
 *
 * What was found on the device, and what was changed or could not be changed, is sent to the server log
 * ("WoL: ...") whenever it changes, so the setting used by each model can be read from the MDM.
 * The launcher app setting "wol_enabled" = "false" turns the whole thing off for a configuration.
 */
public class WakeOnLanKeeper {

    private static final long CHECK_INTERVAL_MS = 30 * 60 * 1000;

    // Launcher app settings (server: Applications > launcher settings)
    public static final String SETTING_ENABLED = "wol_enabled";
    public static final String SETTING_EXTRA_KEYS = "wol_settings";

    // Names of Wake-on-LAN switches seen on Android firmwares. Matched against Settings keys, property names
    // and sysfs file names (case insensitive). "wol" only as a whole word, so e.g. "evolution" does not match;
    // "wol" is matched case-sensitively so camelCase names (wolEnable, xbhWol) match too.
    private static final Pattern WOL_NAME = Pattern.compile(
            "(?-i:(^|[^a-z])(wol|WOL)([^a-z]|$)|Wol([^a-z]|$))"
            + "|wake[_.\\- ]?on[_.\\- ]?(lan|wlan|eth|ethernet|network|net)"
            + "|wakeonlan|lan[_.\\- ]?wake|eth(ernet)?[_.\\- ]?wake|wake[_.\\- ]?(up[_.\\- ]?)?by[_.\\- ]?(lan|eth|net)"
            + "|network[_.\\- ]?standby|net[_.\\- ]?standby|magic[_.\\- ]?packet",
            Pattern.CASE_INSENSITIVE);

    private static final String NS_GLOBAL = "global";
    private static final String NS_SECURE = "secure";
    private static final String NS_SYSTEM = "system";

    private static Handler handler;
    private static String lastReport;

    private WakeOnLanKeeper() {}

    public static boolean isEnabledInBuild() {
        return BuildConfig.KEEP_WAKE_ON_LAN;
    }

    static boolean looksLikeWakeOnLan(String name) {
        return name != null && WOL_NAME.matcher(name).find();
    }

    /** Checks now and then every CHECK_INTERVAL_MS, on a background thread. Safe to call more than once. */
    public static synchronized void start(Context context) {
        if (!isEnabledInBuild() || handler != null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        HandlerThread thread = new HandlerThread("impressbox-wol");
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

    /** Runs the check on the background thread now (e.g. after a configuration update). */
    public static synchronized void requestCheck(Context context) {
        if (!isEnabledInBuild()) {
            return;
        }
        if (handler == null) {
            start(context);
            return;
        }
        final Context appContext = context.getApplicationContext();
        handler.post(new Runnable() {
            @Override
            public void run() {
                ensure(appContext);
            }
        });
    }

    /** Finds the Wake-on-LAN switches of this device and turns them on. Blocking: not on the main thread. */
    public static void ensure(Context context) {
        if (!Utils.isDeviceOwner(context)) {
            return;
        }
        try {
            SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
            String enabled = settingsHelper.getAppPreference(context.getPackageName(), SETTING_ENABLED);
            if (enabled != null && ("false".equalsIgnoreCase(enabled.trim()) || "0".equals(enabled.trim()))) {
                return;
            }

            List<String> found = new ArrayList<>();
            List<String> changed = new ArrayList<>();
            List<String> failed = new ArrayList<>();

            // 1. Settings with a Wake-on-LAN name
            for (String ns : new String[] {NS_GLOBAL, NS_SECURE, NS_SYSTEM}) {
                for (Map.Entry<String, String> e : listSettings(context, ns).entrySet()) {
                    if (!looksLikeWakeOnLan(e.getKey())) {
                        continue;
                    }
                    String id = ns + ":" + e.getKey();
                    String value = e.getValue();
                    String on = onValue(value);
                    if (on == null) {
                        found.add(id + "=" + value);
                        continue;
                    }
                    if (writeSetting(context, ns, e.getKey(), on)) {
                        changed.add(id + " " + value + "->" + on);
                        found.add(id + "=" + on);
                    } else {
                        failed.add(id + "=" + value);
                        found.add(id + "=" + value);
                    }
                }
            }

            // 2. Keys given by the server, written as they are
            String extra = settingsHelper.getAppPreference(context.getPackageName(), SETTING_EXTRA_KEYS);
            for (String[] kv : parseExtraKeys(extra)) {
                String ns = kv[0], key = kv[1], wanted = kv[2];
                String id = ns + ":" + key;
                String current = readSetting(context, ns, key);
                if (wanted.equals(current)) {
                    if (!containsKey(found, id)) {
                        found.add(id + "=" + current);
                    }
                    continue;
                }
                if (writeSetting(context, ns, key, wanted)) {
                    changed.add(id + " " + current + "->" + wanted);
                } else {
                    failed.add(id + "=" + current);
                }
                if (!containsKey(found, id)) {
                    found.add(id + "=" + readSetting(context, ns, key));
                }
            }

            // 3. System properties
            for (Map.Entry<String, String> e : listWolProperties().entrySet()) {
                String id = "prop:" + e.getKey();
                String on = onValue(e.getValue());
                if (on != null && !e.getKey().startsWith("ro.")) {
                    if (setSystemProperty(e.getKey(), on) && on.equals(readSystemProperty(e.getKey()))) {
                        changed.add(id + " " + e.getValue() + "->" + on);
                        found.add(id + "=" + on);
                        continue;
                    }
                    failed.add(id + "=" + e.getValue() + " (system property, only the system can set it)");
                }
                found.add(id + "=" + e.getValue());
            }

            // 4. Kernel (ethernet interfaces)
            checkSysfs(found, changed, failed);

            StringBuilder report = new StringBuilder();
            if (found.isEmpty()) {
                report.append("no Wake-on-LAN setting found on this device (")
                        .append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                        .append(", ").append(Build.HARDWARE).append("); set the vendor key in the launcher setting ")
                        .append(SETTING_EXTRA_KEYS);
            } else {
                report.append(TextUtils.join(", ", found));
                if (!failed.isEmpty()) {
                    report.append("; could not turn on: ").append(TextUtils.join(", ", failed));
                }
            }
            String state = report.toString();
            if (!changed.isEmpty()) {
                RemoteLogger.log(context, Const.LOG_INFO, "WoL: turned on " + TextUtils.join(", ", changed) + "; now " + state);
            } else if (!state.equals(lastReport)) {
                RemoteLogger.log(context, failed.isEmpty() ? Const.LOG_INFO : Const.LOG_WARN, "WoL: " + state);
            }
            lastReport = state;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "WoL check failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Settings

    private static Uri tableUri(String ns) {
        switch (ns) {
            case NS_GLOBAL: return Settings.Global.CONTENT_URI;
            case NS_SECURE: return Settings.Secure.CONTENT_URI;
            default: return Settings.System.CONTENT_URI;
        }
    }

    /** All keys of a Settings table (name -> value). Empty if the firmware does not let us list it. */
    private static Map<String, String> listSettings(Context context, String ns) {
        Map<String, String> result = new TreeMap<>();
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(tableUri(ns),
                    new String[] {Settings.NameValueTable.NAME, Settings.NameValueTable.VALUE}, null, null, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(0);
                    if (name != null) {
                        result.put(name, cursor.getString(1));
                    }
                }
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "WoL: cannot list " + ns + " settings: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    private static String readSetting(Context context, String ns, String key) {
        ContentResolver resolver = context.getContentResolver();
        try {
            switch (ns) {
                case NS_GLOBAL: return Settings.Global.getString(resolver, key);
                case NS_SECURE: return Settings.Secure.getString(resolver, key);
                default: return Settings.System.getString(resolver, key);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean writeSetting(Context context, String ns, String key, String value) {
        ContentResolver resolver = context.getContentResolver();
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = LegacyUtils.getAdminComponentName(context);
        boolean secureSettings = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        try {
            switch (ns) {
                case NS_GLOBAL:
                    // Only a few global keys are allowed for the device owner; the others need WRITE_SECURE_SETTINGS
                    try {
                        dpm.setGlobalSetting(admin, key, value);
                    } catch (Exception e) {
                        if (secureSettings) {
                            Settings.Global.putString(resolver, key, value);
                        }
                    }
                    break;
                case NS_SECURE:
                    try {
                        dpm.setSecureSetting(admin, key, value);
                    } catch (Exception e) {
                        if (secureSettings) {
                            Settings.Secure.putString(resolver, key, value);
                        }
                    }
                    break;
                default:
                    boolean done = false;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try {
                            dpm.setSystemSetting(admin, key, value);
                            done = true;
                        } catch (Exception e) {
                            // Only screen settings are allowed for the device owner
                        }
                    }
                    if (!done && (Settings.System.canWrite(context) || secureSettings)) {
                        Settings.System.putString(resolver, key, value);
                    }
                    break;
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "WoL: cannot write " + ns + ":" + key + ": " + e.getMessage());
        }
        return value.equals(readSetting(context, ns, key));
    }

    /**
     * "system:xbh_wol_enable=1; global:wake_on_lan = 1" -> {ns, key, value}. Namespace defaults to system.
     * Separators: ";", "," or new lines.
     */
    static List<String[]> parseExtraKeys(String value) {
        List<String[]> result = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) {
            return result;
        }
        for (String item : value.split("[;,\\n]")) {
            item = item.trim();
            int eq = item.indexOf('=');
            if (eq <= 0 || eq == item.length() - 1) {
                continue;
            }
            String name = item.substring(0, eq).trim();
            String v = item.substring(eq + 1).trim();
            String ns = NS_SYSTEM;
            int colon = name.indexOf(':');
            if (colon > 0) {
                ns = name.substring(0, colon).trim().toLowerCase(Locale.US);
                name = name.substring(colon + 1).trim();
            }
            if (!NS_GLOBAL.equals(ns) && !NS_SECURE.equals(ns) && !NS_SYSTEM.equals(ns) || name.isEmpty()) {
                continue;
            }
            result.add(new String[] {ns, name, v});
        }
        return result;
    }

    private static boolean containsKey(List<String> found, String id) {
        for (String s : found) {
            if (s.startsWith(id + "=")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The "on" value matching the format of an "off" value, or null when the value is not "off"
     * (already on, or a value we do not understand, e.g. a mode name).
     */
    static String onValue(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        switch (v.toLowerCase(Locale.US)) {
            case "0": return "1";
            case "false": return v.equals("FALSE") ? "TRUE" : v.equals("False") ? "True" : "true";
            case "off": return v.equals("OFF") ? "ON" : v.equals("Off") ? "On" : "on";
            case "disable": return v.equals("DISABLE") ? "ENABLE" : "enable";
            case "disabled": return v.equals("DISABLED") ? "ENABLED" : "enabled";
            case "no": return "yes";
            default: return null;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // System properties

    private static final Pattern GETPROP_LINE = Pattern.compile("^\\[([^\\]]+)\\]: \\[([^\\]]*)\\]$");

    /** System properties whose name looks like Wake-on-LAN, from "getprop" (apps may run it). */
    private static Map<String, String> listWolProperties() {
        Map<String, String> result = new LinkedHashMap<>();
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[] {"getprop"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = GETPROP_LINE.matcher(line.trim());
                if (m.matches() && looksLikeWakeOnLan(m.group(1))) {
                    result.put(m.group(1), m.group(2));
                }
            }
            reader.close();
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "WoL: cannot list system properties: " + e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return result;
    }

    private static boolean setSystemProperty(String key, String value) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            c.getMethod("set", String.class, String.class).invoke(null, key, value);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static String readSystemProperty(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            return (String) c.getMethod("get", String.class).invoke(null, key);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Kernel

    /**
     * Ethernet interfaces: vendor kernels may have a Wake-on-LAN file next to the interface or its device,
     * and the device's power/wakeup must be "enabled" for the magic packet to wake the board.
     */
    private static void checkSysfs(List<String> found, List<String> changed, List<String> failed) {
        File[] interfaces = new File("/sys/class/net").listFiles();
        if (interfaces == null) {
            return;
        }
        for (File iface : interfaces) {
            String name = iface.getName();
            if (!(name.startsWith("eth") || name.startsWith("en"))) {
                continue;
            }
            for (File dir : new File[] {iface, new File(iface, "device")}) {
                File[] files = dir.listFiles();
                if (files == null) {
                    continue;
                }
                for (File f : files) {
                    if (f.isFile() && looksLikeWakeOnLan(f.getName())) {
                        checkSysfsFile(f, found, changed, failed);
                    }
                }
            }
            File wakeup = new File(iface, "device/power/wakeup");
            if (wakeup.isFile()) {
                checkSysfsFile(wakeup, found, changed, failed);
            }
        }
    }

    private static void checkSysfsFile(File f, List<String> found, List<String> changed, List<String> failed) {
        String path = f.getAbsolutePath();
        String value = readFile(f);
        if (value == null) {
            return;
        }
        String on = onValue(value);
        if (on != null) {
            if (writeFile(f, on) && on.equals(readFile(f))) {
                changed.add(path + " " + value + "->" + on);
                found.add(path + "=" + on);
                return;
            }
            failed.add(path + "=" + value + " (kernel, needs root)");
        }
        found.add(path + "=" + value);
    }

    private static String readFile(File f) {
        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            String line = reader.readLine();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean writeFile(File f, String value) {
        if (!f.canWrite()) {
            return false;
        }
        try (FileWriter writer = new FileWriter(f)) {
            writer.write(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
