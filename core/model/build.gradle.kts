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
// The filter is a property of the whole source set, not of one srcDir, and its
// one job is to keep the rest of app/src/main -- res/ (drawables,
// file_paths.xml) and every Kotlin source but GuideScreen.kt -- off the test
// classpath.
//
// It is an EXCLUDE, not a whitelist (#69 item 7). It used to be
// include("**/*.json", "AndroidManifest.xml", "**/screens/GuideScreen.kt",
// "README.md", "PROMPTS.md"), which failed silent: a resource dropped into
// core/model/src/test/resources matching none of those patterns was never
// copied to build/resources/test, and getResourceAsStream returned null with
// no build error. Now everything in every srcDir is admitted except the two
// app/src/main trees named below, so a new resource is on the classpath by
// default and only those trees need thinking about.
//
// GuideScreen.kt is the one file under kotlin/ that is admitted, which is why
// this is a spec rather than exclude("kotlin/**", "res/**"): that pattern form,
// the one this comment used to propose, would also drop GuideScreen.kt and red
// GuidePromptContractTest with a null resource. Directories under kotlin/ are
// not excluded, only files, because excluding a directory prunes everything
// beneath it, GuideScreen.kt included. The admitted set was compared file by
// file against the include form's when this changed; the lane report has it.
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
    exclude { element ->
        val path = element.relativePath.pathString
        path == "res" ||
            path.startsWith("res/") ||
            (!element.isDirectory && path.startsWith("kotlin/") && !path.endsWith("/screens/GuideScreen.kt"))
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
