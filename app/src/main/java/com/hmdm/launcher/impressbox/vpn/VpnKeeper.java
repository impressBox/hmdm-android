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

package com.hmdm.launcher.impressbox.vpn;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Log;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.util.LegacyUtils;
import com.hmdm.launcher.util.RemoteLogger;
import com.hmdm.launcher.util.Utils;
import com.wireguard.config.Config;
import com.wireguard.crypto.KeyPair;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.StringReader;

/**
 * impressBox remote-access VPN, launcher side (main process).
 *
 * A WireGuard tunnel to the impressBox VPN hub, so technicians can reach the device over ADB even when
 * it sits behind NAT. The tunnel itself runs in the separate ":vpn" process (VpnTunnel) and is an
 * always-on VPN set by the device owner, so Android keeps it up even if this process crashes.
 *
 * Only the staff range (vpn_allowed_ips) is routed into the tunnel; all other traffic, including the
 * player's, goes out as before, and always-on VPN is set WITHOUT lockdown, so a VPN outage never cuts
 * the device off. Isolation (no device-to-device or device-to-our-network traffic) is enforced on the hub.
 *
 * Flow:
 * 1. The launcher generates the WireGuard key pair on the device (the private key never leaves it) and
 *    publishes the public key in a device custom field (default custom3, "wg:&lt;public key&gt;") and in the log.
 * 2. The backend registers that key as a peer on the hub and puts the device's tunnel address and the hub
 *    details into the launcher app settings (server: Applications > launcher settings):
 *    - vpn_enabled       "true" to run the tunnel (anything else: tunnel off, always-on VPN cleared)
 *    - vpn_address       the device's tunnel address, e.g. 10.66.0.12/32
 *    - vpn_endpoint      hub host:port, e.g. vpn.impressbox.eu:51820
 *    - vpn_server_key    hub public key (base64)
 *    - vpn_allowed_ips   routed into the tunnel (default BuildConfig.VPN_ALLOWED_IPS, the staff range)
 *    - vpn_keepalive     persistent keepalive seconds (default 25, keeps the NAT mapping open)
 *    - vpn_mtu           tunnel MTU (default 1280)
 *    - vpn_custom_field  1, 2 or 3: custom field for the public key (default 3); "off" to not publish it
 * 3. The launcher writes wg.conf, sets itself as the always-on VPN and tells the ":vpn" process to apply.
 *
 * Checked at start, after every configuration update and every few minutes; state changes (up/down,
 * handshake lost/regained, errors) go to the server log.
 */
public class VpnKeeper {

    private static final long CHECK_INTERVAL_MS = 5 * 60 * 1000;
    // Read the tunnel status this long after asking it to apply
    private static final long STATUS_DELAY_MS = 20 * 1000;
    // With a 25 s keepalive WireGuard re-handshakes every ~2 minutes; older than this = hub unreachable
    private static final long HANDSHAKE_STALE_MS = 5 * 60 * 1000;

    public static final String SETTING_ENABLED = "vpn_enabled";
    public static final String SETTING_ADDRESS = "vpn_address";
    public static final String SETTING_ENDPOINT = "vpn_endpoint";
    public static final String SETTING_SERVER_KEY = "vpn_server_key";
    public static final String SETTING_ALLOWED_IPS = "vpn_allowed_ips";
    public static final String SETTING_KEEPALIVE = "vpn_keepalive";
    public static final String SETTING_MTU = "vpn_mtu";
    public static final String SETTING_CUSTOM_FIELD = "vpn_custom_field";

    private static final String CUSTOM_PREFIX = "wg:";

    private static Handler handler;
    private static Context appContext;
    private static String lastReported;
    private static String loggedPublicKey;

    private VpnKeeper() {}

