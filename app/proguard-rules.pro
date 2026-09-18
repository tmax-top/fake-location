# Project-wide ProGuard rules

# Keep generic signatures
-keepattributes Signature

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager.FragmentContextWrapper

# Kotlin metadata
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }

# Coroutines
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# Google Maps
-keep class com.google.android.gms.maps.** { *; }
-dontwarn com.google.android.gms.maps.**

# Moshi
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }

# 保留所有 android.util.Log 调用（覆盖 proguard-android-optimize.txt 默认的
# -assumenosideeffects 对 Log.v/Log.d 的移除），便于 release 调试。
# 不要删除 -keepclassmembers 部分 —— -keep 只能阻止重命名/移除方法签名，
# 真正阻止 R8 "树摇" 方法体调用需要明确列出每个方法。
-keep class android.util.Log { *; }
-keepclassmembers class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# —— 应用代码 keep 规则 ——
# 根因：Hilt 生成的工厂只 keep ViewModel 的构造器（用于反射创建），
# 构造器之外的所有方法（startMock / stopMock / setPicked / ...）都没有任何
# "被 keep 的代码" 引用，R8 视为 dead code 整个 strip 掉。后果是
# - 方法体里的 Log.d 调用被一并删除，release 包 logcat 看不到任何输出
# - 即使按钮点击事件到了 ViewRootImpl，onClick lambda 调用的方法体也已被砍
#
# 同样原因适用于 @Composable 函数（HomeRouteKt 等）和 MockLocationEngineImpl
# 这种 @Singleton：Hilt 工厂只 keep 构造器，方法体被 strip。

# 1) 所有 HiltViewModel 子类：keep 全部成员（构造器 + 方法 + 字段）
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { *; }

# 2) Mock 模块：所有类所有成员
-keep class com.fakelocation.core.mock.** { *; }
-keepclassmembers class com.fakelocation.core.mock.** { *; }

# 3) Feature 模块：所有 ViewModel / Route / 内部类都保留
#    Composable 函数（HomeRouteKt / OnboardingRouteKt 等）由 Compose 编译器
#    生成的是普通 static 方法，R8 同样会把未保留的方法 strip。
-keep class com.fakelocation.feature.** { *; }
-keepclassmembers class com.fakelocation.feature.** { *; }

# 4) Application 入口
-keep class com.fakelocation.FakeLocationApp { *; }
-keep class com.fakelocation.MainActivity { *; }
