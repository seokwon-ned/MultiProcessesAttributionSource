// IToolHubCallback.aidl
package com.example.toolhub;

import android.os.Bundle;

/**
 * Process B -> Process A result notification.
 *
 * Declared oneway so B's handler thread is never blocked by A's processing time.
 */
oneway interface IToolHubCallback {

    /**
     * @param requestId the value execute() returned
     * @param result    a Bundle in ResultContract format
     *                  - KEY_STATUS: "success" | "permission_denied" | "error"
     *                  - on failure, includes KEY_PERMISSION / KEY_DENIED_AT / KEY_PHASE
     */
    void onResult(String requestId, in Bundle result);
}
