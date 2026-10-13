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

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.util.RemoteLogger;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Self-enrollment with the hub's registrar (k8s-services vpn/registrar): the device sends its device ID and
 * WireGuard public key, signed with HMAC-SHA256(VPN_REGISTER_SECRET, deviceId + "\n" + publicKey + "\n" + ts),
 * and gets back its tunnel address, the hub endpoint and the hub key.
 *
 * The answer is cached (VpnFiles registration.json) and used as long as the registrar cannot be reached, so
 * a device that enrolled once keeps its tunnel through registrar or network outages. It is re-confirmed
 * every RECONFIRM_MS, when the key changed, or when the caller says the hub stopped answering.
 * Runs on the VpnKeeper thread (blocking network I/O).
 */
public final class VpnEnrollment {

    private static final long RECONFIRM_MS = 12 * 60 * 60 * 1000L;
    // Do not hammer the registrar: at most one attempt per this interval unless nothing is cached yet
    private static final long MIN_RETRY_MS = 30 * 60 * 1000L;
    private static final long FIRST_RETRY_MS = 60 * 1000L;
    private static final int TIMEOUT_MS = 15000;

    private static long lastAttempt;
    private static String lastLogged;

    private VpnEnrollment() {}

    public static final class Result {
        public String address;
        public String endpoint;
        public String serverKey;
        public String allowedIps;
        public String problem;
    }

    public static boolean isAvailable() {
        return !TextUtils.isEmpty(BuildConfig.VPN_REGISTER_URL) && !TextUtils.isEmpty(BuildConfig.VPN_REGISTER_SECRET);
    }

    public static synchronized Result get(Context context, String deviceId, String publicKey, boolean hubLost) {
        Result result = new Result();
        JSONObject cached = VpnFiles.readRegistration(context);
        boolean usable = cached != null && publicKey.equals(cached.optString("public_key"))
                && deviceId != null && deviceId.equals(cached.optString("device_id"))
                && !TextUtils.isEmpty(cached.optString("address"));
        long age = usable ? System.currentTimeMillis() - cached.optLong("time", 0) : Long.MAX_VALUE;
        long sinceAttempt = System.currentTimeMillis() - lastAttempt;

        boolean due = !usable || age > RECONFIRM_MS || hubLost;
        boolean allowed = sinceAttempt > (usable ? MIN_RETRY_MS : FIRST_RETRY_MS);
        if (TextUtils.isEmpty(deviceId)) {
            result.problem = "no device ID yet, cannot enroll";
        } else if (due && allowed) {
            lastAttempt = System.currentTimeMillis();
            try {
                JSONObject answer = register(deviceId, publicKey);
                answer.put("public_key", publicKey);
                answer.put("device_id", deviceId);
                answer.put("time", System.currentTimeMillis());
                VpnFiles.writeRegistration(context, answer);
                String message = "VPN: enrolled with the hub as " + answer.optString("address");
                if (!usable || !answer.optString("address").equals(cached.optString("address"))) {
                    RemoteLogger.log(context, Const.LOG_INFO, message);
                }
                cached = answer;
                usable = true;
                lastLogged = null;
            } catch (Exception e) {
                String message = "VPN: enrollment failed: " + e.getMessage()
                        + (usable ? " (keeping the address from the last enrollment)" : "");
                if (!message.equals(lastLogged)) {
                    RemoteLogger.log(context, Const.LOG_WARN, message);
                    lastLogged = message;
                }
                if (!usable) {
                    result.problem = "not enrolled yet: " + e.getMessage();
                }
            }
        }
        if (usable) {
            result.address = cached.optString("address");
            result.endpoint = cached.optString("endpoint");
            result.serverKey = cached.optString("server_key");
            result.allowedIps = cached.optString("allowed_ips", null);
        }
        return result;
    }

    private static JSONObject register(String deviceId, String publicKey) throws Exception {
        long ts = System.currentTimeMillis() / 1000;
        JSONObject body = new JSONObject();
        body.put("device_id", deviceId);
        body.put("public_key", publicKey);
        body.put("ts", ts);
        body.put("sig", hmacSha256Hex(BuildConfig.VPN_REGISTER_SECRET, deviceId + "\n" + publicKey + "\n" + ts));
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = (HttpURLConnection) new URL(BuildConfig.VPN_REGISTER_URL).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(data.length);
            OutputStream out = connection.getOutputStream();
            out.write(data);
            out.close();
            int code = connection.getResponseCode();
            InputStream in = code < 400 ? connection.getInputStream() : connection.getErrorStream();
            String text = in != null ? readAll(in) : "";
            JSONObject answer = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            if (code != 200) {
                throw new Exception("HTTP " + code + " " + answer.optString("error"));
            }
            for (String key : new String[]{"address", "endpoint", "server_key"}) {
                if (TextUtils.isEmpty(answer.optString(key))) {
                    throw new Exception("incomplete answer from the registrar (" + key + ")");
                }
            }
            return answer;
        } finally {
            connection.disconnect();
        }
    }

    private static String hmacSha256Hex(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0 && buffer.size() < 65536) {
            buffer.write(chunk, 0, n);
        }
        in.close();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
