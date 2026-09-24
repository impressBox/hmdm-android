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

package com.hmdm.launcher.pro.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.pro.AppAccessPolicy;

/**
 * Accessibility-based watchdog: reacts immediately when a window of a forbidden app comes to front.
 * Used when BuildConfig.USE_ACCESSIBILITY is enabled and the user has turned the service on.
 * The system binds the service itself; starting it with startService() has no additional effect.
 */
public class CheckForegroundAppAccessibilityService extends AccessibilityService {

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AppAccessPolicy.register(this);
        Log.i(Const.LOG_TAG, "Accessibility app control connected");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null) {
            return;
        }
        try {
            if (!AppAccessPolicy.isAllowed(this, pkg.toString())) {
                AppAccessPolicy.block(this, pkg.toString(), "accessibility");
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Accessibility app control failed: " + e.getMessage());
        }
    }

    @Override
    public void onInterrupt() {
    }
}
