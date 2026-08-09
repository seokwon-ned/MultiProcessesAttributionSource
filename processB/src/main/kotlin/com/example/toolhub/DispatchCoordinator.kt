package com.example.toolhub

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.example.toolhub.common.ResultContract

/**
 * 전체 요청 처리 흐름 조율.
 * actionId 파싱 → 플러그인 확인 → 사전검사 → 권한 검사 → 실행 → 결과 등록
 */
class DispatchCoordinator(
    private val packageManager: PackageManager,
    private val metadataProvider: PluginMetadataProvider,
    private val authChecker: AuthorizationPreChecker,
    private val executor: PluginExecutor,
    private val requestRegistry: RequestRegistry
) {
    companion object {
        private const val TAG = "DispatchCoordinator"
    }

    fun dispatch(entry: RequestRegistry.Entry) {
        val (authority, actionName) = splitActionId(entry.actionId) ?: run {
            requestRegistry.complete(
                entry.requestId,
                ResultContract.error("malformed actionId: ${entry.actionId}")
            )
            return
        }

        val providerInfo = packageManager.resolveContentProvider(authority, 0)
        if (providerInfo == null) {
            requestRegistry.complete(
                entry.requestId,
                ResultContract.error("no plugin found for authority '$authority'")
            )
            return
        }

        // 캐시된 사전검사 결과 확인
        val cachedAuthCheck = authChecker.getCachedResult(entry.actionId)
        if (cachedAuthCheck != null && cachedAuthCheck.getString(ResultContract.KEY_STATUS) != ResultContract.STATUS_SUCCESS) {
            requestRegistry.complete(entry.requestId, cachedAuthCheck)
            return
        }

        // 권한 사전검사
        val denial = authChecker.checkAuthorizationPreConditions(authority, actionName, entry.chainedSource)
        if (denial != null) {
            authChecker.cacheResult(entry.actionId, denial)
            requestRegistry.complete(entry.requestId, denial)
            return
        }

        // 플러그인 실행
        val result = executor.execute(authority, actionName, entry.chainedSource, entry.args, entry.reverse)
        requestRegistry.complete(entry.requestId, result)
    }

    private fun splitActionId(actionId: String): Pair<String, String>? {
        val idx = actionId.indexOf('/')
        if (idx <= 0 || idx == actionId.length - 1) return null
        return actionId.substring(0, idx) to actionId.substring(idx + 1)
    }
}
