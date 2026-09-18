plugins {
    id("fakelocation.android.application")
    id("fakelocation.android.hilt")
}

android {
    namespace = "com.fakelocation"

    defaultConfig {
        applicationId = "com.fakelocation"
        versionCode = 1
        versionName = "0.2.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // v0.2 内部测试：用 Android 默认 debug.keystore 签名，
            // 产物可安装到任何设备，但**不能上架**（debug 签名 Google Play 拒收）。
            // 正式发布前必须替换为 release keystore（见 build-logic 说明）。
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(project(":core:core-ui"))
    implementation(project(":feature:feature-onboarding"))
    implementation(project(":feature:feature-home"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.osmdroid.android)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.androidx.hilt.nav.compose)
}
