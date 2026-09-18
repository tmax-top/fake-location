package com.fakelocation.feature.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 引导页路由。
 *
 * @param onCompleted 全部完成后回调（由调用方决定跳转目标）
 */
@Composable
fun OnboardingRoute(
    onCompleted: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // 权限申请
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = OnboardingViewModel.REQUIRED_PERMISSIONS.all { result[it] == true }
        viewModel.setHasFineLocation(granted)
    }

    // 关键修复（B3）：每次 Activity 回到前台都重检授权与权限。
    // LifecycleEventEffect 默认会在首次 ON_RESUME 时触发，正好覆盖「进入页面」与「从系统设置返回」两种场景。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshAuthState(context)
    }

    // 全部授权 + 标记完成
    LaunchedEffect(uiState.completed) {
        if (uiState.completed) onCompleted()
    }

    // 关键修复：第 2 页 mock 授权完成后，自动跳到第 3 页。
    // 第 3 页定位权限授予后**不**自动跳（已经是最后一页，由用户点"开始使用"完成）。
    LaunchedEffect(uiState.mockAuthorized, uiState.currentPage) {
        if (uiState.currentPage == 1 && uiState.mockAuthorized) {
            viewModel.next()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 顶部：跳过按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (!uiState.isLastPage) {
                    TextButton(onClick = { viewModel.skip() }) {
                        Text("跳过")
                    }
                }
            }

            // 中间：当前页内容
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = uiState.currentPage,
                    transitionSpec = {
                        fadeIn() togetherWith fadeOut()
                    },
                    label = "onboarding_pager",
                ) { pageIndex ->
                    val page = viewModel.pages[pageIndex]
                    OnboardingPageContent(
                        page = page,
                        pageIndex = pageIndex,
                        mockAuthorized = uiState.mockAuthorized,
                        hasFineLocation = uiState.hasFineLocation,
                    )
                }
            }

            // 底部：进度点 + 主按钮 + 错误提示
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PageIndicators(
                    total = uiState.totalPages,
                    current = uiState.currentPage,
                )
                Spacer(Modifier.height(24.dp))
                PrimaryCta(
                    uiState = uiState,
                    currentCta = uiState.ctaText,
                    onClick = {
                        when (uiState.currentPage) {
                            0 -> viewModel.next()
                            1 -> {
                                if (uiState.mockAuthorized) {
                                    // 已授权：直接跳下一页
                                    viewModel.next()
                                } else {
                                    // 跳转到开发者选项。关键修复（M2）：失败时给用户反馈，
                                    // 避免 runCatching 静默吞掉 ActivityNotFoundException。
                                    val intent = Intent(
                                        Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                    val launched = runCatching { context.startActivity(intent) }
                                        .isSuccess
                                    if (!launched) {
                                        viewModel.notifyError(
                                            "未找到开发者选项设置，请手动打开系统设置",
                                        )
                                    }
                                }
                            }
                            2 -> {
                                if (uiState.hasFineLocation) {
                                    // 已授权：完成引导
                                    viewModel.complete()
                                } else {
                                    // 申请权限
                                    permissionLauncher.launch(
                                        OnboardingViewModel.REQUIRED_PERMISSIONS,
                                    )
                                }
                            }
                        }
                    },
                )

                // 错误反馈（M2）：放在 Column 内底部，用 padding 模拟底部边距，
                // 避免 Surface 不提供 BoxScope 导致 Modifier.align 不可用。
                Spacer(Modifier.height(16.dp))
                SnackbarHost(hostState = snackbarHostState) { Snackbar(it) }
            }
        }
    }

    // 监听 ViewModel 错误（M2）
    val errorMessage = viewModel.errorMessage.collectAsStateWithLifecycle().value
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }
}

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    pageIndex: Int,
    mockAuthorized: Boolean,
    hasFineLocation: Boolean,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val (icon, color) = when (pageIndex) {
            1 -> if (mockAuthorized) Icons.Default.Check to MaterialTheme.colorScheme.primary
            else null to MaterialTheme.colorScheme.surfaceVariant
            2 -> if (hasFineLocation) Icons.Default.Check to MaterialTheme.colorScheme.primary
            else null to MaterialTheme.colorScheme.surfaceVariant
            else -> null to MaterialTheme.colorScheme.primary
        }
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(color, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            } else {
                Text(
                    text = (pageIndex + 1).toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = page.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PageIndicators(total: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { i ->
            Box(
                modifier = Modifier
                    .size(if (i == current) 10.dp else 8.dp)
                    .background(
                        color = if (i == current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun PrimaryCta(
    uiState: OnboardingUiState,
    currentCta: String,
    onClick: () -> Unit,
) {
    // 关键修复：原来最后一页用 `onAdvanceFromLast` 路径，enabled=canAdvance 导致
    // hasFineLocation=false 时按钮 disabled，用户没法点"授予权限"。统一用单按钮 +
    // onClick 内部根据 uiState 分支处理（next / 去设置 / 申请权限 / complete）。
    Button(
        onClick = onClick,
        enabled = true,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(currentCta)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
    }
}
