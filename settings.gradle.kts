rootProject.name = "FakeLocation"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

include(":app")

// core
include(":core:core-common")
include(":core:core-mock")
include(":core:core-ui")

// data
include(":data:data-local")
include(":data:data-remote")
include(":data:data-repository")

// feature
include(":feature:feature-onboarding")
include(":feature:feature-home")
include(":feature:feature-search")
include(":feature:feature-favorites")
include(":feature:feature-history")
include(":feature:feature-route")
include(":feature:feature-settings")
include(":feature:feature-diagnostics")
