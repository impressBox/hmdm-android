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

import android.content.Context;
import android.os.SystemClock;

import com.hmdm.launcher.helper.SettingsHelper;

/**
 * Kiosk mode left on purpose (the code on the remote control, or the server's "exit kiosk" command).
 *
 * Before, leaving kiosk mode only cleared the kiosk flag of the configuration held in memory, and the
 * launcher started the main app again right away (seen on a 17MB400VS panel: "Exit kiosk by admin command"
 * followed 0.1 s later by the player being started in lock task mode again). The suspension is checked
 * where the launcher decides whether kiosk mode is required, so nothing can turn it back on by accident:
 * not a reloaded or newly received configuration, not the launcher coming back to front.
 *
 * It ends when:
 * - the code is typed again on the remote control (KioskExitCode),
 * - the time from the launcher app setting kiosk_exit_minutes runs out (default 30; 0 = until restart),
 * - the launcher restarts (the state is only kept in memory, so a reboot always brings the kiosk back).
 */
public class KioskSuspension {

    private static final String APP_SETTING_MINUTES = "kiosk_exit_minutes";
    private static final long DEFAULT_MINUTES = 30;

    // elapsedRealtime() until which kiosk mode stays off; 0 = not suspended
    private static volatile long until = 0;

    private KioskSuspension() {}

    public static boolean isActive() {
        long u = until;
        if (u == 0) {
            return false;
        }
        if (SystemClock.elapsedRealtime() >= u) {
            until = 0;
            return false;
        }
        return true;
    }

    /** Starts the suspension; returns its length in ms (Long.MAX_VALUE = until restart). */
    public static long start(Context context) {
        long minutes = getMinutes(context);
        if (minutes <= 0) {
            until = Long.MAX_VALUE;
            return Long.MAX_VALUE;
        }
        long ms = minutes * 60_000L;
        until = SystemClock.elapsedRealtime() + ms;
        return ms;
    }

    public static void end() {
        until = 0;
    }

    public static long getMinutes(Context context) {
        String value = SettingsHelper.getInstance(context).getAppPreference(context.getPackageName(), APP_SETTING_MINUTES);
        if (value != null) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                // Use the default
            }
        }
        return DEFAULT_MINUTES;
    }
}
