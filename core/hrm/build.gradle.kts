plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:model"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
