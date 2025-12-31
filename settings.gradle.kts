pluginManagement {
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
        // ✅ اینجا باید باشه (برای AAR داخل app/libs)
        flatDir {
            dirs("app/libs")
        }

        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // برای Xray-core (اگر واقعاً لازم داری)
    }
}

rootProject.name = "MyV2rayApp"
include(":app")
