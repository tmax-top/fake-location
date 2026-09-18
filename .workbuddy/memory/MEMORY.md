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
| `docs/api-keys.md` | 免 Key 架构说明 + 将来引入 Key 的正确做法 |
| `docs/release-checklist.md` | 上架检查清单 |
| `.codebuddy/specs/<feature>/` | bmad-pilot 工作流的规格目录（00-repo-scan / 01-PRD / 02-架构 / 03-sprint / 04-review） |

## 用户偏好

- 中文沟通，简洁直接，先结论后细节。
- 期望产出含**文件路径 + 行号 + 修改前后对比 + 量化验证结果**。
- 常用简短跟进指令（「继续」「打个包」），信任助手驱动多步流程。
