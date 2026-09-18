package com.fakelocation.core.common.result

/**
 * 轻量级结果类型，模拟 Kotlin 的 stdlib Result 但避免其异常传染。
 */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(block: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) block(value)
    return this
}

inline fun <T> AppResult<T>.onFailure(block: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) block(error)
    return this
}

fun <T> runCatchingApp(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (t: Throwable) {
    AppResult.Failure(AppError.from(t))
}

/**
 * 应用层统一错误。
 */
sealed class AppError(open val message: String, open val cause: Throwable? = null) {
    data class PermissionDenied(override val message: String) : AppError(message)
    data class MockNotAuthorized(override val message: String) : AppError(message)
    data class Network(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
    data class Provider(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
    data class Unknown(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)

    companion object {
        fun from(t: Throwable): AppError = Unknown(t.message ?: t::class.simpleName.orEmpty(), t)
    }
}
