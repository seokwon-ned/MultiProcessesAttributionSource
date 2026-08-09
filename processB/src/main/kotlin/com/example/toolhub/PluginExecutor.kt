package com.example.toolhub

import android.content.AttributionSource
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.example.toolhub.common.ResultContract

/**
 * Process C의 ContentProvider를 통해 플러그인 액션 실행.
 */
class PluginExecutor(
    private val contentResolver: ContentResolver
) {
    companion object {
        private const val TAG = "PluginExecutor"
    }

    /**
     * 플러그인 실행. B가 C의 PluginContentProvider를 호출하여
     * 최종 T∧P∧U∧V 인가 파이프라인을 거친다.
     */
    fun execute(
        authority: String,
        actionName: String,
        chainedSource: AttributionSource,
        args: Bundle,
        reverse: Boolean
    ): Bundle {
        val extras = Bundle().apply {
            putParcelable(ResultContract.KEY_ATTRIBUTION_SOURCE, chainedSource)
            putBundle(ResultContract.KEY_ARGS, args)
        }

        val method = if (reverse) {
            ResultContract.METHOD_REVERSE_PERFORM_ACTION
        } else {
            ResultContract.METHOD_PERFORM_ACTION
        }

        return try {
            val uri = Uri.parse("content://$authority")
            contentResolver.call(uri, method, actionName, extras)
                ?: ResultContract.error("plugin returned no result")
        } catch (e: SecurityException) {
            ResultContract.denied(null, authority, ResultContract.PHASE_RUNTIME, e.message)
        } catch (e: Exception) {
            Log.e(TAG, "plugin call failed for $authority/$actionName", e)
            ResultContract.error(e.message)
        }
    }
}
