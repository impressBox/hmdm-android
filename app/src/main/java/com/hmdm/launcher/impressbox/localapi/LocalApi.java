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

/**
 * Names and codes of the launcher local API (ILocalApi). They must match docs/launcher-local-api.json, the contract
 * shared with impress-player; LocalApiContractTest checks it.
 */
public final class LocalApi {

    public static final int API_VERSION = 1;
    public static final String ACTION = "com.impressbox.launcher.LOCAL_API";

    // Keys of every result
    public static final String KEY_CODE = "code";
    public static final String KEY_MESSAGE = "message";

    // Result codes
    public static final int OK = 0;
    public static final int CALLER_NOT_ALLOWED = 1;
    public static final int UNKNOWN_COMMAND = 2;
    public static final int BAD_ARGUMENT = 3;
    public static final int NOT_DEVICE_OWNER = 4;
    public static final int UNSUPPORTED = 5;
    public static final int MANAGED_BY_CONFIG = 6;
    public static final int REFUSED = 7;
    public static final int FAILED = 8;

    // Commands
    public static final String GET_STATUS = "getStatus";
    public static final String SET_TIME_ZONE = "setTimeZone";
    public static final String SET_AUTO_TIME = "setAutoTime";
    public static final String SET_TIME = "setTime";
    public static final String REBOOT = "reboot";
    public static final String SET_SYSTEM_UPDATE_POLICY = "setSystemUpdatePolicy";

    // Arguments
    public static final String ARG_ZONE = "zone";
    public static final String ARG_ENABLED = "enabled";
    public static final String ARG_EPOCH_MILLIS = "epochMillis";
    public static final String ARG_REASON = "reason";
    public static final String ARG_TYPE = "type";
    public static final String ARG_WINDOW_START = "windowStart";
    public static final String ARG_WINDOW_END = "windowEnd";

    // Result values
    public static final String RESULT_ZONE = "zone";
    public static final String RESULT_PREVIOUS_ZONE = "previousZone";
    public static final String RESULT_AUTO_TIME_ZONE = "autoTimeZone";
    public static final String RESULT_AUTO_TIME = "autoTime";
    public static final String RESULT_DEVICE_OWNER = "deviceOwner";
    public static final String RESULT_CONFIG_TIME_ZONE = "configTimeZone";
    public static final String RESULT_LOCAL_API_ZONE = "localApiZone";
    public static final String RESULT_SYSTEM_UPDATE_POLICY = "systemUpdatePolicy";
    public static final String RESULT_LAUNCHER_VERSION = "launcherVersion";

    /** setTimeZone with this zone gives the zone back to automatic detection. */
    public static final String ZONE_AUTO = "auto";

    // setSystemUpdatePolicy types
    public static final String UPDATE_AUTOMATIC = "automatic";
    public static final String UPDATE_WINDOWED = "windowed";
    public static final String UPDATE_POSTPONE = "postpone";
    public static final String UPDATE_NONE = "none";

    private LocalApi() {}
}
