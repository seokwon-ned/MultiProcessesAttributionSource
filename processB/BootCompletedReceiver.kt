package com.example.toolhub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 부팅 완료 시 ToolHubService를 startService()로 깨워서, A가 아직 한 번도
 * bindService()하지 않은 상태여도 플러그인 discovery 캐시(PluginRegistry의
 * authority -> 패키지명 맵)를 미리 로드해둔다.
 *
 * startService()로 띄운 서비스는 bind 여부와 무관하게 계속 떠 있는다
 * (ToolHubService.onStartCommand()가 START_STICKY를 반환). 나중에 A가
 * bindService()해도 이미 떠 있는 같은 인스턴스에 그냥 붙는다 — onCreate()가
 * 두 번 불리지 않는다.
 *
 * 설치 직후처럼 부팅 이후에 앱이 새로 깔린 경우엔 이 브로드캐스트를 못 받는다
 * (다음 재부팅까지). 그런 경우를 대비해 ToolHubService.onCreate() -> start()가
 * 자체적으로도 초기 스캔을 하므로(PluginRegistry 참고), 어느 경로로 서비스가
 * 뜨든 캐시는 항상 채워진다.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        context.startService(Intent(context, ToolHubService::class.java))
    }
}
