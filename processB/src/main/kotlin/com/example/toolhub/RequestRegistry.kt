package com.example.toolhub

import android.content.AttributionSource
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.example.toolhub.common.ResultContract
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 진행 중인 요청을 requestId로 추적한다.
 *
 * execute()가 즉시 반환하고 결과가 나중에 콜백으로 가는 구조라, 요청 상태를
 * 어딘가 붙들고 있어야 한다. 여기서 놓치면 A는 영영 응답을 못 받는다.
 *
 * 콜백은 정확히 한 번만 호출된다(AtomicBoolean). 타임아웃과 정상 완료가
 * 경합해도 중복 통지가 나가지 않는다.
 */
class RequestRegistry(
    private val handler: Handler,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS
) {

    companion object {
        private const val TAG = "RequestRegistry"
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /** 핸들러 큐가 이 이상 밀리면 새 요청을 거절한다. */
        const val MAX_INFLIGHT = 64
    }

    class Entry(
        val requestId: String,
        val actionId: String,
        val args: Bundle,
        /** true면 C에서 onReversePerformAction을 호출해야 하는 요청. */
        val reverse: Boolean,
        /** B -> A 로 조립된, 시스템에 등록된 체인 */
        val chainedSource: AttributionSource,
        val callback: IToolHubCallback?,
        val startedAt: Long = SystemClock.elapsedRealtime()
    ) {
        internal val delivered = AtomicBoolean(false)
    }

    private val inflight = ConcurrentHashMap<String, Entry>()

    val size: Int get() = inflight.size

    fun isSaturated(): Boolean = inflight.size >= MAX_INFLIGHT

    fun register(
        actionId: String,
        args: Bundle,
        reverse: Boolean,
        chainedSource: AttributionSource,
        callback: IToolHubCallback?
    ): Entry {
        val entry = Entry(
            requestId = UUID.randomUUID().toString(),
            actionId = actionId,
            args = args,
            reverse = reverse,
            chainedSource = chainedSource,
            callback = callback
        )
        inflight[entry.requestId] = entry

        handler.postDelayed({
            complete(entry.requestId, ResultContract.error("timed out after ${timeoutMillis}ms"))
        }, entry.requestId, timeoutMillis)

        return entry
    }

    fun get(requestId: String): Entry? = inflight[requestId]

    /**
     * 결과를 A에게 전달하고 항목을 제거한다. 중복 호출은 무시된다.
     */
    fun complete(requestId: String, result: Bundle) {
        val entry = inflight[requestId] ?: return
        if (!entry.delivered.compareAndSet(false, true)) return

        inflight.remove(requestId)
        handler.removeCallbacksAndMessages(requestId)

        val cb = entry.callback ?: return
        try {
            cb.onResult(requestId, result)
        } catch (e: Exception) {
            // A가 죽었거나 바인더가 끊긴 경우. 여기서 죽지 않는다.
            Log.w(TAG, "callback delivery failed for $requestId: ${e.message}")
        }
    }

    fun cancel(requestId: String) {
        complete(requestId, ResultContract.error("cancelled by caller"))
    }

    /** 특정 uid가 등록한 요청 전부 정리 (A 프로세스 사망 시) */
    fun cancelAllFrom(uid: Int) {
        inflight.values
            .filter { it.chainedSource.next?.uid == uid }
            .forEach { complete(it.requestId, ResultContract.error("caller died")) }
    }
}