    /** Checks now, then every CHECK_INTERVAL_MS on a background thread. Safe to call more than once. */
    public static synchronized void start(Context context) {
        if (!BuildConfig.VPN_SUPPORTED || handler != null) {
            return;
        }
        appContext = context.getApplicationContext();
        HandlerThread thread = new HandlerThread("impressbox-vpn");
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

    /** Called after the server configuration was updated: re-check soon (settings may have changed). */
    public static synchronized void requestCheck() {
        if (handler == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                ensure(appContext);
            }
        });
    }

    private static void ensure(final Context context) {
        try {
            SettingsHelper settings = SettingsHelper.getInstance(context);
            if (settings.getConfig() == null) {
                // Not enrolled yet
                return;
            }

            KeyPair keyPair = VpnFiles.getOrCreateKeyPair(context);
            if (keyPair == null) {
                RemoteLogger.log(context, Const.LOG_WARN, "VPN: cannot store the WireGuard key");
                return;
            }
            String publicKey = keyPair.getPublicKey().toBase64();
            publishPublicKey(context, settings, publicKey);

            String configText = null;
            String problem = null;
            if ("true".equalsIgnoreCase(setting(settings, context, SETTING_ENABLED))) {
                String address = setting(settings, context, SETTING_ADDRESS);
                String endpoint = setting(settings, context, SETTING_ENDPOINT);
                String serverKey = setting(settings, context, SETTING_SERVER_KEY);
                if (TextUtils.isEmpty(address) || TextUtils.isEmpty(endpoint) || TextUtils.isEmpty(serverKey)) {
                    problem = "enabled, but vpn_address / vpn_endpoint / vpn_server_key are not all set"
                            + " (register public key " + publicKey + " on the hub first)";
                } else {
                    configText = buildConfig(keyPair, address, endpoint, serverKey,
                            valueOr(setting(settings, context, SETTING_ALLOWED_IPS), BuildConfig.VPN_ALLOWED_IPS),
                            valueOr(setting(settings, context, SETTING_KEEPALIVE), "25"),
                            valueOr(setting(settings, context, SETTING_MTU), "1280"));
                    try {
                        Config.parse(new BufferedReader(new StringReader(configText)));
                    } catch (Exception e) {
                        problem = "invalid settings: " + e.getMessage();
                        configText = null;
                    }
                }
            }

            boolean changed = VpnFiles.writeConfig(context, configText);
            String alwaysOn = updateAlwaysOn(context, configText != null);

            if (configText != null || changed) {
                final long sentAt = System.currentTimeMillis();
                VpnControlReceiver.send(context);
                final String finalProblem = problem;
                final String finalAlwaysOn = alwaysOn;
                final boolean wanted = configText != null;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (wanted && !isTunnelUp(context, sentAt) && restartAlwaysOn(context)) {
                            // Give the restarted tunnel time to come up, then report
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    report(context, finalProblem, finalAlwaysOn);
                                }
                            }, STATUS_DELAY_MS);
                            return;
                        }
                        report(context, finalProblem, finalAlwaysOn);
                    }
                }, STATUS_DELAY_MS);
            } else {
                report(context, problem, alwaysOn);
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "VPN check failed: " + e.getMessage());
        }
    }

    private static String buildConfig(KeyPair keyPair, String address, String endpoint, String serverKey,
                                      String allowedIps, String keepalive, String mtu) {
        // No DNS line: the tunnel must not touch the device's DNS
        return "[Interface]\n"
                + "PrivateKey = " + keyPair.getPrivateKey().toBase64() + "\n"
                + "Address = " + address.trim() + "\n"
                + "MTU = " + mtu.trim() + "\n"
                + "\n"
                + "[Peer]\n"
                + "PublicKey = " + serverKey.trim() + "\n"
                + "Endpoint = " + endpoint.trim() + "\n"
                + "AllowedIPs = " + allowedIps.trim() + "\n"
                + "PersistentKeepalive = " + keepalive.trim() + "\n";
    }

    /**
     * Makes this app the always-on VPN (without lockdown) while the tunnel is configured, and clears it
     * otherwise. Setting it as device owner also grants the VPN consent, so no dialog appears.
     */
    private static String updateAlwaysOn(Context context, boolean wanted) {
        if (!Utils.isDeviceOwner(context)) {
            return wanted ? "not device owner: always-on VPN not set" : null;
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = LegacyUtils.getAdminComponentName(context);
            String current = dpm.getAlwaysOnVpnPackage(admin);
            String own = context.getPackageName();
            if (wanted && !own.equals(current)) {
                dpm.setAlwaysOnVpnPackage(admin, own, false);
                RemoteLogger.log(context, Const.LOG_INFO, "VPN: launcher set as always-on VPN"
                        + (current != null ? " (was " + current + ")" : ""));
            } else if (!wanted && own.equals(current)) {
                dpm.setAlwaysOnVpnPackage(admin, null, false);
                RemoteLogger.log(context, Const.LOG_INFO, "VPN: always-on VPN cleared");
            }
            return null;
        } catch (Exception e) {
            return "always-on VPN failed: " + e.getMessage();
        }
    }

    // True when the tunnel process applied the config after `since` and the tunnel is up
    private static boolean isTunnelUp(Context context, long since) {
        JSONObject status = VpnFiles.readStatus(context);
        return status != null && status.optLong("time", 0) >= since && "UP".equals(status.optString("state"));
    }

    /**
     * The tunnel process did not answer (killed, crashed) or could not bring the tunnel up. Android does not
     * always restart an always-on VPN whose process died, and an app in the background may not start the
     * VPN service itself; clearing and setting always-on again makes the system start it right away.
     */
    private static boolean restartAlwaysOn(Context context) {
        if (!Utils.isDeviceOwner(context)) {
            return false;
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = LegacyUtils.getAdminComponentName(context);
            dpm.setAlwaysOnVpnPackage(admin, null, false);
            dpm.setAlwaysOnVpnPackage(admin, context.getPackageName(), false);
            RemoteLogger.log(context, Const.LOG_WARN, "VPN: tunnel was not running, always-on VPN restarted");
            return true;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "VPN: cannot restart always-on VPN: " + e.getMessage());
            return false;
        }
    }

    // Puts "wg:<public key>" into the chosen custom field; it is sent with the next device info
    private static void publishPublicKey(Context context, SettingsHelper settings, String publicKey) {
        if (!publicKey.equals(loggedPublicKey)) {
            RemoteLogger.log(context, Const.LOG_INFO, "VPN: device WireGuard public key " + publicKey);
            loggedPublicKey = publicKey;
        }
        String field = valueOr(setting(settings, context, SETTING_CUSTOM_FIELD), "3").trim();
        String value = CUSTOM_PREFIX + publicKey;
        ServerConfig config = settings.getConfig();
        switch (field) {
            case "1":
                if (!value.equals(settings.getUserCustom1())) {
                    settings.setUserCustom1(value);
                    config.setCustom1(value);
                }
                break;
            case "2":
                if (!value.equals(settings.getUserCustom2())) {
                    settings.setUserCustom2(value);
                    config.setCustom2(value);
                }
                break;
            case "3":
                if (!value.equals(settings.getUserCustom3())) {
                    settings.setUserCustom3(value);
                    config.setCustom3(value);
                }
                break;
            default:
                // "off"
                break;
        }
    }

    // Logs the state when it differs from the last one reported
    private static void report(Context context, String problem, String alwaysOnProblem) {
        StringBuilder state = new StringBuilder();
        boolean configured = VpnFiles.readConfig(context) != null;
        if (!configured) {
            state.append("off");
        } else {
            JSONObject status = VpnFiles.readStatus(context);
            if (status == null) {
                state.append("starting");
            } else {
                state.append(status.optString("state", "?"));
                if (status.has("error")) {
                    state.append(", error: ").append(status.optString("error"));
                }
                long handshake = status.optLong("handshake", 0);
                if ("UP".equals(status.optString("state"))) {
                    if (handshake == 0) {
                        state.append(", no handshake with the hub yet");
                    } else if (System.currentTimeMillis() - handshake > HANDSHAKE_STALE_MS) {
                        state.append(", hub not reachable (last handshake over 5 min ago)");
                    } else {
                        state.append(", connected to the hub");
                    }
                }
            }
        }
        if (problem != null) {
            state.append("; ").append(problem);
        }
        if (alwaysOnProblem != null) {
            state.append("; ").append(alwaysOnProblem);
        }
        String text = state.toString();
        if (!text.equals(lastReported)) {
            boolean bad = problem != null || alwaysOnProblem != null || text.contains("error")
                    || text.contains("not reachable");
            RemoteLogger.log(context, bad ? Const.LOG_WARN : Const.LOG_INFO, "VPN: " + text);
            lastReported = text;
        }
    }

    private static String setting(SettingsHelper settings, Context context, String name) {
        return settings.getAppPreference(context.getPackageName(), name);
    }

    private static String valueOr(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }
}
