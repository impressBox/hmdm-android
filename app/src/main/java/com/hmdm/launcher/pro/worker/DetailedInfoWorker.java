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

package com.hmdm.launcher.pro.worker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.CellSignalStrength;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.db.DatabaseHelper;
import com.hmdm.launcher.db.InfoHistoryTable;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.DetailedInfo;
import com.hmdm.launcher.json.DetailedInfoConfig;
import com.hmdm.launcher.json.DetailedInfoConfigResponse;
import com.hmdm.launcher.server.ServerService;
import com.hmdm.launcher.server.ServerServiceKeeper;
import com.hmdm.launcher.util.DeviceInfoProvider;
import com.hmdm.launcher.util.RemoteLogger;
import com.hmdm.launcher.util.Utils;

import java.net.Inet4Address;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.ResponseBody;
import retrofit2.Response;

/**
 * Periodically collects a snapshot of the device state (battery, memory, Wi-Fi, GPS, mobile network),
 * stores it locally and uploads the history to the server "deviceinfo" plugin.
 * Collection and upload are enabled and timed by the plugin settings on the server.
 */
public class DetailedInfoWorker extends Worker {

    private static final String WORK_NAME = "com.hmdm.launcher.WORK_DETAILED_INFO";
    private static final int COLLECT_PERIOD_MINS = 15;          // WorkManager minimum
    private static final int UPLOAD_BATCH = 50;
    private static final long GPS_FIX_MAX_AGE_MS = 10 * 60 * 1000L;

    private static final String PREFS = "detailed_info";
    private static final String PREF_CONFIG_LOADED = "config_loaded";
    private static final String PREF_NEED_CONFIG = "need_config";
    private static final String PREF_SEND_DATA = "send_data";
    private static final String PREF_INTERVAL_MINS = "interval_mins";
    private static final String PREF_LAST_UPLOAD = "last_upload";

    private final Context context;

    public static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(DetailedInfoWorker.class,
                COLLECT_PERIOD_MINS, TimeUnit.MINUTES)
                .addTag(Const.WORK_TAG_COMMON)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    // Called when the device configuration is refreshed: reload the plugin settings on the next run
    public static void requestConfigUpdate(Context context) {
        prefs(context).edit().putBoolean(PREF_NEED_CONFIG, true).apply();
    }

