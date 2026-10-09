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
import android.util.Log;

import com.hmdm.launcher.Const;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The WireGuard tunnel. Runs ONLY in the ":vpn" process, together with GoBackend's VpnService, so a
 * crash or restart of the launcher process does not take the tunnel down, and Android's always-on VPN
 * brings this process (and the tunnel) back by itself at boot or after it was killed.
 *
 * The tunnel is (re)applied when:
 * - Android starts the VPN service for always-on VPN (GoBackend's AlwaysOnCallback),
 * - the launcher sends VpnControlReceiver.ACTION_APPLY (config changed, or its periodic check).
 *
 * It runs whatever wg.conf says; no wg.conf means down. After each apply the state is written to
 * status.json for the launcher to report.
 */
public final class VpnTunnel {

    // The first handshake completes a moment after the tunnel comes up: write the status again then
    private static final long STATUS_REFRESH_S = 10;

    private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private static GoBackend backend;
    private static final Tunnel tunnel = new Tunnel() {
        @Override
        public String getName() {
            return VpnFiles.TUNNEL_NAME;
        }

        @Override
        public void onStateChange(State newState) {
            Log.i(Const.LOG_TAG, "VPN: tunnel " + newState);
        }
    };
    private static String appliedConfig;
    private static String lastError;

    private VpnTunnel() {}

    /** Called from Application.onCreate() of the ":vpn" process. */
    public static void initProcess(final Context context) {
        final Context appContext = context.getApplicationContext();
        GoBackend.setAlwaysOnCallback(new GoBackend.AlwaysOnCallback() {
            @Override
            public void alwaysOnTriggered() {
                Log.i(Const.LOG_TAG, "VPN: started by always-on VPN");
                apply(appContext, null);
            }
        });
    }

    /** Brings the tunnel to what wg.conf says, on the tunnel thread; done runs afterwards (may be null). */
    public static void apply(final Context context, final Runnable done) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    applyNow(context.getApplicationContext());
                } finally {
                    if (done != null) {
                        done.run();
                    }
                }
            }
        });
    }

    private static synchronized GoBackend getBackend(Context context) {
        if (backend == null) {
            backend = new GoBackend(context);
        }
        return backend;
    }

    private static void applyNow(final Context context) {
        String error = null;
        String text = VpnFiles.readConfig(context);
        GoBackend goBackend = getBackend(context);
        try {
            if (text == null) {
                if (goBackend.getState(tunnel) == Tunnel.State.UP) {
                    goBackend.setState(tunnel, Tunnel.State.DOWN, null);
                }
                appliedConfig = null;
            } else {
                Config config = Config.parse(new BufferedReader(new StringReader(text)));
                // setState(UP) with a changed config restarts the tunnel; skip it when nothing changed
                if (!text.equals(appliedConfig) || goBackend.getState(tunnel) != Tunnel.State.UP) {
                    goBackend.setState(tunnel, Tunnel.State.UP, config);
                    appliedConfig = text;
                }
            }
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            appliedConfig = null;
            Log.w(Const.LOG_TAG, "VPN: apply failed: " + error);
        }
        lastError = error;
        writeStatus(context);
        executor.schedule(new Runnable() {
            @Override
            public void run() {
                writeStatus(context);
            }
        }, STATUS_REFRESH_S, TimeUnit.SECONDS);
    }

    private static void writeStatus(Context context) {
        try {
            GoBackend goBackend = getBackend(context);
            JSONObject status = new JSONObject();
            Tunnel.State state = goBackend.getState(tunnel);
            status.put("state", state.name());
            status.put("configured", VpnFiles.readConfig(context) != null);
            if (lastError != null) {
                status.put("error", lastError);
            }
            if (state == Tunnel.State.UP) {
                Statistics stats = goBackend.getStatistics(tunnel);
                long handshake = 0;
                for (com.wireguard.crypto.Key key : stats.peers()) {
                    Statistics.PeerStats peer = stats.peer(key);
                    if (peer != null && peer.latestHandshakeEpochMillis() > handshake) {
                        handshake = peer.latestHandshakeEpochMillis();
                    }
                }
                status.put("handshake", handshake);
                status.put("rx", stats.totalRx());
                status.put("tx", stats.totalTx());
            }
            status.put("time", System.currentTimeMillis());
            VpnFiles.writeStatus(context, status);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "VPN: cannot write status: " + e.getMessage());
        }
    }
}
