# FakeLocation — 项目长期记忆

## 项目概况

- **是什么**：Android 虚拟定位 App。基于系统「开发者选项 → 选择模拟位置应用」机制，向 `gps` + `network` 两个 provider 注入伪造坐标。
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

## 核心引擎设计要点（core-mock）

1. 同时注册 `gps` + `network` 两个 test provider（部分 App 只读 network）
2. **不做** `if (allProviders.contains(name)) return` 短路 —— 系统 real gps/network 名字就是 `gps`/`network`，短路会导致后续 `setTestProviderEnabled` 抛 "is not a test provider"
3. 停止时必须 `injectJob?.cancelAndJoin()` 等注入协程真正退出，再 `removeTestProvider`（否则竞态）
4. 启动前清理残留 provider（覆盖上次崩溃）
5. 授权探测（`MockAuthChecker`）：清理残留 → `addTestProvider` 探测 → 再清理，不留痕迹
6. API 差异集中在 `LegacyLocationApi`：API 31+ 用 `ProviderProperties`，API 29/30 用 deprecated `Criteria`
7. `MockConfig.sanitizedCopy()` 钳制越界值而非抛异常
8. `MockState.RouteRunning(progress)` 已预留但**尚无代码构造它**（为 v0.4 路线模拟准备）

## 当前未完状态（截至 2026-09-18）

| 类别 | 状态 |
|------|------|
| 已实现 | `:core:core-common`、`:core:core-mock`、`:core:core-ui`、`:feature:feature-onboarding`、`:feature:feature-home` |
| 空壳占位 | `:data:data-local/remote/repository`、`feature-search/favorites/history/route/settings/diagnostics` —— **均未被 `:app` 依赖** |
| 无 NavHost | `MainActivity.AppRoot()` 只有 onboarding/home 二元 switch |
| 无前台服务 | Manifest 已声明 `FOREGROUND_SERVICE_LOCATION` 权限，但无任何 Service 实现 |
| 无 Git | 仓库未初始化版本控制 |
| Release 签名 | 用 `debug.keystore`，不可上架 |
| 测试 | 仅 2 个纯函数单测（`GeoUtilsTest`、`JitterGeneratorTest`） |

## 文档索引

| 文档 | 内容 |
|------|------|
| `README.md` | 项目简介、快速开始、模块结构、风险 |
| `REQUIREMENTS.md` | 产品需求 + 验收标准（已与实现对齐） |
| `docs/architecture.md` | 模块架构、分层规则、扩展指南、引擎设计 |
| `docs/implementation-status.md` | **需求 vs 实现逐条对照 + 21 项已知代码问题**（最常用） |
| `docs/api-keys.md` | 免 Key 架构说明 + 将来引入 Key 的正确做法 |
| `docs/release-checklist.md` | 上架检查清单 |
| `.codebuddy/specs/<feature>/` | bmad-pilot 工作流的规格目录（00-repo-scan / 01-PRD / 02-架构 / 03-sprint / 04-review） |

## 用户偏好

- 中文沟通，简洁直接，先结论后细节。
- 期望产出含**文件路径 + 行号 + 修改前后对比 + 量化验证结果**。
- 常用简短跟进指令（「继续」「打个包」），信任助手驱动多步流程。
