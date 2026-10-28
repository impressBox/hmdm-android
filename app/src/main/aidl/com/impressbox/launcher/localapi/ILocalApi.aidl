// impressBox launcher local API.
//
// The player binds to it with the action com.impressbox.launcher.LOCAL_API in the package com.hmdm.launcher.
// Only callers whose signing certificate is on the launcher's allowlist may run commands.
//
// docs/launcher-local-api.json is the contract shared with impress-player, which keeps the same interface in
// android/app/src/main/aidl/com/impressbox/launcher/localapi/ILocalApi.aidl. The contract test in each repository
// checks this file against it. Never reorder or change these methods: add new ones at the end and raise apiVersion.
package com.impressbox.launcher.localapi;

import android.os.Bundle;

interface ILocalApi {
    /** Version of this API: the contract's apiVersion. */
    int getApiVersion();

    /** The commands this launcher can run on this device now: a subset of the contract's commands. */
    List<String> getCapabilities();

    /**
     * Runs one command of the fixed allowlist. The result always holds "code" (int, 0 = OK) and "message"
     * (String); the contract lists the other keys of each command.
     */
    Bundle run(String command, in Bundle args);
}
