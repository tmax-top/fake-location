package com.fakelocation.core.mock.di

import com.fakelocation.core.mock.MockLocationEngine
import com.fakelocation.core.mock.MockLocationEngineImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 暴露 Mock 引擎的 DI 绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MockModule {

    @Binds
    @Singleton
    abstract fun bindMockLocationEngine(impl: MockLocationEngineImpl): MockLocationEngine
}
