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
import android.content.Intent;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.pro.KioskController;
import com.hmdm.launcher.util.RemoteLogger;

/**
 * Leaving kiosk mode with a code typed on the remote control (default 9999).
 *
 * Key presses go to the app on screen (the player), so they are watched by the launcher's accessibility
 * service, which sees every key without consuming it. The digit keys of IR / Bluetooth remotes arrive as
 * KEYCODE_0..9 (some as NUMPAD_0..9) on practically every Android device; both are accepted.
 *
 * Typing the code does what the server's "exit kiosk" command does: kiosk mode is left until the next
 * configuration update or restart. Every exit is written to the server log.
 *
 * Launcher app settings (server: Applications > launcher settings):
 * - kiosk_exit_code: the code, digits only; "off" or an empty value disables it (default BuildConfig.KIOSK_EXIT_CODE)
 * - log_key_codes: "true" writes every key code the device receives to the server log, to see what a new
 *   remote control sends
 */
public class KioskExitCode {

    private static final String APP_SETTING_CODE = "kiosk_exit_code";
    private static final String APP_SETTING_LOG_KEYS = "log_key_codes";
    // Digits typed further apart than this start a new code
    private static final long MAX_GAP_MS = 3000;
    private static final int MAX_CODE_LENGTH = 16;

    private static final StringBuilder typed = new StringBuilder();
    private static long lastDigitTime = 0;

    private KioskExitCode() {}

    /** True when the build can use the code; the accessibility service must then be on. */
    public static boolean isEnabledInBuild() {
        return !TextUtils.isEmpty(BuildConfig.KIOSK_EXIT_CODE);
    }

    /** Called by the accessibility service for every key; never consumes the key. */
    public static synchronized void onKeyEvent(Context context, KeyEvent event) {
        if (!isEnabledInBuild() || event == null) {
            return;
        }
        SettingsHelper settings = SettingsHelper.getInstance(context);
        if ("true".equalsIgnoreCase(settings.getAppPreference(context.getPackageName(), APP_SETTING_LOG_KEYS))
                && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            InputDevice device = event.getDevice();
            RemoteLogger.log(context, Const.LOG_INFO, "Key: " + KeyEvent.keyCodeToString(event.getKeyCode())
                    + " (" + event.getKeyCode() + ", scan " + event.getScanCode() + ") from "
                    + (device != null ? device.getName() : "unknown device"));
        }

        if (event.getAction() != KeyEvent.ACTION_UP) {
            return;
        }
        char digit = digitOf(event.getKeyCode());
        if (digit == 0) {
            return;
        }
        String code = getCode(context);
        if (code == null) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastDigitTime > MAX_GAP_MS) {
            typed.setLength(0);
        }
        lastDigitTime = now;
        typed.append(digit);
        if (typed.length() > MAX_CODE_LENGTH) {
            typed.delete(0, typed.length() - MAX_CODE_LENGTH);
        }
        if (typed.toString().endsWith(code)) {
            typed.setLength(0);
            exitKiosk(context);
        }
    }

    private static void exitKiosk(Context context) {
        ServerConfig config = SettingsHelper.getInstance(context).getConfig();
        boolean inKiosk = KioskController.isRunning(context) || (config != null && config.isKioskMode());
        if (!inKiosk) {
            return;
        }
        RemoteLogger.log(context, Const.LOG_INFO, "Exit kiosk by the code typed on the remote control");
        // Handled by MainActivity like the server's "exit kiosk" command
        LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(Const.ACTION_EXIT_KIOSK));
    }

    // The code from the launcher app settings, else the build default; null when disabled
    private static String getCode(Context context) {
        String code = SettingsHelper.getInstance(context).getAppPreference(context.getPackageName(), APP_SETTING_CODE);
        if (code == null) {
            code = BuildConfig.KIOSK_EXIT_CODE;
        }
        code = code.trim();
        if (code.isEmpty() || "off".equalsIgnoreCase(code) || !code.matches("[0-9]{1," + MAX_CODE_LENGTH + "}")) {
            return null;
        }
        return code;
    }

    private static char digitOf(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return (char) ('0' + keyCode - KeyEvent.KEYCODE_0);
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return (char) ('0' + keyCode - KeyEvent.KEYCODE_NUMPAD_0);
        }
        return 0;
    }
}
