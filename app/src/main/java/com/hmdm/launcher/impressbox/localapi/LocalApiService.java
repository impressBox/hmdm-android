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

package com.hmdm.launcher.impressbox.localapi;

import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.app.admin.SystemUpdatePolicy;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.util.LegacyUtils;
import com.hmdm.launcher.util.RemoteLogger;
import com.hmdm.launcher.util.Utils;
import com.impressbox.launcher.localapi.ILocalApi;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Local API for the player: device settings only the device owner can change (time zone, automatic time, clock,
 * reboot, OS system update policy), so the player can apply the manage settings on plain Android.
 *
 * Bound with the action LocalApi.ACTION. Only apps signed with a certificate on the allowlist may run commands:
 * BuildConfig.LOCAL_API_CALLER_CERTS (the impressbox flavor: the player) plus, in debug builds,
 * BuildConfig.LOCAL_API_EXTRA_CALLER_CERTS (-PlocalApiDebugCallerCerts). Every command that changes something is
 * written to the server log. The contract shared with the player is docs/launcher-local-api.json.
 */
public class LocalApiService extends Service {

    private static final String TAG = Const.LOG_TAG;
    // The reboot answers first, so the caller gets its result and can write its log
    private static final long REBOOT_DELAY_MS = 2000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private CallerCheck callerCheck;

