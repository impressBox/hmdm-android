/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
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

package com.hmdm.launcher;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileInputStream;

import com.hmdm.launcher.impressbox.AdbKeeper;
import com.hmdm.launcher.impressbox.DeviceSetup;
import com.hmdm.launcher.impressbox.vpn.VpnKeeper;
import com.hmdm.launcher.impressbox.vpn.VpnTunnel;
import com.hmdm.launcher.ui.MainActivity;

import com.jakewharton.picasso.OkHttp3Downloader;
import com.squareup.picasso.Picasso;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // impressBox: the ":vpn" process only runs the WireGuard tunnel (see VpnTunnel)
        if (isVpnProcess()) {
            VpnTunnel.initProcess(this);
            return;
        }

        Picasso.Builder builder = new Picasso.Builder(this);
        builder.downloader(new OkHttp3Downloader(this,Integer.MAX_VALUE));
        Picasso built = builder.build();
        //built.setIndicatorsEnabled(true);
        //built.setLoggingEnabled(true);
        Picasso.setSingletonInstance(built);

        registerActivityLifecycleCallbacks(new ProvisioningHook());

        // impressBox: keep USB / network ADB on (checked now and every few minutes)
        AdbKeeper.start(this);

        // impressBox: remote-access WireGuard VPN (always-on, runs in the ":vpn" process)
        VpnKeeper.start(this);
    }

    /**
     * impressBox: picks up the provisioning extras the Writer passes with
     * "am start -n com.hmdm.launcher/.ui.MainActivity --es com.hmdm.DEVICE_ID ... --ez com.impressbox.LOCK_ROTATION ..."
     * and applies the device setup. onActivityCreated runs inside MainActivity's super.onCreate(),
     * i.e. before MainActivity decides whether it needs to ask for a device ID.
     */
    private static class ProvisioningHook implements ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
            if (activity instanceof MainActivity) {
                // Signage: the launcher wakes the screen itself and is never hidden behind the lock screen,
                // so nobody has to press a key (the Writer no longer sends KEYCODE_WAKEUP)
                DeviceSetup.wakeUp(activity);
                if (savedInstanceState == null) {
                    DeviceSetup.consumeProvisioningExtras(activity, activity.getIntent());
                    DeviceSetup.apply(activity);
                }
            }
        }
        @Override public void onActivityStarted(@NonNull Activity activity) {}
        @Override public void onActivityResumed(@NonNull Activity activity) {}
        @Override public void onActivityPaused(@NonNull Activity activity) {}
        @Override public void onActivityStopped(@NonNull Activity activity) {}
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}
        @Override public void onActivityDestroyed(@NonNull Activity activity) {}
    }

    private static final String VPN_PROCESS_SUFFIX = ":vpn";

    private static boolean isVpnProcess() {
        String name = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            name = Application.getProcessName();
        } else {
            FileInputStream in = null;
            try {
                in = new FileInputStream("/proc/self/cmdline");
                byte[] buffer = new byte[256];
                int length = in.read(buffer);
                int end = 0;
                while (end < length && buffer[end] != 0) {
                    end++;
                }
                name = new String(buffer, 0, end);
            } catch (Exception e) {
                // Assume the main process
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception e) {
                        // Ignore
                    }
                }
            }
        }
        return name != null && name.endsWith(VPN_PROCESS_SUFFIX);
    }
}
