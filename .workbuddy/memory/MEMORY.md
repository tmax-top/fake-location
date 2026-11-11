# FakeLocation — 项目长期记忆

## 项目概况

- **是什么**：Android 虚拟定位 App。基于系统「开发者选项 → 选择模拟位置应用」机制，向 `gps` + `network` + 可选 `fused` 三个 provider 注入伪造坐标；前台服务保活。
- **包名**：`com.fakelocation`；当前 `versionName = 0.2.0`
- **技术栈**：Kotlin 1.9.24 · Jetpack Compose + Material3 · Hilt 2.51.1（kapt）· osmdroid 6.1.20 · DataStore · AGP 8.5.2
- **SDK**：compileSdk 36 / targetSdk 36 / minSdk 29 / JVM 17
- **免 Key**：地图用 osmdroid（OSM 瓦片），地址搜索用 Nominatim 公共 API（`HttpURLConnection` 手写解析）。**不需要任何 API Key。**

## 架构约定（必须遵守）

- **模块分层**：`:app` → `:feature:*` → `:data:*` → `:core:*`。依赖方向不可逆。
- **构建配置全部走 Convention Plugin**（`build-logic/convention/`），模块 build.gradle.kts 里不写 SDK 版本、不写 Compose 配置。
  - 新 feature 模块直接用 `id("fakelocation.android.feature")` + `id("fakelocation.android.hilt")`
- **依赖版本唯一来源**：`gradle/libs.versions.toml`。`settings.gradle.kts` 启用 `FAIL_ON_PROJECT_REPOS`，禁止模块内写 `repositories {}`。
- **新增模块需改两处**：`settings.gradle.kts` 的 `include` + `app/build.gradle.kts` 的 `implementation(project(...))`。**漏掉第二处代码不会进 APK。**
- **源码目录**：`src/main/kotlin/`（不是 `src/main/java/`），包名与模块路径一一对应。
- **命名**：UI 入口 `XxxRoute`，状态 `XxxUiState`，VM `XxxViewModel` + `@HiltViewModel`。
- **状态模式**：`MutableStateFlow` + `combine` → `stateIn(WhileSubscribed(5_000))`；一次性事件用 `StateFlow<String?>` + `consumeXxx()` 消费。
- **注释风格**：KDoc 用中文，关键修复点在注释标注编号（如 `关键修复（S1）`）——新增代码请延续。

## 版本控制

- **远端**：`fake-location` → `git@github.com:tmax-top/fake-location.git`；分支 `master`，跟踪 `fake-location/master`
- **基线提交**：`8f67bd3` "chore: 导入 v0.2.0 完整工程基线"（101 files, 4957 insertions）
- **⚠️ `.gitignore` 铁律**：构建产物用**不带前导斜杠**的 `build/` 和 `.gradle/`。
  绝对不要写 `*/build/` 或 `/*/build/` —— gitignore 里带 `/` 的模式锚定根目录且 `*` 不跨 `/`，
  这类写法只能匹配**一层**目录，`core/*/build/`、`feature/*/build/` 会全部漏掉
  （曾导致 3581 个待提交文件，其中 3534 个是编译产物）。
- **⚠️ 全局配置坑**：`~/.gitignore_global` 含一条 `.gitignore` 规则，会让**所有仓库**忽略自己的
  `.gitignore`，使其不出现在 untracked 列表。需要 `git add -f .gitignore` 绕过。
- `gradlew` 以 100755 提交（可执行位正常）；项目**缺 `gradlew.bat`**，Windows 下无法用 wrapper 构建。
- `.codebuddy/specs/` 与 `.workbuddy/memory/` 纳入版本控制（非缓存，有价值）。
- **⚠️ 推送坑（2026-11-11）**：`github.com:22` 在国内会 `Operation timed out`，
  但 `ssh.github.com:443`（GitHub 官方 SSH-over-HTTPS）通。推送用临时 URL 改写，不改 remote：
  `git -c url."ssh://git@ssh.github.com:443/".insteadOf="git@github.com:" push fake-location master`
  首次连 443 需 `-o StrictHostKeyChecking=accept-new` 写入 known_hosts（已写入）。
  `git ls-remote` 同样要带这个 `-c` 参数。

## 外部服务依赖（国内网络，重要）

- **`*.openstreetmap.org` 在国内存在 DNS 污染** —— 会解析到不可达 IP（甚至 Facebook 的 IP 段）。
  但**链路本身是通的**（直连正确的 `146.75.45.91:443` 返回 TCP-OK），问题纯在 DNS。
