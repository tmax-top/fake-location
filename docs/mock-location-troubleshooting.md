# 模拟定位失效排查：闪一下就回真实定位 / 反复弹提醒

> 记录日期：2026-11-11
> 验证设备：Redmi `24122RKC7C`（Android 17 / SDK 37）
> 适用版本：`versionName = 0.2.0` + 本次修复

---

## 1. 用户看到的现象

1. 在 App 里选好点、点「开始模拟」，再打开高德地图 —— **闪一下设定的位置，随即回到真实定位**。
2. 通知栏**一直反复弹**「FakeLocation 正在提供模拟位置」，带声音，且有「关闭模拟定位」按钮。
3. 严重时**完全无法定位**（`dumpsys location` 里 `gps provider: last location=null`）。

---

## 2. 根因：一条因果链，不是单一 bug

| # | 原因 | 现场证据 | 后果 |
|---|------|---------|------|
| R1 | **授权探测删掉了引擎正在用的 provider** | `dumpsys location` 历史里成组的三连：`gps removed mock provider override` / `added` / `removed` | 注入通道失效 |
| R2 | **注入失败被静默吞掉且永不恢复** | `pushLocation()` 用 `runCatching` 吞异常、不返回状态 | 假位置只闪一下，UI 仍显示「运行中」 |
| R3 | **没有前台服务，切后台进程被回收** | `ps -A` 查不到 `com.fakelocation` | 注入协程直接终止 |
| R4 | **只 mock 了 `gps` + `network`，没覆盖 `fused`** | `dumpsys location`：`fused provider last location ... amap=1 / flpProvider=network` | 读 fused 的 App 拿不到假位置 |
| R5 | 系统反滥用设计 | 通知 `pkg=android` / `channel=GPS_STATUS_MONITOR_ID` / `airtimeCount=21` | 「正在提供模拟位置」常驻，**App 无法关闭** |

### R1 的具体触发路径

```
点「开始模拟」→ setupProviders() 注册 gps/network 并注入成功（假位置出现 ← 「闪一下」）
        ↓
切到高德、再切回 App → ON_RESUME → HomeRoute.refreshAuthState()
        ↓
MockAuthChecker.isAuthorized() 用 "gps" 做 remove/add/remove 探测
        ↓
引擎的 gps test provider 被删除 → setTestProviderLocation("gps") 开始抛
IllegalArgumentException
        ↓
R2 让这个失败永久静默 → 回到真实定位
```

**关键点**：`addTestProvider()` 的 appop 校验发生在系统服务入口，**与 provider 名字无关**。
旧代码用 `gps` 做探针纯属无谓的破坏 —— 系统 real gps 和引擎的 test provider 共用这个名字。

### 系统通知为什么「反复弹」

每次 `addTestProvider` / 注入状态变化都会让 MIUI/AOSP 的 `GnssNotificationManager`
重新 post 一次通知。旧实现每次 `ON_RESUME` 都探测一次，`airtimeCount` 就这么攒起来的
（实测 8 秒内不涨，说明不是定时循环，而是**每次状态变化触发**）。

---

## 3. 本次修复清单

| 编号 | 文件 | 改动 |
|------|------|------|
| **A1** | `core-mock/MockAuthChecker.kt` | 探针名改为 `fake_location_auth_probe`，**绝不再碰 gps/network/fused** |
| **A2** | 同上 | 授权结果缓存 500 ms（`SystemClock.elapsedRealtime`），减少 binder 扰动 |
| **A3** | `feature-home/HomeViewModel.kt` | `refreshAuthState()` 在引擎 Running/Starting 时**跳过探测**（能跑就证明有授权） |
| **S3** | `core-mock/MockLocationEngineImpl.kt` | `pushLocation()` 返回 Boolean；连续 2 次失败自动重建 provider 并补推，重建冷却 5 s |
| **C1** | 同上 | 新增**可选** `fused` 通道；注册失败静默降级，绝不影响 gps/network 主链路 |
| **F1** | `core-mock/MockLocationService.kt`（新增） | 前台服务（`foregroundServiceType=location`）保活，切后台注入不断 |
| **F2** | `feature-home/HomeRoute.kt` | 服务严格跟随 `uiState.isRunning` 启停；带 `wasRunning` 守卫，避免 Activity 重建误停 |
| **F3** | `MockLocationService.kt` | `startForeground` / `startForegroundService` 全部 `runCatching` 兜底，保活失败不连累注入主链路 |

