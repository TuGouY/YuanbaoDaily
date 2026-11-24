pluginManagement {
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application" -> useModule("com.android.tools.build:gradle:9.3.2")
                "org.jetbrains.kotlin.android" -> useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
            }
        }
    }
    repositories {
        google()
        mavenCentral()
        // Xposed API 82 is published in the official Xposed Maven repository.
        maven(url = "https://api.xposed.info")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Required by compileOnly("de.robv.android.xposed:api:82").
        maven(url = "https://api.xposed.info")
    }
}

rootProject.name = "YuanbaoDaily"
include(":app")
