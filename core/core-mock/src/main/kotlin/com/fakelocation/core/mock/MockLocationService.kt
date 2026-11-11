package com.fakelocation.core.mock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * 模拟定位前台服务 —— **只负责进程保活，不负责注入**。
 *
 * ## 为什么必须有它（关键修复 F1）
 *
 * 引擎的注入协程挂在 [MockLocationEngineImpl] 这个 Singleton 的
 * `CoroutineScope` 上，属于**应用进程**。切到高德地图后，本 App 退到后台且
 * 没有任何活跃组件，MIUI / Android 会在内存压力或省电策略下回收进程 ——
 * 进程一死，注入协程随之终止，假位置立刻失效。
 *
 * 这正是「设置好定位 → 打开高德 → 闪一下 → 回到真实定位」的第二半原因：
 * 第一半是授权探测删掉了 gps test provider（见 [MockAuthChecker] 关键修复 A1），
 * 第二半是进程被回收。
 *
 * 有了前台服务，进程优先级提升到「可见/服务级」，切后台期间注入持续。
 *
 * ## 设计取舍
 *
 * - 不持有 Binder、不注入位置、不读 LocationManager —— 职责单一，
 *   避免与引擎的 [MockLocationEngineImpl.mutex] 产生生命周期竞态
 * - `START_NOT_STICKY`：进程真被强杀时不自动拉起（否则会留下一个
 *   「引擎其实没在跑」的幽灵通知）；如需崩溃自恢复，后续再单独做
 * - 通知设为 `IMPORTANCE_LOW` + 静音 + 无震动。系统那条
 *   「正在提供模拟位置」是 AOSP/MIUI 强制的、无法关闭，这里不再增加噪音
 * - 未申请 `POST_NOTIFICATIONS` 时通知不出现在通知栏，但**保活效果不变**
 *   （`startForeground` 本身不依赖通知可见性）
 *
 * ## 与模块的依赖方向
 *
 * 放在 `:core:core-mock` 而非 `:app`，因为启停指令由
 * `:feature:feature-home` 的 `HomeRoute` 发出，feature 层不能反向依赖 app 层。
 * 这里只用 framework API + `android.R` 资源，不引入任何新依赖。
 */
class MockLocationService : Service() {

    override fun onCreate() {
        super.onCreate()
        // 关键修复（F3）：startForeground 在部分 ROM / 权限缺失时会抛
        // SecurityException 或 ForegroundServiceStartNotAllowedException。
        // 不兜住会直接杀掉整个 App 进程 —— 比「没有保活」更糟。
        runCatching {
            createChannel()
            // startForeground 三参数重载自 API 29 起可用（minSdk = 29），
            // targetSdk 34+ 要求显式声明 foregroundServiceType = location。
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            Log.w(TAG, "前台服务已启动，注入进程进入保活状态")
        }.onFailure {
            Log.w(TAG, "startForeground 失败，主动停止服务（不阻断前台注入）", it)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.w(TAG, "前台服务已停止")
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // 用 launcher Intent 回到主界面，避免 core 层反向依赖 :app 的 MainActivity
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
    }

    companion object {
        private const val TAG = "MockLocationService"
        private const val CHANNEL_ID = "mock_location_running"
        private const val CHANNEL_NAME = "模拟定位"
        private const val NOTIFICATION_TITLE = "模拟定位运行中"
        private const val NOTIFICATION_TEXT = "点按返回应用，可停止模拟"
        private const val NOTIFICATION_ID = 1001

        /**
         * 启动保活服务。必须在 **App 处于前台** 时调用（Android 12+ 限制
         * 后台启动前台服务）；当前由 `HomeRoute` 观察引擎运行状态触发。
         */
        fun start(context: Context) {
            // Android 12+ 后台启动前台服务会抛 ForegroundServiceStartNotAllowedException。
            // 当前只在前台点击「开始模拟」时触发，但仍做兜底：保活失败不能连累注入主链路。
            runCatching {
                context.startForegroundService(Intent(context, MockLocationService::class.java))
            }.onFailure {
                Log.w(TAG, "前台服务启动失败（降级为无保活，前台注入仍可用）", it)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MockLocationService::class.java))
        }
    }
}
