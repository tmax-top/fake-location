package com.fakelocation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fakelocation.core.ui.theme.FakeLocationTheme
import com.fakelocation.feature.home.HomeRoute
import com.fakelocation.feature.onboarding.OnboardingRoute
import dagger.hilt.android.AndroidEntryPoint

/**
 * 根级路由：首次启动显示引导页，完成后切换到主页面。
 * 完成状态由 OnboardingPreferences（DataStore）持久化。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FakeLocationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot(viewModel: RootViewModel = hiltViewModel()) {
    val needsOnboarding by viewModel.needsOnboarding.collectAsStateWithLifecycle()

    when (needsOnboarding) {
        null -> {
            // 加载中
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        true -> OnboardingRoute(onCompleted = { /* prefs.isCompleted Flow 自动更新 */ })
        false -> HomeRoute()
    }
}
