package com.fakelocation

import androidx.lifecycle.ViewModel
import com.fakelocation.feature.onboarding.data.OnboardingPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

/**
 * 根级状态：是否需要展示引导。
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    prefs: OnboardingPreferences,
) : ViewModel() {

    /**
     * `true` = 仍在引导中 / 引导未完成
     * `false` = 已完成引导，展示主页面
     */
    val needsOnboarding: StateFlow<Boolean?> = prefs.isCompleted
        .map { completed -> !completed }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null, // null = 加载中
        )
}
