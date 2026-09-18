package com.fakelocation.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidHiltConventionPlugin : org.gradle.api.Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.google.dagger.hilt.android")
            apply("org.jetbrains.kotlin.kapt")
        }

        dependencies {
            "implementation"(libs.findLibrary("hilt-android").get())
            "kapt"(libs.findLibrary("hilt-compiler").get())
        }
    }
}
