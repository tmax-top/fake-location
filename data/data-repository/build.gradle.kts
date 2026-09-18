plugins {
    id("fakelocation.android.library")
    id("fakelocation.android.hilt")
}

android {
    namespace = "com.fakelocation.data.repository"
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":data:data-local"))
    implementation(project(":data:data-remote"))
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
}