- **地图瓦片**：`feature-home/.../map/OsmTileSources.kt` 维护 3 个候选源，启动时探测取首个可达：
  `a.tile.openstreetmap.fr/hot/`（实装默认）→ `a.tile.openstreetmap.de/` → 官方（兜底）。
- **地址搜索**：Photon（`photon.komoot.io`，主源）→ Nominatim（兜底）。双源都在 `HomeViewModel.remoteSearch()`。
- ⚠️ **Photon 只支持 `lang=default/de/en/fr`，传 `lang=zh` 会直接 HTTP 400**。不要加 lang 参数
  —— 不带时返回 OSM 原始名称，中国区数据本身就是中文。
- ⚠️ **绝不要为国内速度换成高德/腾讯瓦片**：那是 GCJ-02 坐标系，会让「地图上选的点」与「实际注入的坐标」偏移 50~500 m。
- 完整实测数据、排查命令见 `docs/network-reachability.md`。

## 真机调试

- 用户有一台常连的测试机：Redmi `24122RKC7C`（Android 17 / SDK 37），`adb` 设备号 **`f6e26d28`**，
  屏幕 `1440x3200`（截图坐标换算：截图宽 488 → 设备 1440，×2.951）。
- 可用手法：`adb logcat -d | grep ...`、`adb exec-out screencap -p > x.png`、
  `adb shell input tap/swipe/text`、`adb shell curl`（设备自带 `/system/bin/curl`）。
- ⚠️ `adb shell input text` **不支持中文**，测中文搜索需换思路（可用 ASCII 查询验证链路）。
- ⚠️ Gradle 有时对 feature 模块报 `compileDebugKotlin UP-TO-DATE`，需以
  「dex 里能否找到新字符串」为准来验证改动是否真的进了 APK。

## 核心引擎设计要点（core-mock）

1. 注册 `gps` + `network` 两个必需 test provider，外加**可选** `fused`（部分 ROM 不允许 override，失败静默降级；高德走 fused）
2. **不做** `if (allProviders.contains(name)) return` 短路 —— 系统 real gps/network 名字就是 `gps`/`network`，短路会导致后续 `setTestProviderEnabled` 抛 "is not a test provider"
3. 停止时必须 `injectJob?.cancelAndJoin()` 等注入协程真正退出，再 `removeTestProvider`（否则竞态）
4. 启动前清理残留 provider（覆盖上次崩溃）
5. ⚠️ **授权探测必须用独立 provider 名 `fake_location_auth_probe`，绝不能用 `gps`** ——
   `addTestProvider` 的 appop 校验与名字无关，用 gps 会删掉引擎正在用的 test provider
   （这是「定位闪一下就回真实」的根因，2026-11-11 修复）。运行中还应跳过探测
6. API 差异集中在 `LegacyLocationApi`：API 31+ 用 `ProviderProperties`，API 29/30 用 deprecated `Criteria`
7. `MockConfig.sanitizedCopy()` 钳制越界值而非抛异常
8. `MockState.RouteRunning(progress)` 已预留但**尚无代码构造它**（为 v0.4 路线模拟准备）
9. `pushLocation()` 返回 Boolean；连续 2 次失败自动 `setupProviders()` 重建 + 补推，冷却 5s（S3 自愈）
10. `MockLocationService` 前台服务保活，放 `:core:core-mock`（feature 层不能反向依赖 `:app`），
    Manifest 由 library 合并；只用 framework API + `android.R`，零新依赖

## 已知运行限制与已修复缺陷（2026-11-11 真机诊断）

- **系统级「正在提供模拟位置」通知无法关闭**：来自 `android` 包 `GnssNotificationManager`
  （channel `GPS_STATUS_MONITOR_ID`，`pkg=android id=11`，带「关闭模拟定位」按钮 →
  `android/GnssService`），`timeout=PT72H`、`isNoisy=true`。只要注册了 test provider
  并注入 mock 位置，AOSP/MIUI 就会常驻提示，App **无权抑制** —— 这是设计如此，不是 bug。
  ⚠️ 用户点「关闭模拟定位」会撤销授权，需重新去开发者选项选择模拟位置应用。
- ✅ **已修（2026-11-11）授权探测破坏注入链路**：原 `MockAuthChecker` 用 `gps` 做
  remove/add/remove 探测，`HomeRoute` 每次 `ON_RESUME` 都调 → 删掉引擎的 gps test provider，
  `pushLocation` 又用 `runCatching` 静默吞异常且永不恢复 → 假位置只闪一下。
  修复：独立探针名 + 500ms 缓存 + 运行中跳过探测 + 注入失败自愈。
  系统通知的「反复重弹」（`airtimeCount=21`）也随之消除。
