# 发布检查清单

> 最后更新：2026-09-18
> 当前状态：**未达发布条件**。以下为 v1.0 上架 Google Play 前的完整检查项。

---

## 0. 当前阻塞项（P0，必须先解决）

| # | 项 | 现状 | 需要做什么 |
|---|----|------|-----------|
| 1 | **签名** | `app/build.gradle.kts` release 用 `signingConfigs.getByName("debug")` | 生成 release keystore，配置 `keystore.properties`（已 gitignore），改 release signingConfig |
| 2 | **版本控制** | 仓库无 `.git` | `git init` + 首次提交 + 建远端 |
| 3 | **收录未实现的占位模块** | `:app` 未依赖 `:data:*` 与 6 个 feature 占位模块 | 要么接线，要么从上架范围移除 |
| 4 | **国际化** | UI 文案大量硬编码中文 | 全部抽到 `stringResource()`，补 `values-zh` |
| 5 | **OSM 署名** | 未展示 | 地图页角落加「© OpenStreetMap contributors」 |
| 6 | **搜索防抖** | 每次键入即请求 Nominatim | 补 250 ms 防抖（同时是 §5.1 验收项） |
| 7 | **前台服务** | Manifest 声明了 `FOREGROUND_SERVICE_LOCATION` 权限，但无 Service 实现 | 实现 Foreground Service（Android 14 需 `foregroundServiceType="location"`） |

---

## 1. 构建与产物

- [ ] `./gradlew clean :app:bundleRelease` 通过，产出 `.aab`
- [ ] `versionCode` / `versionName` 已递增
- [ ] `isMinifyEnabled = true` + `isShrinkResources = true`（已开启）
- [ ] `proguard-rules.pro` 覆盖 Hilt / Retrofit / Room / Kotlin Serialization / osmdroid 的 keep 规则
- [ ] Release 反编译验证：无硬编码密钥、日志已剥离
- [ ] `./gradlew test` 全绿
- [ ] Lint 无 error：`./gradlew :app:lintRelease`

## 2. 签名与发布链路

- [ ] Release keystore 已生成并安全备份（丢失即无法更新应用）
- [ ] `keystore.properties` 已加入 `.gitignore`（已包含）
- [ ] Play App Signing 已启用
- [ ] 内部测试轨道 → 封闭测试 → 公开测试 → 生产的推进计划已定
- [ ] `ANDROID_HOME` / `sdk.dir` 在 CI 环境可复现

## 3. 权限与合规

- [ ] Manifest 权限逐项有合理用途说明：

  | 权限 | 用途 | 状态 |
  |------|------|------|
  | `INTERNET` | 下载地图瓦片、地址搜索 | ✅ 必需 |
  | `ACCESS_NETWORK_STATE` | 网络可用性判断 | ✅ |
  | `ACCESS_FINE_LOCATION` | 注入测试位置（必需） | ✅ 核心 |
  | `ACCESS_COARSE_LOCATION` | 同上（部分流程降级） | ✅ |
  | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` | 后台保活 | ⚠️ 服务未实现 |
  | `POST_NOTIFICATIONS` | Android 13+ 前台服务通知 | ⚠️ 服务未实现 |
  | `ACCESS_MOCK_LOCATION` | 国内 ROM「选择模拟位置应用」列表过滤条件（已 deprecated，用 `tools:ignore="MockLocation"` 抑制） | ⚠️ 需在隐私政策中说明 |

- [ ] `POST_NOTIFICATIONS` 在 Android 13+ 做运行时申请，拒绝时降级为无通知保活
- [ ] Android 14 前台服务 `foregroundServiceType="location"` + `<uses-permission ... FOREGROUND_SERVICE_LOCATION/>`（权限已声明，服务待实现）
- [ ] Android 14 所有动态注册 `BroadcastReceiver` 显式声明 `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED`
- [ ] Android 12+ 如使用精确闹钟，按需申请 `SCHEDULE_EXACT_ALARM` 或降级 `setInexactRepeating`

## 4. Play Store 上架材料

- [ ] **Data Safety 表**：位置数据仅本地存储、不上传、不共享 —— 需与实际行为逐项核对
- [ ] **隐私政策 URL**（自建静态页），需明确说明：
  - 位置数据仅在本机使用
  - 地址搜索会把用户输入的**关键词**发送到 `nominatim.openstreetmap.org`（**必须在隐私政策中披露**）
  - 地图瓦片请求会访问 OSM 瓦片服务器
- [ ] 应用描述：明确标注用途为「应用测试 / 隐私保护」，**避免出现「打卡」「绕过定位」等敏感词**
- [ ] 目标用户年龄分级
- [ ] 应用类别：工具（Tools），非游戏
- [ ] 截图与宣传图（手机 + 平板）
- [ ] 打开 OSM 署名（地图页 + 关于页）

## 5. 质量与非功能

- [ ] 冷启动 < 1.5 s（Pixel 6 参考口径）
- [ ] 静止态内存 < 150 MB
- [ ] 后台模拟 1 小时耗电 < 8%（Pixel 6 参考口径）
- [ ] Google Play Pre-launch 报告无 P0 Crash
- [ ] 崩溃上报已接入（Crashlytics / Sentry），且**严禁上报位置字段**，用户可在设置页关闭
- [ ] 兼容性矩阵真机验证：

  | 设备 | 系统 | 状态 |
  |------|------|------|
  | Pixel 6 / 8 | Android 14 | ⬜ |
  | Samsung S23 | One UI 6 | ⬜ |
  | Xiaomi 14 | HyperOS | ⬜ |
  | Huawei P60 | HarmonyOS | ⬜ 需文档化限制 |

- [ ] 测试覆盖：
  - [ ] 路线插值算法单测（±1 m）
  - [ ] Haversine 与官方 SDK 误差 < 0.1%（已有 `GeoUtilsTest`）
  - [ ] Bearing 典型角度用例（已有）
  - [ ] 抖动范围 ±3 m（已有 `JitterGeneratorTest`）
  - [ ] Compose UI 测试：主界面选点流程、收藏空状态、引导页完整/跳过流程
  - [ ] 集成测试：真实第三方 App 验证 `isFromMockProvider == true`

## 6. 发布后

- [ ] 埋点/反馈入口可用（`feature-diagnostics` 诊断页）
- [ ] 灰度发布与回滚预案
- [ ] 商店评价监控与回复
