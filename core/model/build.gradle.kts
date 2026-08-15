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
// code is pinned to. ForegroundServiceContractTest reads the REAL source
// manifest for the same reason — a copy drifts. GuidePromptContractTest reads
// the REAL GuideScreen.kt, which carries the prompt actually put on the
// lifter's clipboard; it is named file-by-file rather than by "**/*.kt",
// because the point is one document, not thirty-one source files on a test
// classpath.
//
// The include filter is a property of the whole source set, not of one srcDir:
// it must admit every resource any test here reads (the schemas, their
// examples, and this module's own real-plan fixture), and its job is to keep
// the rest of app/src/main — res/, drawables, file_paths.xml — off the test
// classpath.
//
// It is a WHITELIST over the whole test source set, and it fails silent. A new
// resource dropped into core/model/src/test/resources that matches neither
// pattern is not copied to build/resources/test and getResourceAsStream returns
// null with no build error — including a field-*.csv, which is this repo's
// discharge ritual for a hardware-found bug. Widen the include, or switch to
// exclude("kotlin/**", "res/**"), before adding one.
sourceSets["test"].resources.apply {
    srcDir(rootProject.file("docs/schemas"))
    srcDir(rootProject.file("app/src/main"))
    include("**/*.json", "AndroidManifest.xml", "**/screens/GuideScreen.kt")
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
