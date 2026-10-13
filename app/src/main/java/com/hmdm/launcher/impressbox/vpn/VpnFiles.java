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
import com.wireguard.crypto.Key;
import com.wireguard.crypto.KeyPair;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Files shared by the launcher process (VpnKeeper, which reads the server settings) and the ":vpn"
 * process (VpnTunnel, which runs the tunnel). SharedPreferences are not safe across processes, so
 * the two talk through small files in the app's private directory, always replaced atomically.
 *
 * - wg.key     the device's WireGuard private key (base64). Created on the device, never leaves it.
 * - wg.conf    the wg-quick config the tunnel must run; missing = tunnel down.
 * - status.json  written by the tunnel: state, error, latest handshake, traffic.
 * - registration.json  the last answer from the hub's registrar (VpnEnrollment).
 */
public final class VpnFiles {

    public static final String TUNNEL_NAME = "impressbox";

    private static final String DIR = "impressbox-vpn";
    private static final String KEY_FILE = "wg.key";
    private static final String CONFIG_FILE = "wg.conf";
    private static final String STATUS_FILE = "status.json";
    private static final String REGISTRATION_FILE = "registration.json";

    private VpnFiles() {}

    private static File dir(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** The device key pair; the private key is generated on first use. Null only if it cannot be stored. */
    public static synchronized KeyPair getOrCreateKeyPair(Context context) {
        File file = new File(dir(context), KEY_FILE);
        String stored = read(file);
        if (stored != null) {
            try {
                return new KeyPair(Key.fromBase64(stored.trim()));
            } catch (Exception e) {
                Log.w(Const.LOG_TAG, "VPN: stored private key is invalid, generating a new one");
            }
        }
        KeyPair keyPair = new KeyPair();
        if (!write(file, keyPair.getPrivateKey().toBase64())) {
            return null;
        }
        file.setReadable(false, false);
        file.setReadable(true, true);
        return keyPair;
    }

    /** The existing key pair, without creating one (used by the tunnel process). */
    public static synchronized KeyPair getKeyPair(Context context) {
        String stored = read(new File(dir(context), KEY_FILE));
        if (stored == null) {
            return null;
        }
        try {
            return new KeyPair(Key.fromBase64(stored.trim()));
        } catch (Exception e) {
            return null;
        }
    }

    public static String readConfig(Context context) {
        return read(new File(dir(context), CONFIG_FILE));
    }

    /** Writes the config, or removes it when config is null. Returns true when the content changed. */
    public static boolean writeConfig(Context context, String config) {
        File file = new File(dir(context), CONFIG_FILE);
        String old = read(file);
        if (config == null) {
            return old != null && file.delete();
        }
        if (config.equals(old)) {
            return false;
        }
        write(file, config);
        return true;
    }

    public static JSONObject readStatus(Context context) {
        String text = read(new File(dir(context), STATUS_FILE));
        if (text == null) {
            return null;
        }
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
    }

    public static void writeStatus(Context context, JSONObject status) {
        write(new File(dir(context), STATUS_FILE), status.toString());
    }

    public static JSONObject readRegistration(Context context) {
        String text = read(new File(dir(context), REGISTRATION_FILE));
        if (text == null) {
            return null;
        }
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
    }

    public static void writeRegistration(Context context, JSONObject registration) {
        write(new File(dir(context), REGISTRATION_FILE), registration.toString());
    }

    private static String read(File file) {
        if (!file.isFile()) {
            return null;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buffer = new byte[(int) file.length()];
            int offset = 0;
            while (offset < buffer.length) {
                int n = in.read(buffer, offset, buffer.length - offset);
                if (n < 0) {
                    break;
                }
                offset += n;
            }
            return new String(buffer, 0, offset, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } finally {
            close(in);
        }
    }

    // Write to a temporary file and rename it, so the other process never reads half a file
    private static boolean write(File file, String text) {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        OutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            ((FileOutputStream) out).getFD().sync();
            close(out);
            out = null;
            return tmp.renameTo(file);
        } catch (IOException e) {
            Log.w(Const.LOG_TAG, "VPN: cannot write " + file.getName() + ": " + e.getMessage());
            return false;
        } finally {
            close(out);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
}
