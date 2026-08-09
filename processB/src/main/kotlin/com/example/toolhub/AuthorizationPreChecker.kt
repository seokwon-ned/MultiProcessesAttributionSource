package com.example.toolhub

import android.app.AppOpsManager
import android.content.AttributionSource
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import com.example.toolhub.common.ResultContract
import java.util.concurrent.ConcurrentHashMap

/**
 * Process B의 권한 사전검사 담당 (advisory, C를 깨우지 않음).
 * Layer 2 (grant) + AppOps 상태 빠른 진단 및 캐싱.
 */
class AuthorizationPreChecker(
    private val packageManager: PackageManager,
    private val appOpsManager: AppOpsManager,
    private val metadataProvider: PluginMetadataProvider
) {
    companion object {
        private const val AUTHORIZATION_PRECHECK_CACHE_TTL_MS = 10000L
    }

    private val checkCache = ConcurrentHashMap<String, Pair<Long, Bundle>>()

    /**
     * 권한 사전검사 조건 확인. 캐시된 메타데이터의 권한 목록으로:
     *  1. 체인 각 링크(B 자신 + A)의 grant 확인
     *  2. A의 AppOps 상태 확인
     * 거부는 PHASE_HUB_AUTHORIZATION_PRECHECK로 구분 보고.
     */
    fun checkAuthorizationPreConditions(
        authority: String,
        actionName: String,
        chained: AttributionSource
    ): Bundle? {
        val meta = metadataProvider.getActionsMetadata(authority)?.getBundle(actionName) ?: return null
        val entries = meta.getParcelableArrayList(
            ResultContract.KEY_PERMISSION_ENTRIES, Bundle::class.java
        ) ?: return null

        val links = buildList {
            add(chained)
            chained.next?.let { add(it) }
        }

        for (permEntry in entries) {
            val permission = permEntry.getString(ResultContract.KEY_PERM_NAME) ?: continue
            for (link in links) {
                val pkg = link.packageName ?: continue
                if (packageManager.checkPermission(permission, pkg) != PackageManager.PERMISSION_GRANTED) {
                    return ResultContract.denied(
                        permission, pkg, ResultContract.PHASE_HUB_AUTHORIZATION_PRECHECK,
                        "grant missing (authorization pre-check)", getPermissionType(permission)
                    )
                }
                if (isAppOpsBlocked(permission, link.uid, pkg)) {
                    return ResultContract.denied(
                        permission, pkg, ResultContract.PHASE_HUB_AUTHORIZATION_PRECHECK,
                        "app-op not allowed (one-time grant expired or ignored)",
                        ResultContract.PERMISSION_TYPE_RUNTIME
                    )
                }
            }
        }
        return null
    }

    /**
     * 캐시 결과 조회 (TTL 검증).
     */
    fun getCachedResult(actionId: String): Bundle? {
        val (timestamp, result) = checkCache[actionId] ?: return null
        if (System.currentTimeMillis() - timestamp > AUTHORIZATION_PRECHECK_CACHE_TTL_MS) {
            checkCache.remove(actionId)
            return null
        }
        return result
    }

    /**
     * 결과 캐싱.
     */
    fun cacheResult(actionId: String, result: Bundle) {
        checkCache[actionId] = System.currentTimeMillis() to result
    }

    /**
     * grant는 있지만 AppOps 모드가 실행을 막는 상태인가.
     */
    private fun isAppOpsBlocked(permission: String, uid: Int, packageName: String): Boolean {
        val op = AppOpsManager.permissionToOp(permission) ?: return false
        return try {
            val mode = appOpsManager.unsafeCheckOpNoThrow(op, uid, packageName)
            mode != AppOpsManager.MODE_ALLOWED && mode != AppOpsManager.MODE_DEFAULT
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * dangerous(런타임) 권한이면 runtime, 그 외/조회 실패는 install.
     */
    private fun getPermissionType(permission: String): String = try {
        val info = packageManager.getPermissionInfo(permission, 0)
        if (info.protection == PermissionInfo.PROTECTION_DANGEROUS) {
            ResultContract.PERMISSION_TYPE_RUNTIME
        } else {
            ResultContract.PERMISSION_TYPE_INSTALL
        }
    } catch (e: PackageManager.NameNotFoundException) {
        ResultContract.PERMISSION_TYPE_INSTALL
    }
}
