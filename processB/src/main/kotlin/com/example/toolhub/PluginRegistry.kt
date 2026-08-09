package com.example.toolhub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.Handler
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 설치된 플러그인(C)들을 탐색해서 authority -> 패키지명만 캐싱한다 (순수
 * discovery). 권한 관련 정보는 다루지 않는다 — B는 권한 검사를 하지 않고,
 * "이 액션에 실제로 필요한 권한이 뭔지, 체인의 각 링크가 그걸 갖고 있는지"는
 * C가 자기 자신의 attributionContext로 스스로 판단한다. 예전에는 여기서
 * "__required_permissions"를 C에 물어봐서 캐싱하고 B가 사전 검사를 했지만,
 * B의 역할을 "체인을 조립해서 넘겨주는 라우터"로 최소화하기 위해 그 경로를
 * 전부 제거했다.
 *
 * 캐시를 채우는 경로는 두 가지다:
 *  1. 부팅 시 — BootCompletedReceiver가 ToolHubService를 startService()로
 *     깨우면 onCreate() -> start()에서 초기 스캔이 돈다. A가 아직 한 번도
 *     bind하지 않은 상태에서도 캐시가 미리 채워진다.
 *  2. 패키지 변경 시 — 설치/삭제/갱신 브로드캐스트를 받아 캐시를 갱신한다.
 * 두 경로 모두 결국 같은 refreshAll()을 탄다.
 *
 * 플러그인은 PLUGIN_PROVIDER_PERMISSION으로 보호된 ContentProvider를
 * 노출해야 발견 대상이 된다.
 *
 * refreshAll()은 설치된 패키지 전체를 순회하는 PackageManager 조회다 — IPC를
 * 거는 건 아니지만(더 이상 C를 호출하지 않는다) 패키지가 많으면 여전히 느릴
 * 수 있어서, 호출부(ToolHubService)의 메인 스레드를 막지 않도록 이 클래스에
 * 넘겨받은 handler 스레드에서만 실행되게 한다 — start()의 초기 스캔은
 * handler.post로, 브로드캐스트 수신도 registerReceiver에 handler를 지정해
 * onReceive() 자체가 그 스레드에서 돌게 만든다.
 */
class PluginRegistry(private val context: Context, private val handler: Handler) {

    companion object {
        private const val TAG = "PluginRegistry"

        /** C가 provider에 선언해야 하는 보호 권한. 이게 없으면 발견 대상에서 제외한다. */
        const val PLUGIN_PROVIDER_PERMISSION = "com.example.toolhub.permission.BIND_PLUGIN"
    }

    data class PluginInfo(
        val authority: String,
        val packageName: String
    )

    private val cache = ConcurrentHashMap<String, PluginInfo>() // authority -> info

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            // handler 스레드에서 등록했으므로(start() 참고) 이 콜백 자체가
            // 이미 메인 스레드가 아니다.
            val pkg = intent.data?.schemeSpecificPart ?: return
            Log.i(TAG, "package changed: $pkg (${intent.action}), refreshing plugin registry")
            refreshAll()
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(packageChangeReceiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(packageChangeReceiver, filter, null, handler)
        }

        // 초기 스캔도 handler 스레드로 넘긴다 — start()는 ToolHubService.onCreate()
        // (메인 스레드)에서 호출되므로, 여기서 refreshAll()을 직접 부르면 그
        // 비용을 메인 스레드가 그대로 떠안는다.
        handler.post { refreshAll() }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(packageChangeReceiver) }
    }

    fun getPluginInfo(authority: String): PluginInfo? = cache[authority]

    /** 설치된 provider 중 PLUGIN_PROVIDER_PERMISSION으로 보호된 것을 전부 다시 찾는다. */
    fun refreshAll() {
        val pm = context.packageManager
        val discovered = mutableListOf<ProviderInfo>()

        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(PackageManager.GET_PROVIDERS)
        for (pkgInfo in packages) {
            val providers = pkgInfo.providers ?: continue
            for (provider in providers) {
                if (provider.readPermission == PLUGIN_PROVIDER_PERMISSION ||
                    provider.writePermission == PLUGIN_PROVIDER_PERMISSION
                ) {
                    discovered.add(provider)
                }
            }
        }

        val discoveredAuthorities = discovered.map { it.authority }.toSet()
        cache.keys.retainAll(discoveredAuthorities)

        for (provider in discovered) {
            cache[provider.authority] = PluginInfo(provider.authority, provider.packageName)
        }
    }
}