    @Override
    public void onCreate() {
        super.onCreate();
        callerCheck = new CallerCheck(this, LocalApiRules.parseCertDigests(
                BuildConfig.LOCAL_API_CALLER_CERTS, BuildConfig.LOCAL_API_EXTRA_CALLER_CERTS));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private final ILocalApi.Stub binder = new ILocalApi.Stub() {

        @Override
        public int getApiVersion() {
            return LocalApi.API_VERSION;
        }

        @Override
        public List<String> getCapabilities() {
            long identity = Binder.clearCallingIdentity();
            try {
                return LocalApiRules.capabilities(Build.VERSION.SDK_INT, Utils.isDeviceOwner(LocalApiService.this));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public Bundle run(String command, Bundle args) {
            // The caller's uid is only known here, before the calling identity is cleared
            int uid = Binder.getCallingUid();
            long identity = Binder.clearCallingIdentity();
            try {
                String caller = callerCheck.describe(uid);
                if (!callerCheck.isAllowed(uid)) {
                    RemoteLogger.log(LocalApiService.this, Const.LOG_WARN, "Local API: refused " + command
                            + " from " + caller + ": its signing certificate is not allowed");
                    return result(LocalApi.CALLER_NOT_ALLOWED, "the caller's signing certificate is not allowed");
                }
                return runAllowed(command, args != null ? args : new Bundle(), caller);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    };

    private Bundle runAllowed(String command, Bundle args, String caller) {
        int refusal = LocalApiRules.refusal(command, Build.VERSION.SDK_INT, Utils.isDeviceOwner(this));
        Bundle result;
        if (refusal != LocalApi.OK) {
            result = result(refusal, refusalMessage(refusal, command));
        } else {
            try {
                result = execute(command, args);
            } catch (SecurityException e) {
                result = result(LocalApi.REFUSED, "the system refused: " + e.getMessage());
            } catch (Exception e) {
                result = result(LocalApi.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        log(caller, command, args, result);
        return result;
    }

    private Bundle execute(String command, Bundle args) {
        switch (command) {
            case LocalApi.GET_STATUS:
                return getStatus();
            case LocalApi.SET_TIME_ZONE:
                return setTimeZone(args.getString(LocalApi.ARG_ZONE));
            case LocalApi.SET_AUTO_TIME:
                if (!args.containsKey(LocalApi.ARG_ENABLED)) {
                    return result(LocalApi.BAD_ARGUMENT, LocalApi.ARG_ENABLED + " is missing");
                }
                return setAutoTime(args.getBoolean(LocalApi.ARG_ENABLED));
            case LocalApi.SET_TIME:
                if (!args.containsKey(LocalApi.ARG_EPOCH_MILLIS)) {
                    return result(LocalApi.BAD_ARGUMENT, LocalApi.ARG_EPOCH_MILLIS + " is missing");
                }
                return setTime(args.getLong(LocalApi.ARG_EPOCH_MILLIS));
            case LocalApi.REBOOT:
                return reboot();
            case LocalApi.SET_SYSTEM_UPDATE_POLICY:
                return setSystemUpdatePolicy(args.getString(LocalApi.ARG_TYPE),
                        args.getInt(LocalApi.ARG_WINDOW_START, -1), args.getInt(LocalApi.ARG_WINDOW_END, -1));
            default:
                return result(LocalApi.UNKNOWN_COMMAND, "unknown command " + command);
        }
    }

    private Bundle getStatus() {
        Bundle result = result(LocalApi.OK, "status");
        result.putString(LocalApi.RESULT_ZONE, TimeZone.getDefault().getID());
        result.putBoolean(LocalApi.RESULT_AUTO_TIME_ZONE, isGlobalSettingOn(Settings.Global.AUTO_TIME_ZONE));
        result.putBoolean(LocalApi.RESULT_AUTO_TIME, isGlobalSettingOn(Settings.Global.AUTO_TIME));
        result.putBoolean(LocalApi.RESULT_DEVICE_OWNER, Utils.isDeviceOwner(this));
        String configZone = configTimeZone();
        if (configZone != null) {
            result.putString(LocalApi.RESULT_CONFIG_TIME_ZONE, configZone);
        }
        String localZone = LocalApiZone.get(this);
        if (localZone != null) {
            result.putString(LocalApi.RESULT_LOCAL_API_ZONE, localZone);
        }
        result.putString(LocalApi.RESULT_SYSTEM_UPDATE_POLICY, describe(dpm().getSystemUpdatePolicy()));
        result.putString(LocalApi.RESULT_LAUNCHER_VERSION, BuildConfig.VERSION_NAME);
        return result;
    }

    private Bundle setTimeZone(String zone) {
        if (zone == null || zone.trim().isEmpty()) {
            return result(LocalApi.BAD_ARGUMENT, LocalApi.ARG_ZONE + " is missing");
        }
        zone = zone.trim();
        String configZone = configTimeZone();
        if (LocalApiRules.zoneManagedByConfig(configZone)) {
            return result(LocalApi.MANAGED_BY_CONFIG, "zone is managed by the HMDM configuration (" + configZone + ")");
        }
        String previous = TimeZone.getDefault().getID();
        Bundle result;
        if (LocalApi.ZONE_AUTO.equals(zone)) {
            LocalApiZone.clear(this);
            setAutoTimeZone(true);
            result = result(LocalApi.OK, "automatic time zone on");
        } else {
            if (!LocalApiRules.isKnownZone(zone, Arrays.asList(TimeZone.getAvailableIDs()))) {
                return result(LocalApi.BAD_ARGUMENT, "unknown time zone " + zone);
            }
            // DevicePolicyManager.setTimeZone refuses while automatic time zone is on
            setAutoTimeZone(false);
            if (!dpm().setTimeZone(admin(), zone)) {
                return result(LocalApi.REFUSED, "the system refused the time zone " + zone);
            }
            LocalApiZone.set(this, zone);
            result = result(LocalApi.OK, "time zone " + zone + " (was " + previous + ")");
        }
        // The process default updates when the system announces the change; the system zone is set already
        result.putString(LocalApi.RESULT_ZONE, LocalApi.ZONE_AUTO.equals(zone) ? previous : zone);
        result.putString(LocalApi.RESULT_PREVIOUS_ZONE, previous);
        return result;
    }

    private Bundle setAutoTime(boolean enabled) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dpm().setAutoTimeEnabled(admin(), enabled);
        } else {
            dpm().setGlobalSetting(admin(), Settings.Global.AUTO_TIME, enabled ? "1" : "0");
        }
        return result(LocalApi.OK, "automatic time " + (enabled ? "on" : "off"));
    }

    private Bundle setTime(long epochMillis) {
        if (!LocalApiRules.isPlausibleTime(epochMillis)) {
            return result(LocalApi.BAD_ARGUMENT, "time out of range: " + epochMillis);
        }
        if (isGlobalSettingOn(Settings.Global.AUTO_TIME)) {
            return result(LocalApi.REFUSED, "automatic time is on: turn it off with setAutoTime first");
        }
        if (!dpm().setTime(admin(), epochMillis)) {
            return result(LocalApi.REFUSED, "the system refused the time");
        }
        return result(LocalApi.OK, "clock set to " + utc(epochMillis));
    }

    private Bundle reboot() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!Utils.reboot(LocalApiService.this)) {
                    RemoteLogger.log(LocalApiService.this, Const.LOG_WARN, "Local API: the reboot failed");
                }
            }
        }, REBOOT_DELAY_MS);
        return result(LocalApi.OK, "rebooting in " + (REBOOT_DELAY_MS / 1000) + " s");
    }

    private Bundle setSystemUpdatePolicy(String type, int windowStart, int windowEnd) {
        ServerConfig config = SettingsHelper.getInstance(this).getConfig();
        if (LocalApiRules.updatePolicyManagedByConfig(config != null ? config.getSystemUpdateType() : null)) {
            return result(LocalApi.MANAGED_BY_CONFIG, "system update policy is managed by the HMDM configuration");
        }
        SystemUpdatePolicy policy;
        if (LocalApi.UPDATE_AUTOMATIC.equals(type)) {
            policy = SystemUpdatePolicy.createAutomaticInstallPolicy();
        } else if (LocalApi.UPDATE_WINDOWED.equals(type)) {
            String problem = LocalApiRules.windowProblem(windowStart, windowEnd);
            if (problem != null) {
                return result(LocalApi.BAD_ARGUMENT, problem);
            }
            policy = SystemUpdatePolicy.createWindowedInstallPolicy(windowStart, windowEnd);
        } else if (LocalApi.UPDATE_POSTPONE.equals(type)) {
            policy = SystemUpdatePolicy.createPostponeInstallPolicy();
        } else if (LocalApi.UPDATE_NONE.equals(type)) {
            policy = null;
        } else {
            return result(LocalApi.BAD_ARGUMENT, "type must be automatic, windowed, postpone or none, got " + type);
        }
        dpm().setSystemUpdatePolicy(admin(), policy);
        return result(LocalApi.OK, "system update policy " + describe(policy));
    }

    private void setAutoTimeZone(boolean enabled) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dpm().setAutoTimeZoneEnabled(admin(), enabled);
        } else {
            dpm().setGlobalSetting(admin(), Settings.Global.AUTO_TIME_ZONE, enabled ? "1" : "0");
        }
    }

    private String configTimeZone() {
        ServerConfig config = SettingsHelper.getInstance(this).getConfig();
        return config != null ? config.getTimeZone() : null;
    }

    private boolean isGlobalSettingOn(String name) {
        return Settings.Global.getInt(getContentResolver(), name, 0) == 1;
    }

    private DevicePolicyManager dpm() {
        return (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
    }

    private ComponentName admin() {
        return LegacyUtils.getAdminComponentName(this);
    }

    // Commands that change something go to the server log; getStatus only to logcat
    private void log(String caller, String command, Bundle args, Bundle result) {
        int code = result.getInt(LocalApi.KEY_CODE);
        String line = "Local API: " + caller + " " + command + describeArgs(args) + ": "
                + codeName(code) + ", " + result.getString(LocalApi.KEY_MESSAGE);
        if (LocalApi.GET_STATUS.equals(command) && code == LocalApi.OK) {
            Log.d(TAG, line);
        } else {
            RemoteLogger.log(this, code == LocalApi.OK ? Const.LOG_INFO : Const.LOG_WARN, line);
        }
    }

    private static String describeArgs(Bundle args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" {");
        for (String key : args.keySet()) {
            if (sb.length() > 2) {
                sb.append(", ");
            }
            sb.append(key).append('=').append(args.get(key));
        }
        return sb.append('}').toString();
    }

    private static String refusalMessage(int code, String command) {
        switch (code) {
            case LocalApi.UNKNOWN_COMMAND:
                return "unknown command " + command;
            case LocalApi.NOT_DEVICE_OWNER:
                return "the launcher is not the device owner";
            case LocalApi.UNSUPPORTED:
                return command + " needs Android API " + LocalApiRules.minSdk(command)
                        + ", this device has " + Build.VERSION.SDK_INT;
            default:
                return "refused";
        }
    }

    private static String codeName(int code) {
        switch (code) {
            case LocalApi.OK: return "OK";
            case LocalApi.CALLER_NOT_ALLOWED: return "CALLER_NOT_ALLOWED";
            case LocalApi.UNKNOWN_COMMAND: return "UNKNOWN_COMMAND";
            case LocalApi.BAD_ARGUMENT: return "BAD_ARGUMENT";
            case LocalApi.NOT_DEVICE_OWNER: return "NOT_DEVICE_OWNER";
            case LocalApi.UNSUPPORTED: return "UNSUPPORTED";
            case LocalApi.MANAGED_BY_CONFIG: return "MANAGED_BY_CONFIG";
            case LocalApi.REFUSED: return "REFUSED";
            default: return "FAILED";
        }
    }

    private static String describe(SystemUpdatePolicy policy) {
        if (policy == null) {
            return LocalApi.UPDATE_NONE;
        }
        switch (policy.getPolicyType()) {
            case SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC:
                return LocalApi.UPDATE_AUTOMATIC;
            case SystemUpdatePolicy.TYPE_INSTALL_WINDOWED:
                return LocalApi.UPDATE_WINDOWED + " " + policy.getInstallWindowStart() + "-" + policy.getInstallWindowEnd();
            case SystemUpdatePolicy.TYPE_POSTPONE:
                return LocalApi.UPDATE_POSTPONE;
            default:
                return "type " + policy.getPolicyType();
        }
    }

    private static String utc(long epochMillis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(epochMillis));
    }

    private static Bundle result(int code, String message) {
        Bundle bundle = new Bundle();
        bundle.putInt(LocalApi.KEY_CODE, code);
        bundle.putString(LocalApi.KEY_MESSAGE, message);
        return bundle;
    }
}
