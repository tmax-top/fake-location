# 架构说明

> 最后更新：2026-09-18
> 适用于 `versionName = 0.2.0`

---

## 1. 分层与依赖方向

```
┌──────────────────────────────────────────────────────────┐
│  :app            入口 / 根路由 / Hilt 装配                │
└───────────┬──────────────────────────────┬───────────────┘
            │                              │
   ┌────────▼─────────┐          ┌─────────▼──────────┐
   │ :feature:*-*     │          │ :feature:*-*       │   ← feature 层
   │ （UI + ViewModel）│          │                    │
   └────────┬─────────┘          └─────────┬──────────┘
            │                              │
   ┌────────▼──────────────────────────────▼──────────┐
   │  :data:data-repository  →  :data:data-local       │   ← data 层（占位）
   │                         →  :data:data-remote      │
   └────────┬──────────────────────────────┬──────────┘
            │                              │
   ┌────────▼──────────────────────────────▼──────────┐
   │  :core:core-mock    :core:core-common             │   ← core 层
   │  :core:core-ui                                    │
   └───────────────────────────────────────────────────┘
```

**依赖规则（硬约束）：**

| 层 | 可以依赖 | 禁止依赖 |
|----|---------|---------|
| `:app` | 任意 feature / core 模块 | — |
| `:feature:*` | 本层其他 feature 的 **api 包**、`:data:*`、`:core:*` | 直接互相 `implementation` 内部实现 |
| `:data:*` | `:core:core-common` | `:feature:*` |
| `:core:*` | 更低层的 core | `:feature:*`、`:data:*`、`:app` |

当前实际依赖（`app/build.gradle.kts`）：

```
:app → :core:core-ui, :feature:feature-onboarding, :feature:feature-home
:feature:feature-home → :core:core-mock, :core:core-ui, :core:core-common  （后两者由 feature plugin 自动注入）
:feature:feature-onboarding → :core:core-mock, :core:core-ui, :core:core-common
:core:core-mock → :core:core-common
```

> ⚠️ `:data:*` 与 `feature-search/favorites/history/route/settings/diagnostics` 目前**没有被任何模块依赖**，是未接线的空壳。

---

## 2. 构建基建：Convention Plugin

所有构建配置收口在 `build-logic/convention/`，通过 `settings.gradle.kts` 的 `pluginManagement { includeBuild("build-logic") }` 引入。

| Plugin ID | 实现类 | 提供 |
|-----------|-------|------|
| `fakelocation.android.application` | `AndroidApplicationConventionPlugin` | `com.android.application` + kotlin-android + kapt；compileSdk 36 / targetSdk 36 / minSdk 29 / JVM 17 |
| `fakelocation.android.library` | `AndroidLibraryConventionPlugin` | 同上但为 library；额外 `consumerProguardFiles("consumer-rules.pro")` |
| `fakelocation.android.library.compose` | `AndroidLibraryComposeConventionPlugin` | 在上者基础上开启 Compose + 注入 BOM、ui、material3、foundation、icons-extended 等 |
| `fakelocation.android.feature` | `AndroidFeatureConventionPlugin` | `= library.compose` + 自动依赖 `:core:core-ui`、`:core:core-common` |
| `fakelocation.android.hilt` | `AndroidHiltConventionPlugin` | Hilt + kapt + `hilt-android` / `hilt-android-compiler` |
| `fakelocation.jvm.library` | `JvmLibraryConventionPlugin` | 纯 JVM 模块（当前无模块使用） |

**公共 Kotlin 配置**（`KotlinConfig.kt`）：`jvmToolchain(17)`、`jvmTarget = 17`、`-opt-in=kotlin.RequiresOptIn`、`-Xjvm-default=all`、打包排除 `/META-INF/{AL2.0,LGPL2.1}`。

**依赖版本唯一来源**：`gradle/libs.versions.toml`。模块内只写 `libs.xxx`，不写版本号。

---

## 3. 如何新增一个 feature 模块

```bash
mkdir -p feature/feature-xxx/src/main/kotlin/com/fakelocation/feature/xxx
```

`feature/feature-xxx/build.gradle.kts`：

```kotlin
plugins {
    id("fakelocation.android.feature")   // 自带 Compose、core-ui、core-common
    id("fakelocation.android.hilt")      // 需要 Hilt 时加
}

android {
    namespace = "com.fakelocation.feature.xxx"
}

dependencies {
    implementation(project(":core:core-mock"))       // 按需
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.nav.compose)
}
```

