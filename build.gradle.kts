plugins {
    // Kotlin-family plugins are declared here (apply false) so they land on the
    // root classpath with KNOWN versions — subproject requests then match instead
    // of hitting "already on the classpath with an unknown version". AGP is
    // deliberately NOT here: it only resolves from Google Maven, which is
    // unreachable in restricted -PjvmOnly environments, and per-module versioned
    // requests work because AGP never appears on a parent classpath.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    }
}
