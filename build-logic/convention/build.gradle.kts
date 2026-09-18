plugins {
    `kotlin-dsl`
}

group = "com.fakelocation.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.ksp.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "fakelocation.android.application"
            implementationClass = "com.fakelocation.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "fakelocation.android.library"
            implementationClass = "com.fakelocation.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "fakelocation.android.library.compose"
            implementationClass = "com.fakelocation.buildlogic.AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "fakelocation.android.feature"
            implementationClass = "com.fakelocation.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("androidHilt") {
            id = "fakelocation.android.hilt"
            implementationClass = "com.fakelocation.buildlogic.AndroidHiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "fakelocation.jvm.library"
            implementationClass = "com.fakelocation.buildlogic.JvmLibraryConventionPlugin"
        }
    }
}
