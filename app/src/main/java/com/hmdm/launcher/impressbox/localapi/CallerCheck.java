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

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.security.MessageDigest;
import java.util.Set;

/**
 * Decides whether the app behind a binder call may use the local API: one of its signing certificates must be on
 * the allowlist (SHA-256 digests). Android 9+ asks the package manager, which also follows certificate rotation.
 */
final class CallerCheck {

    private final Context context;
    private final Set<String> allowedDigests;

    CallerCheck(Context context, Set<String> allowedDigests) {
        this.context = context;
        this.allowedDigests = allowedDigests;
    }

    boolean isAllowed(int uid) {
        if (allowedDigests.isEmpty()) {
            return false;
        }
        PackageManager pm = context.getPackageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            for (String digest : allowedDigests) {
                if (pm.hasSigningCertificate(uid, LocalApiRules.fromHex(digest), PackageManager.CERT_INPUT_SHA256)) {
                    return true;
                }
            }
            return false;
        }
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null) {
            return false;
        }
        for (String pkg : packages) {
            if (allSignersAllowed(pm, pkg)) {
                return true;
            }
        }
        return false;
    }

    /** The calling app for the log: its package name (or shared uid name), else the uid. */
    String describe(int uid) {
        String name = context.getPackageManager().getNameForUid(uid);
        return name != null ? name : "uid " + uid;
    }

    // Android 8: GET_SIGNATURES; every signer of the package must be allowed
    @SuppressWarnings("deprecation")
    private boolean allSignersAllowed(PackageManager pm, String pkg) {
        try {
            PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            if (info.signatures == null || info.signatures.length == 0) {
                return false;
            }
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (Signature signature : info.signatures) {
                if (!allowedDigests.contains(LocalApiRules.toHex(sha256.digest(signature.toByteArray())))) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
