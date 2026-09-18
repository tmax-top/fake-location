plugins {
    id("fakelocation.android.feature")
    id("fakelocation.android.hilt")
}

android {
    namespace = "com.fakelocation.feature.onboarding"
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(project(":core:core-mock"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.nav.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
