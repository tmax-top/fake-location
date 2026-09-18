plugins {
    id("fakelocation.android.library")
    id("fakelocation.android.hilt")
}

android {
    namespace = "com.fakelocation.core.mock"
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
