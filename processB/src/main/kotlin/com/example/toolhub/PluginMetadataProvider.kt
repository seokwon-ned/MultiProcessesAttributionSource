package com.example.toolhub

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.example.toolhub.common.ResultContract
import java.util.concurrent.ConcurrentHashMap

/**
 * Process C의 describe 응답(액션 메타데이터)을 조회하고 캐싱.
 * 플러그인 패키지 변경 시 PluginRegistry.onRefresh로 무효화된다.
 */
class PluginMetadataProvider(
    private val contentResolver: ContentResolver
) {
    companion object {
        private const val TAG = "PluginMetadataProvider"
    }

    private val metadataCache = ConcurrentHashMap<String, Bundle>()

    /**
     * authority의 액션 메타데이터(KEY_ACTIONS Bundle)를 캐시에서 얻거나,
     * 없으면 C provider를 호출해 채운다. 실패 시 null.
     */
    fun getActionsMetadata(authority: String): Bundle? {
        metadataCache[authority]?.let { return it }
        return try {
            val uri = Uri.parse("content://$authority")
            val response = contentResolver.call(
                uri, ResultContract.METHOD_DESCRIBE_ACTION, null, Bundle()
            ) ?: return null
            if (response.getString(ResultContract.KEY_STATUS) != ResultContract.STATUS_SUCCESS) {
                return null
            }
            val actions = response.getBundle(ResultContract.KEY_PAYLOAD)
                ?.getBundle(ResultContract.KEY_ACTIONS) ?: return null
            metadataCache[authority] = actions
            actions
        } catch (e: Exception) {
            Log.w(TAG, "describe fetch failed for $authority: ${e.message}")
            null
        }
    }

    /**
     * 플러그인 패키지 변경 시 호출 — 캐시 무효화.
     */
    fun clearCache() {
        metadataCache.clear()
    }
}
