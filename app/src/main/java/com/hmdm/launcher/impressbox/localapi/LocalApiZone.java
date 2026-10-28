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
import android.content.SharedPreferences;

import com.hmdm.launcher.Const;

/**
 * The time zone the player set through the local API. While it is kept, the HMDM configuration's timeZone "auto"
 * leaves automatic time zone off (Utils.setTimeZone), otherwise every configuration sync would undo the player's zone.
 * A fixed zone in the configuration clears it: then the configuration owns the zone and setTimeZone refuses.
 */
public final class LocalApiZone {

    private static final String PREFERENCE = "impressbox_local_api_zone";

    private LocalApiZone() {}

    public static String get(Context context) {
        return prefs(context).getString(PREFERENCE, null);
    }

    static void set(Context context, String zone) {
        prefs(context).edit().putString(PREFERENCE, zone).commit();
    }

    /** The zone Utils.setTimeZone must keep for the configuration's timeZone (LocalApiRules.zoneToKeep), else null. */
    public static String keptFor(Context context, String configZone) {
        return LocalApiRules.zoneToKeep(configZone, get(context));
    }

    public static void clear(Context context) {
        if (get(context) != null) {
            prefs(context).edit().remove(PREFERENCE).commit();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Const.PREFERENCES, Context.MODE_PRIVATE);
    }
}
