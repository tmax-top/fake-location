package com.fakelocation.feature.onboarding

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fakelocation.core.mock.MockAuthChecker
import com.fakelocation.feature.onboarding.data.OnboardingPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 引导页单页内容。
 */
data class OnboardingPage(
    val title: String,
    val body: String,
    val cta: String,
)

/**
 * 引导页 UI 状态。
 */
data class OnboardingUiState(
    val currentPage: Int = 0,
    val totalPages: Int = 3,
    val mockAuthorized: Boolean = false,
    val hasFineLocation: Boolean = false,
    val completed: Boolean = false,
) {
    val isLastPage: Boolean get() = currentPage == totalPages - 1
    val canAdvance: Boolean get() = when (currentPage) {
        1 -> mockAuthorized // 卡 2：必须先授权 mock
        2 -> hasFineLocation // 卡 3：必须先授予定位权限
        else -> true
    }
    /**
     * 动态 CTA 文字：第 2、3 页授权完成后切换为「下一步 / 开始使用」。
     * 修复：「完成之后没法下一步」——之前固定显示「去设置 / 授予权限」，
     * 即使用户已经完成授权，按钮文字也不变，导致无路可走。
     */
    val ctaText: String get() = when (currentPage) {
        0 -> "下一步"
        1 -> if (mockAuthorized) "下一步" else "去设置"
        2 -> if (hasFineLocation) "开始使用" else "授予权限"
        else -> "下一步"
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefs: OnboardingPreferences,
    private val authChecker: MockAuthChecker,
) : ViewModel() {

    private val _currentPage = MutableStateFlow(0)
    private val _mockAuthorized = MutableStateFlow(false)
    private val _hasFineLocation = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    /** 一次性错误提示（Snackbar 用）。 */
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val pages: List<OnboardingPage> = listOf(
        OnboardingPage(
            title = "欢迎使用 FakeLocation",
            body = "本 App 通过 Android 开发者选项的「模拟位置应用」机制，" +
                "向其他 App 报告一个你指定的位置。无需 Root。",
            cta = "下一步",
        ),
        OnboardingPage(
            title = "授权模拟位置",
            body = "请在「开发者选项 → 选择模拟位置应用」中指定本 App。\n\n" +
                "• 未开启开发者选项：设置 → 关于本机 → 连续点击「版本号」7 次\n" +
                "• 开启后：开发者选项 → 顶部「选择模拟位置应用」",
            cta = "去设置",
        ),
        OnboardingPage(
            title = "授予定位权限",
            body = "需要 ACCESS_FINE_LOCATION 权限，App 才能注入测试位置。\n\n" +
                "你的位置数据仅在本机使用，不会上传。",
            cta = "授予权限",
        ),
    )

    val uiState: StateFlow<OnboardingUiState> = combine(
        _currentPage,
        _mockAuthorized,
        _hasFineLocation,
        prefs.isCompleted,
    ) { page, mock, perm, completed ->
        OnboardingUiState(
            currentPage = page,
            totalPages = pages.size,
            mockAuthorized = mock,
            hasFineLocation = perm,
            completed = completed,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OnboardingUiState(totalPages = pages.size),
    )

    fun next() {
        if (_currentPage.value < pages.size - 1) {
            _currentPage.value = _currentPage.value + 1
        }
    }

    fun previous() {
        if (_currentPage.value > 0) {
            _currentPage.value = _currentPage.value - 1
        }
    }

    fun goTo(page: Int) {
        _currentPage.value = page.coerceIn(0, pages.size - 1)
    }

    fun setMockAuthorized(authorized: Boolean) {
        _mockAuthorized.value = authorized
    }

    fun setHasFineLocation(granted: Boolean) {
        _hasFineLocation.value = granted
    }

    /**
     * 同时刷新 mock 授权状态与定位权限。供 UI 在 ON_RESUME 时调用。
     */
    fun refreshAuthState(context: Context) {
        _mockAuthorized.value = authChecker.isAuthorized()
        _hasFineLocation.value = REQUIRED_PERMISSIONS.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun complete() {
        viewModelScope.launch { prefs.setCompleted(true) }
    }

    /**
     * 跳过整个引导。
     * 修复：「跳过也没法跳过」——之前 OnboardingRoute 顶部的"跳过"按钮
     * 调的是 onCompleted（空 lambda），从未触发 prefs.setCompleted(true)，
     * AppRoot 永远停留在 Onboarding 页面。
     */
    fun skip() {
        viewModelScope.launch { prefs.setCompleted(true) }
    }

    /**
     * 设置一次性错误消息（UI 通过 collectAsStateWithLifecycle 监听，showSnackbar 后调用 [consumeError]）。
     */
    fun notifyError(message: String) {
        _errorMessage.value = message
    }

    fun consumeError() {
        _errorMessage.value = null
    }

    companion object {
        val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
