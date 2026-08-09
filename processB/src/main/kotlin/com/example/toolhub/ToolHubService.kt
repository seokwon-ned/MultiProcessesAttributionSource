package com.example.toolhub

import android.app.Service
import android.content.AttributionSource
import android.content.ContextParams
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.example.toolhub.common.ResultContract
import java.util.concurrent.ConcurrentHashMap

/**
 * 프로세스 B의 진입점 및 서비스 라이프사이클 관리.
 *
 * 책임 위임:
 * - PluginMetadataProvider: C의 메타데이터 조회/캐싱
 * - AuthorizationPreChecker: 권한 사전검사 및 캐싱
 * - PluginExecutor: C의 플러그인 실행
 * - DispatchCoordinator: 요청 흐름 조율
 *
 * execute()는 바인더 스레드에서:
 *  1. callerSource.enforceCallingUid() — 호출자 검증
 *  2. 체인 깊이 확인
 *  3. 시스템 등록 체인으로 재조립
 *  4. RequestRegistry 등록 + linkToDeath
 *  5. 핸들러 스레드로 위임
 */
class ToolHubService : Service() {

    companion object {
        private const val TAG = "ToolHubService"
    }

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var requestRegistry: RequestRegistry
    private lateinit var pluginRegistry: PluginRegistry

    // 기능별 컴포넌트
    private lateinit var metadataProvider: PluginMetadataProvider
    private lateinit var authChecker: AuthorizationPreChecker
    private lateinit var executor: PluginExecutor
    private lateinit var dispatcher: DispatchCoordinator

    /** 콜백 바인더당 한 번만 linkToDeath 하기 위한 추적 맵. */
    private val linkedDeaths = ConcurrentHashMap<IBinder, IBinder.DeathRecipient>()

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("ToolHubDispatch").apply { start() }
        handler = Handler(handlerThread.looper)
        requestRegistry = RequestRegistry(handler)
        pluginRegistry = PluginRegistry(this, handler).apply {
            onRefresh = { metadataProvider.clearCache() }
            start()
        }

        // 컴포넌트 초기화
        metadataProvider = PluginMetadataProvider(contentResolver)
        authChecker = AuthorizationPreChecker(packageManager, getSystemService(android.app.AppOpsManager::class.java), metadataProvider)
        executor = PluginExecutor(contentResolver)
        dispatcher = DispatchCoordinator(packageManager, metadataProvider, authChecker, executor, requestRegistry)
    }

    override fun onDestroy() {
        pluginRegistry.stop()
        handlerThread.quitSafely()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IToolHub.Stub() {

        override fun execute(
            actionId: String,
            args: Bundle,
            reverse: Boolean,
            callerSource: AttributionSource,
            callback: IToolHubCallback?
        ): String {
            callerSource.enforceCallingUid()

            require(callerSource.next == null) {
                "callerSource must not carry a pre-built chain (next must be null)"
            }

            val chained = createContext(
                ContextParams.Builder().setNextAttributionSource(callerSource).build()
            ).attributionSource

            check(!requestRegistry.isSaturated()) { "too many in-flight requests" }

            val entry = requestRegistry.register(actionId, args, reverse, chained, callback)

            if (callback != null) {
                linkCallbackDeath(callback, callerSource.uid)
            }

            handler.post { dispatcher.dispatch(entry) }

            return entry.requestId
        }

        override fun cancel(requestId: String) {
            requestRegistry.cancel(requestId)
        }

        override fun describe(actionId: String): Bundle {
            val (authority, actionName) = splitActionId(actionId)
                ?: return ResultContract.error("malformed actionId: $actionId")
            val actions = metadataProvider.getActionsMetadata(authority)
                ?: return ResultContract.error("describe failed for authority '$authority'")
            val action = actions.getBundle(actionName)
                ?: return ResultContract.error("unknown action: $actionName")
            return ResultContract.success(Bundle(action).apply {
                putString(ResultContract.KEY_ACTION_NAME, actionName)
            })
        }

        override fun authorizationPreCheck(actionId: String): Bundle {
            val cached = authChecker.getCachedResult(actionId)
            if (cached != null) {
                return cached
            }

            val (authority, actionName) = splitActionId(actionId)
                ?: return ResultContract.error("malformed actionId: $actionId")
            val providerInfo = packageManager.resolveContentProvider(authority, 0)
            if (providerInfo == null) {
                return ResultContract.error("no plugin found for authority '$authority'")
            }

            val denial = authChecker.checkAuthorizationPreConditions(authority, actionName, attributionSource)
            val result = denial ?: ResultContract.success(Bundle())

            authChecker.cacheResult(actionId, result)
            return result
        }
    }

    fun executeAsHub(
        actionId: String,
        args: Bundle,
        reverse: Boolean = false,
        onResult: ((Bundle) -> Unit)? = null
    ): String? {
        if (requestRegistry.isSaturated()) {
            onResult?.invoke(ResultContract.error("too many in-flight requests"))
            return null
        }
        val callback = onResult?.let { fn ->
            object : IToolHubCallback.Stub() {
                override fun onResult(requestId: String, result: Bundle) = fn(result)
            }
        }
        val entry = requestRegistry.register(actionId, args, reverse, attributionSource, callback)
        handler.post { dispatcher.dispatch(entry) }
        return entry.requestId
    }

    private fun linkCallbackDeath(callback: IToolHubCallback, callerUid: Int) {
        val callbackBinder = callback.asBinder()
        linkedDeaths.computeIfAbsent(callbackBinder) {
            val recipient = IBinder.DeathRecipient {
                Log.i(TAG, "caller uid=$callerUid died, cancelling its in-flight requests")
                requestRegistry.cancelAllFrom(callerUid)
                linkedDeaths.remove(callbackBinder)
            }
            try {
                callbackBinder.linkToDeath(recipient, 0)
            } catch (e: android.os.RemoteException) {
                requestRegistry.cancelAllFrom(callerUid)
            }
            recipient
        }
    }

    private fun splitActionId(actionId: String): Pair<String, String>? {
        val idx = actionId.indexOf('/')
        if (idx <= 0 || idx == actionId.length - 1) return null
        return actionId.substring(0, idx) to actionId.substring(idx + 1)
    }
}
