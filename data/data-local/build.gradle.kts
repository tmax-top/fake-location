plugins {
    id("fakelocation.android.library")
}

android {
    namespace = "com.fakelocation.data.local"
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    kapt(libs.androidx.room.compiler)
}
