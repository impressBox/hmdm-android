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

package com.hmdm.launcher.pro;

import android.content.Context;
import android.location.Location;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.db.DatabaseHelper;
import com.hmdm.launcher.db.LocationTable;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.server.ServerService;
import com.hmdm.launcher.server.ServerServiceKeeper;
import com.hmdm.launcher.util.RemoteLogger;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.ResponseBody;
import retrofit2.Response;

/**
 * Location history for the server "devicelocations" plugin.
 * Locations coming from LocationService are thinned out (the minimal interval between stored points
 * is set by the launcher app setting "location_update_time", in seconds), stored in the local
 * database and uploaded in batches. Points that fail to upload stay in the database and are
 * sent together with the next point.
 */
public class LocationUploader {

    private static final int DEFAULT_MIN_INTERVAL_SEC = 60;
    private static final int UPLOAD_BATCH = 50;

    private static LocationUploader instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean uploading = new AtomicBoolean(false);
    private long lastStoredTime = 0;

    public static synchronized LocationUploader getInstance() {
        if (instance == null) {
            instance = new LocationUploader();
        }
        return instance;
    }

    private LocationUploader() {}

    public synchronized void onLocation(Context context, Location location, String provider) {
        if (location == null) {
            return;
        }
        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        if (settingsHelper.getConfig() == null) {
            return;
        }
        long minIntervalMs = getMinIntervalSec(context, settingsHelper) * 1000L;
        if (location.getTime() - lastStoredTime < minIntervalMs) {
            return;
        }
        lastStoredTime = location.getTime();

        final Context appContext = context.getApplicationContext();
        final LocationTable.Location item = new LocationTable.Location(location);
        executor.execute(() -> {
            try {
                LocationTable.insert(DatabaseHelper.instance(appContext).getWritableDatabase(), item);
                LocationTable.deleteOldItems(DatabaseHelper.instance(appContext).getWritableDatabase());
            } catch (Exception e) {
                RemoteLogger.log(appContext, Const.LOG_WARN, "Locations: failed to store: " + e.getMessage());
                return;
            }
            uploadPending(appContext);
        });
    }

    private void uploadPending(Context context) {
        if (!uploading.compareAndSet(false, true)) {
            return;
        }
        try {
            SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
            DatabaseHelper db = DatabaseHelper.instance(context);
            while (true) {
                List<LocationTable.Location> items = LocationTable.select(db.getReadableDatabase(), UPLOAD_BATCH);
                if (items == null || items.isEmpty()) {
                    return;
                }
                if (!send(context, settingsHelper, items)) {
                    RemoteLogger.log(context, Const.LOG_DEBUG, "Locations: upload failed, " + items.size() + " points kept");
                    return;
                }
                LocationTable.delete(db.getWritableDatabase(), items);
                if (items.size() < UPLOAD_BATCH) {
                    return;
                }
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Locations: upload error: " + e.getMessage());
        } finally {
            uploading.set(false);
        }
    }

    private boolean send(Context context, SettingsHelper settingsHelper, List<LocationTable.Location> items) {
        for (ServerService service : new ServerService[] {
                ServerServiceKeeper.getServerServiceInstance(context),
                ServerServiceKeeper.getSecondaryServerServiceInstance(context)}) {
            try {
                Response<ResponseBody> response = service.sendLocations(
                        settingsHelper.getServerProject(), settingsHelper.getDeviceId(), items).execute();
                if (response.isSuccessful()) {
                    return true;
                }
            } catch (Exception e) {
                // Try the secondary server
            }
        }
        return false;
    }

    private static int getMinIntervalSec(Context context, SettingsHelper settingsHelper) {
        String value = settingsHelper.getAppPreference(context.getPackageName(), "location_update_time");
        if (value == null) {
            return DEFAULT_MIN_INTERVAL_SEC;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_MIN_INTERVAL_SEC;
        }
    }
}
