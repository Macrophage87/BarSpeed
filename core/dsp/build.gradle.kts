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

// GuideVoiceCopyContractTest reads the REAL GuideScreen.kt -- the Voice
// section it pins is what the lifter reads in the app, and a copy of that prose
// here would drift exactly as the prose itself did (#293 round 1: the shipped
// Voice section described a merged rep call that no code produces).
//
// Copied by name into a generated directory rather than reached with a srcDir
// on app/src/main. A srcDir would need an `include` filter, and an include on a
// resources source set is a WHITELIST over the whole of it: the 34 field-*.csv
// fixtures under core/dsp/src/test/resources would have to be named in it too,
// and a new one dropped in without widening the filter is silently absent from
// build/resources/test with no build error. core/model/build.gradle.kts carries
// that filter and that warning; this module keeps its fixtures unfiltered.
val guideSourceResource by tasks.registering(Copy::class) {
    from(rootProject.file("app/src/main/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"))
    into(layout.buildDirectory.dir("generated/guide-source"))
}

sourceSets["test"].resources.srcDir(guideSourceResource.map { it.destinationDir })

dependencies {
    api(project(":core:model"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