然后 **两处必改**：

1. `settings.gradle.kts` → `include(":feature:feature-xxx")`
2. `app/build.gradle.kts` → `implementation(project(":feature:feature-xxx"))`

漏掉第 2 步代码不会进入 APK（历史上容易踩）。

### 页面接入方式

当前 `MainActivity.AppRoot()` 是**二元 switch**（`needsOnboarding` → Onboarding / Home），**没有 NavHost**。新增多页面功能需先建导航图：

- `navigation-compose 2.7.7` 与 `hilt-navigation-compose 1.2.0` 已在 `:app` 声明，可直接用
- `feature` 模块导出 `XxxRoute(onNavigate...: () -> Unit)` 形式，由 `:app` 侧组装导航图，避免 feature 之间互相依赖

---

## 4. core-mock 引擎设计

`MockLocationEngineImpl` 是全项目最核心的实现，设计要点：

| 关注点 | 做法 |
|-------|------|
| 双 provider | 同时注册 `gps` 与 `network`，因为部分 App 只读 `network` |
| 并发串行化 | `Mutex`（而非 `synchronized`），`startAt` / `stop` 互斥且 suspend 友好 |
| 停止时序 | `injectJob?.cancelAndJoin()` 等待注入协程真正退出后再 `removeTestProvider` |
| 残留清理 | 启动前先 remove 已存在的同名 test provider（覆盖上次崩溃残留） |
| 覆盖 real provider | **不做** `if (allProviders.contains(name)) return` 短路 —— 系统 real gps/network 名字就是 `gps`/`network`，短路会导致后续 `setTestProviderEnabled` 抛 "is not a test provider" |
| API 差异 | `LegacyLocationApi` 集中封装：API 31+ 用 `ProviderProperties`，API 29/30 用 deprecated `Criteria` |
| 配置热更新 | `MockConfig.sanitizedCopy()` 把越界值钳制而非抛 `IllegalArgumentException`（避免崩溃） |
| 授权探测 | `MockAuthChecker`：清理残留 → `addTestProvider` 探测 → 再清理。`SecurityException` = 未授权 |

状态机（`MockState`）：`Idle` / `Starting(target)` / `SinglePointRunning(target)` / `RouteRunning(progress)` / `Stopping`。

> `RouteRunning` 已预留但**尚未有代码构造它**，为 v0.4 路线模拟准备。

---

## 5. 数据层现状与落地建议

`:data:*` 三个模块只有一个空 interface，**当前所有数据访问都是「ViewModel 直连」**：

- 引导完成标记 → `feature-onboarding/.../OnboardingPreferences` 直接持有 DataStore
- 地址搜索 → `feature-home/HomeViewModel` 直接 `HttpURLConnection` 打 Nominatim

**落地时的建议路径：**

1. `:data:data-local` 建 Room 数据库（`FavoriteEntity`、`HistoryEntity`）+ DataStore 封装，参照 `OnboardingPreferences` 的 `@Singleton` + `@ApplicationContext` 风格
2. `:data:data-remote` 封装 Nominatim（后续 OSRM 路线）为 Retrofit interface 或保持轻量 HTTP client，注意 Nominatim 使用策略要求设置 `User-Agent`
3. `:data:data-repository` 聚合两边，对外暴露 `Flow` / `suspend` API
4. feature 模块改为依赖 `:data:data-repository`，把 `HomeViewModel` 里的网络逻辑迁出
5. 注意：**`:data:data-remote` 若用 Retrofit，需给 `:app` 补 DI 装配**（当前 `:app` 未依赖 data 层）

---

## 6. 命名与代码约定（现有代码体现出的）

- 包名：`com.fakelocation.<module-path>`，与模块路径一一对应（`:core:core-mock` → `com.fakelocation.core.mock`）
- UI 入口统一命名 `XxxRoute`，状态类 `XxxUiState`，VM 为 `XxxViewModel` + `@HiltViewModel`
- 状态用 `MutableStateFlow` + `combine` → `stateIn(WhileSubscribed(5_000))`，初始值给 `null` 表示「加载中」
- 一次性事件（错误提示）用 `StateFlow<String?>` + `consumeXxx()` 消费模式
- 网络/异常统一 `runCatching` 包裹，错误落 `_error` Flow 而非抛异常
- KDoc 用中文，关键修复点在注释里标注编号（如 `关键修复（S1）`）—— **新增代码请延续这个习惯**，便于回溯决策原因
