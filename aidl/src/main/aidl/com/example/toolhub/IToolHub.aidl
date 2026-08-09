// IToolHub.aidl
package com.example.toolhub;

import android.content.AttributionSource;
import android.os.Bundle;
import com.example.toolhub.IToolHubCallback;

/**
 * Process A (agent) -> Process B (tool hub) interface.
 *
 * execute() returns a requestId immediately and returns. Actual execution
 * runs asynchronously on B's handler thread, and the result is delivered
 * via the callback.
 *
 * callerSource must always be A's own context.getAttributionSource() result.
 * A source assembled directly via AttributionSource.Builder will fail the
 * authenticity check of the 3-hop chain (C -> B -> A).
 */
interface IToolHub {

    /**
     * @param actionId   the action to perform (plugin authority + method)
     * @param args       action arguments
     * @param reverse    if true, C is asked to run onReversePerformAction
     *                   instead of onPerformAction for this actionId
     * @param callerSource A's own AttributionSource (single source, no chain)
     * @param callback   result callback. null means fire-and-forget.
     * @return requestId — used to identify the request in later callbacks.
     */
    String execute(String actionId,
                   in Bundle args,
                   boolean reverse,
                   in AttributionSource callerSource,
                   IToolHubCallback callback);

    /**
     * Cancel an in-flight request. May be ignored if already forwarded to C.
     */
    void cancel(String requestId);
}
