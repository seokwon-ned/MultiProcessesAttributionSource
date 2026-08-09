pluginManagement {
    repositories {
        google()
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

rootProject.name = "ToolHub"
include(":processA")
include(":processB")
include(":processC")
include(":aidl")
// :sdk는 AAR 빌드용으로만 포함 — processC는 libs/sdk.aar(file lib)를 쓴다.
include(":sdk")
