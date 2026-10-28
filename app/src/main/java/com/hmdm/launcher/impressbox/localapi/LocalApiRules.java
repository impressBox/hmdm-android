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

import com.hmdm.launcher.json.ServerConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The local API's decisions, kept free of Android calls so they can be unit tested. */
final class LocalApiRules {

    /** The fixed allowlist of commands, in contract order. */
    static final List<String> COMMANDS = Collections.unmodifiableList(Arrays.asList(
            LocalApi.GET_STATUS,
            LocalApi.SET_TIME_ZONE,
            LocalApi.SET_AUTO_TIME,
            LocalApi.SET_TIME,
            LocalApi.REBOOT,
            LocalApi.SET_SYSTEM_UPDATE_POLICY));

    // Android levels: 26 is the launcher's minSdk (Android 8); DevicePolicyManager.setTimeZone/setTime need 28 (Android 9)
    static final int SDK_LAUNCHER_MIN = 26;
    static final int SDK_P = 28;

    // setTime accepts only plausible clocks: 2020-01-01 .. 2100-01-01 UTC
    static final long MIN_EPOCH_MILLIS = 1577836800000L;
    static final long MAX_EPOCH_MILLIS = 4102444800000L;

    static final int MINUTES_PER_DAY = 24 * 60;

    private LocalApiRules() {}

    static int minSdk(String command) {
        if (LocalApi.SET_TIME_ZONE.equals(command) || LocalApi.SET_TIME.equals(command)) {
            return SDK_P;
        }
        return SDK_LAUNCHER_MIN;
    }

    static boolean needsDeviceOwner(String command) {
        return !LocalApi.GET_STATUS.equals(command);
    }

    /** OK when the command can run here, else the code that refuses it. */
    static int refusal(String command, int sdk, boolean deviceOwner) {
        if (command == null || !COMMANDS.contains(command)) {
            return LocalApi.UNKNOWN_COMMAND;
        }
        if (needsDeviceOwner(command) && !deviceOwner) {
            return LocalApi.NOT_DEVICE_OWNER;
        }
        if (sdk < minSdk(command)) {
            return LocalApi.UNSUPPORTED;
        }
        return LocalApi.OK;
    }

    static List<String> capabilities(int sdk, boolean deviceOwner) {
        List<String> result = new ArrayList<>();
        for (String command : COMMANDS) {
            if (refusal(command, sdk, deviceOwner) == LocalApi.OK) {
                result.add(command);
            }
        }
        return result;
    }

    /**
     * True when the HMDM configuration sets a fixed zone: then the configuration owns the zone and setTimeZone refuses,
     * so the launcher and the player never fight. Empty and "auto" leave the zone to the device.
     */
    static boolean zoneManagedByConfig(String configZone) {
        if (configZone == null) {
            return false;
        }
        String zone = configZone.trim();
        return !zone.isEmpty() && !LocalApi.ZONE_AUTO.equalsIgnoreCase(zone);
    }

    static boolean isKnownZone(String zone, Collection<String> availableIds) {
        return zone != null && availableIds.contains(zone);
    }

    /** True when the HMDM configuration sets a system update policy; the launcher re-applies it at every sync. */
    static boolean updatePolicyManagedByConfig(Integer configType) {
        return configType != null && configType != ServerConfig.SYSTEM_UPDATE_DEFAULT;
    }

    /** Null when the update window is valid, else what is wrong with it. */
    static String windowProblem(int start, int end) {
        if (start < 0 || start >= MINUTES_PER_DAY) {
            return "windowStart must be 0-1439 minutes after midnight, got " + start;
        }
        if (end < 0 || end >= MINUTES_PER_DAY) {
            return "windowEnd must be 0-1439 minutes after midnight, got " + end;
        }
        return null;
    }

    static boolean isPlausibleTime(long epochMillis) {
        return epochMillis >= MIN_EPOCH_MILLIS && epochMillis < MAX_EPOCH_MILLIS;
    }

    /**
     * Parses certificate SHA-256 digests ("AA:BB:..." or plain hex, separated by commas, semicolons or spaces) into
     * upper-case hex without colons. Anything that is not a 64-digit hex digest is ignored.
     */
    static Set<String> parseCertDigests(String... lists) {
        Set<String> result = new LinkedHashSet<>();
        for (String list : lists) {
            if (list == null) {
                continue;
            }
            for (String item : list.split("[,;\\s]+")) {
                String hex = item.replace(":", "").trim().toUpperCase(Locale.ROOT);
                if (hex.matches("[0-9A-F]{64}")) {
                    result.add(hex);
                }
            }
        }
        return result;
    }

    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02X", b & 0xff));
        }
        return sb.toString();
    }

    static byte[] fromHex(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }
}
