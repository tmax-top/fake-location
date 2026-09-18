plugins {
    id("fakelocation.android.library")
    id("fakelocation.android.hilt")
}

android {
    namespace = "com.fakelocation.data.remote"
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi.core)
    implementation(libs.moshi.kotlin)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
}
