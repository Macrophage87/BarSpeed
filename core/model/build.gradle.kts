plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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

// SchemaContractTest reads the PUBLISHED schemas, not copies of them: the
// contract only holds if the documents an LLM is pointed at are the ones the
// code is pinned to.
sourceSets["test"].resources.srcDir(rootProject.file("docs/schemas"))

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
