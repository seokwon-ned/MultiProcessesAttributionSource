package com.example.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.example.toolhub.IToolHub
import com.example.toolhub.IToolHubCallback
import com.example.toolhub.common.ResultContract
import java.util.concurrent.ConcurrentHashMap

/**
 * Process A (agent) side client.
 *
 * Important: the AttributionSource handed to the tool hub must always be
 * context.attributionSource. A source built directly via
 * AttributionSource.Builder(myUid()) carries a token that isn't registered
 * with the system, so it fails the authenticity check of the 3-hop chain
 * (C -> B -> A). (2-hop chains are allowed unverified, but this is 3-hop.)
 */
class AgentToolClient(private val context: Context) {

    companion object {
        private const val TAG = "AgentToolClient"
        private const val HUB_PACKAGE = "com.example.toolhub"
        private const val HUB_SERVICE = "com.example.toolhub.ToolHubService"
    }

    fun interface ResultListener {
        fun onResult(result: Bundle)
    }

    private var hub: IToolHub? = null
    private val pending = ConcurrentHashMap<String, ResultListener>()

    private val callback = object : IToolHubCallback.Stub() {
        override fun onResult(requestId: String, result: Bundle) {
            // oneway callback, so this arrives on a binder pool thread.
            val listener = pending.remove(requestId)
            if (listener == null) {
                Log.w(TAG, "no listener for $requestId (cancelled or timed out)")
                return
            }
            listener.onResult(result)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            hub = IToolHub.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            hub = null
            // If the connection drops, in-flight requests will never get a response.
            pending.keys.toList().forEach { id ->
                pending.remove(id)?.onResult(
                    ResultContract.error("tool hub disconnected")
                )
            }
        }
    }

    fun bind() {
        val intent = Intent().apply {
            component = ComponentName(HUB_PACKAGE, HUB_SERVICE)
        }
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        runCatching { context.unbindService(connection) }
        hub = null
        pending.clear()
    }

    /**
     * Requests execution of an action. Returns a requestId immediately;
     * the result arrives later via the listener.
     *
     * @param reverse if true, asks C to run onReversePerformAction instead
     *                of onPerformAction for this actionId
     * @return requestId, or null if not bound to the hub
     */
    fun execute(actionId: String, args: Bundle, reverse: Boolean = false, listener: ResultListener): String? {
        val service = hub ?: run {
            listener.onResult(ResultContract.error("tool hub not bound"))
            return null
        }

        return try {
            // System-issued source for this app itself. No chain (next == null).
            val mySource = context.attributionSource

            val requestId = service.execute(actionId, args, reverse, mySource, callback)
            pending[requestId] = listener
            requestId
        } catch (e: Exception) {
            Log.e(TAG, "execute failed", e)
            listener.onResult(ResultContract.error(e.message))
            null
        }
    }

    fun cancel(requestId: String) {
        pending.remove(requestId)
        runCatching { hub?.cancel(requestId) }
    }

    /**
     * Example of how to interpret a result.
     *
     * Even a permission denial demands completely different handling depending
     * on the cause:
     *  - PHASE_CHAIN            -> a code bug. Requesting permission is pointless.
     *  - deniedAt == my package -> show the runtime permission request UI.
     *  - deniedAt == hub/plugin -> a deployment issue. Nothing the user can do.
     */
    fun describe(result: Bundle): String = when (result.getString(ResultContract.KEY_STATUS)) {
        ResultContract.STATUS_SUCCESS -> "성공"

        ResultContract.STATUS_UNTRUSTED_CHAIN ->
            "어트리뷰션 체인 무효 (코드 버그): ${result.getString(ResultContract.KEY_MESSAGE)}"

        ResultContract.STATUS_PROTOCOL_INCOMPATIBLE ->
            "허브/플러그인 버전 호환 문제 (사용자가 할 수 있는 게 없음, 업데이트 필요): " +
                result.getString(ResultContract.KEY_MESSAGE)

        ResultContract.STATUS_PERMISSION_DENIED -> {
            val perm = result.getString(ResultContract.KEY_PERMISSION)
            val at = result.getString(ResultContract.KEY_DENIED_AT)
            if (ResultContract.isActionableByCaller(result, context.packageName)) {
                "권한 요청 필요: $perm"
            } else {
                "$at 가 $perm 권한을 보유하지 않음 (배포 구성 문제)"
            }
        }

        else -> "오류: ${result.getString(ResultContract.KEY_MESSAGE)}"
    }
}
