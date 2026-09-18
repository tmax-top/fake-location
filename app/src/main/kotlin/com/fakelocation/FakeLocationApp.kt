package com.fakelocation

import android.app.Application
import android.preference.PreferenceManager
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration

@HiltAndroidApp
class FakeLocationApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // osmdroid 初始化 + 瓦片缓存配置
        Configuration.getInstance().apply {
            // userAgentValue 必须设置，否则会被 OSM 服务器拒绝
            userAgentValue = packageName
            load(this@FakeLocationApp, PreferenceManager.getDefaultSharedPreferences(this@FakeLocationApp))

            // 瓦片缓存优化（默认 600MB 对移动端过大，磁盘 IO 也会拖慢地图加载）
            // - 内存缓存：默认 ~1MB，足够（用户视野内的瓦片 + 缓存池）
            // - 磁盘缓存：200MB 上限，150MB 时 trim；足够覆盖一个城市的所有缩放级别
            //   用户在同一区域多次使用后，第二次打开几乎秒开
            tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 150L * 1024 * 1024
        }
    }
}
