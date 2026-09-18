plugins {
    id("fakelocation.android.library.compose")
    id("fakelocation.android.hilt")
}

android {
    namespace = "com.fakelocation.core.ui"
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.nav.compose)
}
