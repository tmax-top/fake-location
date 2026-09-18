plugins {
    id("fakelocation.android.feature")
    id("fakelocation.android.hilt")
}

android {
    namespace = "com.fakelocation.feature.home"
}

dependencies {
    implementation(project(":core:core-mock"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.nav.compose)

    // 地图：osmdroid（OpenStreetMap，MIT 许可，免 Key）
    implementation(libs.osmdroid.android)

    implementation(libs.kotlinx.coroutines.android)
}