### 依赖方向约束

`HomeRoute` 在 `:feature:feature-home`，**不能反向依赖 `:app`**，所以 Service 放在
`:core:core-mock`（与引擎同层），Manifest 由 library 合并进 app：

```
core/core-mock/src/main/AndroidManifest.xml
  <service android:name="com.fakelocation.core.mock.MockLocationService"
           android:exported="false"
           android:foregroundServiceType="location" />
```

权限（`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` / `POST_NOTIFICATIONS`）
在 `:app` Manifest 中，此前已声明。Service 只用 framework API + `android.R` 资源，
**未引入任何新依赖**。

---

## 4. 怎么验证修复是否生效

```bash
D=<adb 设备号>

# 1. 确认服务已合并进 APK
aapt2 dump xmltree app/build/outputs/apk/debug/app-debug.apk --file AndroidManifest.xml \
  | grep -A3 MockLocationService
# 期望：service 节点 + foregroundServiceType=0x00000008 (location)

# 2. 装机并开始模拟，抓引擎日志
adb -s $D logcat -c
adb -s $D install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $D shell am start -n com.fakelocation/.MainActivity
# （在地图上选点 → 点「开始模拟」）
adb -s $D logcat -d | grep -E "MockLocationEngine|MockAuthChecker|MockLocationService"
# 期望：
#   fusedProviderAvailable=true
#   前台服务已启动，注入进程进入保活状态
#   pushLocation failed 出现次数 = 0

# 3. 采样 last location：连续两次，间隔 3 秒
adb -s $D shell dumpsys location | grep -E "last location=Location\[(gps|network|fused)"
# 期望：三个 provider 都是设定坐标，且 et= 时间戳在持续刷新

# 4. 切到高德再切回，检查 provider 是否还在
adb -s $D shell dumpsys location | grep -i "mock provider"
# 期望：不再出现成组的 removed/added/removed 三连

# 5. 检查通知是否还在反复重弹
adb -s $D shell dumpsys notification --noredact | grep "GnssNotificationManager" -A45 \
  | grep -oE "airtimeCount=[0-9]+" | head -1
# 期望：计数不再持续上涨
```

---

## 5. 免 Root 方案的固有限制（**改不了**）

| 限制 | 说明 |
|------|------|
| 系统「正在提供模拟位置」通知 | `pkg=android` / `GnssNotificationManager`，AOSP 反滥用设计，App 无法抑制。带「关闭模拟定位」按钮，**不要点**（点了会撤销授权，需重新去开发者选项选择模拟位置应用） |
| 高德等 App 的自有定位 SDK | AMap Location SDK 走基站 / WiFi / IP 融合定位，不依赖 `LocationManager` 的 gps/network；且可能按 `Location.isFromMockProvider` 主动丢弃 mock 位置。已补 `fused` 通道尽量覆盖，但**无法保证** |
| `isFromMockProvider` 检测 | `setTestProviderLocation` 注入的位置必然被系统标记为 mock，任何 App 都能识别 |
| 应用级反作弊 | 设备指纹、行为画像、服务端 IP 交叉校验，jitter 只解决视觉层真实感 |

**结论**：本次修复解决的是「注入链路被自己打断」这个**代码缺陷**，
能让系统级定位、以及通过 `LocationManager` 读位置的 App 稳定拿到设定坐标。
高德自有 SDK 的反作弊属于机制上限，不是 bug。

---

## 6. 相关文档

- [docs/architecture.md](./architecture.md) — 模块分层与引擎设计
- [docs/implementation-status.md](./implementation-status.md) — 需求 vs 实现逐条对照
- [docs/network-reachability.md](./network-reachability.md) — 地图瓦片 / 地址搜索的 DNS 污染排查
