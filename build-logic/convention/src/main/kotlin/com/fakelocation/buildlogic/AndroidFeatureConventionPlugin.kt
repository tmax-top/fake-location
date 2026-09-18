package com.fakelocation.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidFeatureConventionPlugin : org.gradle.api.Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("fakelocation.android.library.compose")

        dependencies {
            "implementation"(project(":core:core-ui"))
            "implementation"(project(":core:core-common"))
        }
    }
}
