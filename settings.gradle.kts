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

rootProject.name = "AccelerometerLifting"

// Pure-JVM modules: buildable and testable without the Android SDK.
include(":core:model")
include(":core:dsp")
include(":core:witmotion")
include(":core:hrm")

// Android modules require the Android SDK and Google Maven access.
// Pass -PjvmOnly to work on the pure-JVM modules in restricted environments.
if (!providers.gradleProperty("jvmOnly").isPresent) {
    include(":app")
    include(":core:ble")
    include(":core:data")
}