- ✅ **已修（2026-11-11）切后台注入停止**：新增 `MockLocationService` 前台服务
  （`foregroundServiceType=location`），随 `uiState.isRunning` 启停。
- ✅ **已修（2026-11-11）高德回落真实定位**：旧判断「AMap SDK 走 fused 融合定位、
  免 Root 绕不过」在补齐 `fused` mock 通道后被**真机实测推翻**。当前 gps/network/fused
  三通道都注入 mock 坐标，高德「我的位置」直接跟随设定坐标（上海前滩 → 北京天安门），
  `dumpsys` 中不再出现 `amap=1 / flpProvider=network` 的真实坐标覆盖。
  仍需注意：部分 ROM 不允许 override `fused`，此时可能回落；`isFromMockProvider`
  检测与应用级反作弊依然存在，**不是**所有 App 都能骗过。
- ✅ **真机端到端验证已完成（2026-11-11 11:05，Redmi 24122RKC7C / Android 17）**：
  三通道 last location 均为设定坐标且时间戳持续刷新；切后台 10s 进程与服务存活、
  注入不停；切回 App 后 `10:56:26` 起无任何新的 remove/add 事件（探测副作用消除）；
  `pushLocation failed` = 0；`airtimeCount` 全周期仅 21→25（每次引擎启动 +1，不再反复弹）。
  完整数据表见 `docs/mock-location-troubleshooting.md` 4.1。
- ⚠️ **新发现（待修）运行中改点不生效**：模拟运行中重新选点只更新 UI 的「目标：」，
  引擎仍推旧坐标，必须先停止再重新开始。已记为 `implementation-status.md` 问题 #22。
- 完整因果链、修复清单、adb 验证命令见 `docs/mock-location-troubleshooting.md`。
- 排查命令留存：`adb shell dumpsys notification --noredact | grep -A40 GnssNotificationManager`、
  `adb shell dumpsys location | grep -iE "mock|provider|last location"`、
  `adb shell cmd appops get com.fakelocation android:mock_location`、
  `aapt2 dump xmltree <apk> --file AndroidManifest.xml`（验 Manifest 合并）。

## 当前未完状态（截至 2026-11-11）

| 类别 | 状态 |
|------|------|
| 已实现 | `:core:core-common`、`:core:core-mock`、`:core:core-ui`、`:feature:feature-onboarding`、`:feature:feature-home` |
| 空壳占位 | `:data:data-local/remote/repository`、`feature-search/favorites/history/route/settings/diagnostics` —— **均未被 `:app` 依赖** |
| 无 NavHost | `MainActivity.AppRoot()` 只有 onboarding/home 二元 switch |
| 前台服务 | ✅ 已实现并真机验证通过（2026-11-11）：切后台 10s 进程/服务存活、注入持续 |
| 无 Git | ~~仓库未初始化版本控制~~ → 已建立，远端 `tmax-top/fake-location`，基线 `8f67bd3` |
| Release 签名 | 用 `debug.keystore`，不可上架 |
| 测试 | 仅 2 个纯函数单测（`GeoUtilsTest`、`JitterGeneratorTest`） |

## 文档索引

| 文档 | 内容 |
|------|------|
| `README.md` | 项目简介、快速开始、模块结构、风险 |
| `REQUIREMENTS.md` | 产品需求 + 验收标准（已与实现对齐） |
| `docs/architecture.md` | 模块架构、分层规则、扩展指南、引擎设计 |
| `docs/implementation-status.md` | **需求 vs 实现逐条对照 + 21 项已知代码问题**（最常用） |
| `docs/network-reachability.md` | DNS 污染实测、瓦片/搜索多源策略、排查命令 |
| `docs/mock-location-troubleshooting.md` | **定位失效排查**：闪一下回真实、反复弹提醒、注入链路与固有限制 |
| `docs/api-keys.md` | 免 Key 架构说明 + 将来引入 Key 的正确做法 |
| `docs/release-checklist.md` | 上架检查清单 |
| `.codebuddy/specs/<feature>/` | bmad-pilot 工作流的规格目录（00-repo-scan / 01-PRD / 02-架构 / 03-sprint / 04-review） |

## 用户偏好

- 中文沟通，简洁直接，先结论后细节。
- 期望产出含**文件路径 + 行号 + 修改前后对比 + 量化验证结果**。
- 常用简短跟进指令（「继续」「打个包」），信任助手驱动多步流程。