    public DetailedInfoWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        this.context = context.getApplicationContext();
    }

    @NonNull
    @Override
    public Result doWork() {
        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        if (settingsHelper.getConfig() == null || settingsHelper.getDeviceId() == null
                || settingsHelper.getDeviceId().isEmpty()) {
            return Result.success();
        }

        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(PREF_CONFIG_LOADED, false) || prefs.getBoolean(PREF_NEED_CONFIG, false)) {
            loadSettings(settingsHelper, prefs);
        }
        if (!prefs.getBoolean(PREF_SEND_DATA, false)) {
            return Result.success();
        }

        DatabaseHelper db = DatabaseHelper.instance(context);
        try {
            InfoHistoryTable.insert(db.getWritableDatabase(), collect());
            InfoHistoryTable.deleteOldItems(db.getWritableDatabase());
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "DetailedInfo: failed to collect: " + e.getMessage());
        }

        long intervalMs = Math.max(1, prefs.getInt(PREF_INTERVAL_MINS, COLLECT_PERIOD_MINS)) * 60000L;
        long now = System.currentTimeMillis();
        // Small tolerance because WorkManager periods are not exact
        if (now - prefs.getLong(PREF_LAST_UPLOAD, 0) + 60000L < intervalMs) {
            return Result.success();
        }
        while (true) {
            List<DetailedInfo> items = InfoHistoryTable.select(db.getReadableDatabase(), UPLOAD_BATCH);
            if (items == null || items.isEmpty()) {
                break;
            }
            if (!upload(settingsHelper, items)) {
                RemoteLogger.log(context, Const.LOG_WARN, "DetailedInfo: upload failed, will retry later");
                return Result.success();
            }
            InfoHistoryTable.delete(db.getWritableDatabase(), items);
            if (items.size() < UPLOAD_BATCH) {
                break;
            }
        }
        prefs.edit().putLong(PREF_LAST_UPLOAD, now).apply();
        return Result.success();
    }

    private void loadSettings(SettingsHelper settingsHelper, SharedPreferences prefs) {
        DetailedInfoConfig config = null;
        for (ServerService service : new ServerService[] {
                ServerServiceKeeper.getServerServiceInstance(context),
                ServerServiceKeeper.getSecondaryServerServiceInstance(context)}) {
            try {
                Response<DetailedInfoConfigResponse> response = service.getDetailedInfoConfig(
                        settingsHelper.getServerProject(), settingsHelper.getDeviceId()).execute();
                if (response.isSuccessful() && response.body() != null
                        && Const.STATUS_OK.equals(response.body().getStatus())) {
                    config = response.body().getData();
                    break;
                }
            } catch (Exception e) {
                // Try the secondary server
            }
        }
        if (config == null) {
            // Plugin not installed or server not reachable: keep previous settings, retry next time
            return;
        }
        prefs.edit()
                .putBoolean(PREF_CONFIG_LOADED, true)
                .putBoolean(PREF_NEED_CONFIG, false)
                .putBoolean(PREF_SEND_DATA, config.getSendData() != null && config.getSendData())
                .putInt(PREF_INTERVAL_MINS, config.getIntervalMins() != null ? config.getIntervalMins() : COLLECT_PERIOD_MINS)
                .apply();
        RemoteLogger.log(context, Const.LOG_DEBUG, "DetailedInfo: settings loaded, send=" + config.getSendData()
                + ", interval=" + config.getIntervalMins());
    }

    private boolean upload(SettingsHelper settingsHelper, List<DetailedInfo> items) {
        for (ServerService service : new ServerService[] {
                ServerServiceKeeper.getServerServiceInstance(context),
                ServerServiceKeeper.getSecondaryServerServiceInstance(context)}) {
            try {
                Response<ResponseBody> response = service.sendDetailedInfo(
                        settingsHelper.getServerProject(), settingsHelper.getDeviceId(), items).execute();
                if (response.isSuccessful()) {
                    return true;
                }
            } catch (Exception e) {
                // Try the secondary server
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Collection

    private DetailedInfo collect() {
        DetailedInfo info = new DetailedInfo();
        info.setTs(System.currentTimeMillis());
        info.setDevice(collectDevice());
        info.setWifi(collectWifi());
        info.setGps(collectGps());
        collectMobile(info);
        return info;
    }

    private DetailedInfo.Device collectDevice() {
        DetailedInfo.Device device = new DetailedInfo.Device();

        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) {
                device.setBatteryLevel(level * 100 / scale);
            }
            int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            if (plugged == BatteryManager.BATTERY_PLUGGED_USB) {
                device.setBatteryCharging(Const.DEVICE_CHARGING_USB);
            } else if (plugged != 0) {
                device.setBatteryCharging(Const.DEVICE_CHARGING_AC);
            } else {
                device.setBatteryCharging("");
            }
        }

        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        device.setWifi(wifiManager != null && wifiManager.isWifiEnabled());
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        device.setGps(lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER));
        device.setIp(getActiveIpAddress(null));

        KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null) {
            device.setKeyguard(km.isKeyguardLocked());
        }
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            device.setRingVolume(am.getStreamVolume(AudioManager.STREAM_RING));
        }
        try {
            device.setMobileData(Utils.isMobileDataEnabled(context));
        } catch (Exception e) {
            // Ignore
        }
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            device.setBluetooth(bt != null && bt.isEnabled());
        } catch (Exception e) {
            // Missing Bluetooth permission
        }
        device.setUsbStorage(Settings.Global.getInt(context.getContentResolver(), "usb_mass_storage_enabled", 0) == 1);

        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(mi);
            device.setMemoryTotal((int) (mi.totalMem / (1024 * 1024)));
            device.setMemoryAvailable((int) (mi.availMem / (1024 * 1024)));
        }
        return device;
    }

    @SuppressWarnings("deprecation")
    private DetailedInfo.Wifi collectWifi() {
        DetailedInfo.Wifi wifi = new DetailedInfo.Wifi();
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null || !wifiManager.isWifiEnabled()) {
            wifi.setState(Const.WIFI_STATE_INACTIVE);
            return wifi;
        }
        WifiInfo wi = wifiManager.getConnectionInfo();
        boolean connected = isTransportActive(NetworkCapabilities.TRANSPORT_WIFI);
        wifi.setState(connected ? Const.WIFI_STATE_CONNECTED : Const.WIFI_STATE_DISCONNECTED);
        if (wi != null && connected) {
            wifi.setRssi(wi.getRssi());
            String ssid = wi.getSSID();
            if (ssid != null && !ssid.equals(WifiManager.UNKNOWN_SSID)) {
                wifi.setSsid(ssid.replace("\"", ""));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                wifi.setSecurity(securityName(wi.getCurrentSecurityType()));
            }
            wifi.setIp(getActiveIpAddress(NetworkCapabilities.TRANSPORT_WIFI));
        }
        long total = TrafficStats.getTotalTxBytes();
        long mobile = TrafficStats.getMobileTxBytes();
        if (total != TrafficStats.UNSUPPORTED && mobile != TrafficStats.UNSUPPORTED) {
            wifi.setTx(Math.max(0, total - mobile));
        }
        total = TrafficStats.getTotalRxBytes();
        mobile = TrafficStats.getMobileRxBytes();
        if (total != TrafficStats.UNSUPPORTED && mobile != TrafficStats.UNSUPPORTED) {
            wifi.setRx(Math.max(0, total - mobile));
        }
        return wifi;
    }

    private static String securityName(int type) {
        switch (type) {
            case WifiInfo.SECURITY_TYPE_OPEN: return "open";
            case WifiInfo.SECURITY_TYPE_WEP: return "wep";
            case WifiInfo.SECURITY_TYPE_PSK: return "wpa-psk";
            case WifiInfo.SECURITY_TYPE_SAE: return "wpa3-sae";
            case WifiInfo.SECURITY_TYPE_EAP: return "eap";
            default: return null;
        }
    }

    @SuppressLint("MissingPermission")
    private DetailedInfo.Gps collectGps() {
        DetailedInfo.Gps gps = new DetailedInfo.Gps();
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            gps.setState(Const.GPS_STATE_INACTIVE);
            return gps;
        }
        Location best = null;
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            for (String provider : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    Location l = lm.getLastKnownLocation(provider);
                    if (l != null && (best == null || l.getTime() > best.getTime())) {
                        best = l;
                    }
                } catch (Exception e) {
                    // Provider unavailable
                }
            }
        }
        if (best == null || System.currentTimeMillis() - best.getTime() > GPS_FIX_MAX_AGE_MS) {
            gps.setState(Const.GPS_STATE_LOST);
        } else {
            gps.setState(Const.GPS_STATE_ACTIVE);
        }
        if (best != null) {
            gps.setProvider(best.getProvider());
            gps.setLat(best.getLatitude());
            gps.setLon(best.getLongitude());
            if (best.hasAltitude()) gps.setAlt(best.getAltitude());
            if (best.hasSpeed()) gps.setSpeed((double) best.getSpeed());
            if (best.hasBearing()) gps.setCourse((double) best.getBearing());
        }
        return gps;
    }

    @SuppressLint("MissingPermission")
    private void collectMobile(DetailedInfo info) {
        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) {
            return;
        }
        boolean cellularActive = isTransportActive(NetworkCapabilities.TRANSPORT_CELLULAR);
        List<SubscriptionInfo> subs = null;
        if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            try {
                SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                subs = sm != null ? sm.getActiveSubscriptionInfoList() : null;
            } catch (Exception e) {
                // No permission
            }
        }
        int defaultDataSub = SubscriptionManager.getDefaultDataSubscriptionId();

        if (subs == null || subs.isEmpty()) {
            DetailedInfo.Mobile mobile = new DetailedInfo.Mobile();
            mobile.setSimState(simStateName(tm.getSimState()));
            mobile.setCarrier(tm.getNetworkOperatorName());
            mobile.setState(cellularActive ? Const.MOBILE_STATE_CONNECTED : Const.MOBILE_STATE_INACTIVE);
            fillSignalAndTraffic(mobile, tm, cellularActive);
            info.setMobile(mobile);
            return;
        }

        for (int i = 0; i < subs.size() && i < 2; i++) {
            SubscriptionInfo sub = subs.get(i);
            TelephonyManager subTm = tm.createForSubscriptionId(sub.getSubscriptionId());
            boolean isDataSim = sub.getSubscriptionId() == defaultDataSub;
            DetailedInfo.Mobile mobile = new DetailedInfo.Mobile();
            CharSequence carrier = sub.getCarrierName();
            mobile.setCarrier(carrier != null ? carrier.toString() : null);
            mobile.setSimState(simStateName(tm.getSimState(sub.getSimSlotIndex())));
            mobile.setNumber(DeviceInfoProvider.getPhoneNumber(context, sub.getSimSlotIndex()));
            mobile.setImsi(DeviceInfoProvider.getImsi(context, sub.getSubscriptionId()));
            if (isDataSim) {
                mobile.setState(cellularActive ? Const.MOBILE_STATE_CONNECTED : Const.MOBILE_STATE_DISCONNECTED);
            } else {
                mobile.setState(Const.MOBILE_STATE_INACTIVE);
            }
            fillSignalAndTraffic(mobile, subTm, isDataSim && cellularActive);
            if (i == 0) {
                info.setMobile(mobile);
            } else {
                info.setMobile2(mobile);
            }
        }
    }

    private void fillSignalAndTraffic(DetailedInfo.Mobile mobile, TelephonyManager tm, boolean dataActive) {
        try {
            mobile.setData(Utils.isMobileDataEnabled(context));
        } catch (Exception e) {
            // Ignore
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                SignalStrength ss = tm.getSignalStrength();
                if (ss != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    List<CellSignalStrength> cells = ss.getCellSignalStrengths();
                    if (!cells.isEmpty() && cells.get(0).getDbm() != Integer.MAX_VALUE) {
                        mobile.setRssi(cells.get(0).getDbm());
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        if (dataActive) {
            mobile.setIp(getActiveIpAddress(NetworkCapabilities.TRANSPORT_CELLULAR));
            long tx = TrafficStats.getMobileTxBytes();
            long rx = TrafficStats.getMobileRxBytes();
            if (tx != TrafficStats.UNSUPPORTED) mobile.setTx(tx);
            if (rx != TrafficStats.UNSUPPORTED) mobile.setRx(rx);
        }
    }

    private static String simStateName(int state) {
        switch (state) {
            case TelephonyManager.SIM_STATE_ABSENT: return Const.MOBILE_SIMSTATE_ABSENT;
            case TelephonyManager.SIM_STATE_PIN_REQUIRED: return Const.MOBILE_SIMSTATE_PIN_REQUIRED;
            case TelephonyManager.SIM_STATE_PUK_REQUIRED: return Const.MOBILE_SIMSTATE_PUK_REQUIRED;
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED: return Const.MOBILE_SIMSTATE_LOCKED;
            case TelephonyManager.SIM_STATE_READY: return Const.MOBILE_SIMSTATE_READY;
            case TelephonyManager.SIM_STATE_NOT_READY: return Const.MOBILE_SIMSTATE_NOT_READY;
            case TelephonyManager.SIM_STATE_PERM_DISABLED: return Const.MOBILE_SIMSTATE_DISABLED;
            case TelephonyManager.SIM_STATE_CARD_IO_ERROR: return Const.MOBILE_SIMSTATE_ERROR;
            case TelephonyManager.SIM_STATE_CARD_RESTRICTED: return Const.MOBILE_SIMSTATE_RESTRICTED;
            default: return Const.MOBILE_SIMSTATE_UNKNOWN;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Network helpers

    private boolean isTransportActive(int transport) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
        return caps != null && caps.hasTransport(transport);
    }

    // IPv4 address of the active network, or of the first network with the given transport
    private String getActiveIpAddress(Integer transport) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return null;
        }
        Network target = null;
        if (transport == null) {
            target = cm.getActiveNetwork();
        } else {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(transport)) {
                    target = n;
                    break;
                }
            }
        }
        if (target == null) {
            return null;
        }
        LinkProperties lp = cm.getLinkProperties(target);
        if (lp == null) {
            return null;
        }
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address) {
                return la.getAddress().getHostAddress();
            }
        }
        return null;
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
