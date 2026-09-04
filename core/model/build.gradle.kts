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
// PlanDocVersionContractTest reads the REAL README.md and PROMPTS.md for the
// same reason (#240): both hard-code the plan schema number, both went stale at
// the last mint, and a copy of either would drift exactly as the originals did.
// They live at the repository root, outside every source set, so they are
// copied by name rather than reached with a srcDir on the root directory --
// that would make Gradle walk the whole repository, .git and every module's
// build/ included, to find two files.
val planDocResources by tasks.registering(Copy::class) {
    from(rootProject.file("README.md"), rootProject.file("PROMPTS.md"))
    into(layout.buildDirectory.dir("generated/plan-doc-resources"))
}

sourceSets["test"].resources.apply {
    srcDir(rootProject.file("docs/schemas"))
    srcDir(rootProject.file("app/src/main"))
    srcDir(planDocResources.map { it.destinationDir })
    include("**/*.json", "AndroidManifest.xml", "**/screens/GuideScreen.kt", "README.md", "PROMPTS.md")
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
