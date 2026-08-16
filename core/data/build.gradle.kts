plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
}

android {
    namespace = "com.macrophage.barspeed.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

// Unit tests here load :core:model and :core:dsp classes. Those modules are
// jvmToolchain(21) and emit class file 65, while this module is 17 and would
// otherwise run its tests on a JVM that stops at 61. Compilation is unaffected
// -- that is why the seven-module build has always been green -- but the test
// JVM's class loader is strict, and without this every test in this module dies
// with UnsupportedClassVersionError before its first assertion:
//
//   com/macrophage/barspeed/dsp/SetAnalysis has been compiled by a more recent
//   version of the Java Runtime (class file version 65.0), this version of the
//   Java Runtime only recognizes class file versions up to 61.0
//
// This changes no produced bytecode: the AAR's classes stay at major version 61.
// It sets only which JVM runs the tests. CI installs Temurin 21 and does not
// disable toolchain auto-detection, so the launcher resolves there too.
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) },
    )
}

room {
    // The Room Gradle plugin serializes per-variant schema output, avoiding the
    // parallel kspDebug/kspRelease race on schemas/<db>/1.json.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:dsp"))
    api(project(":core:hrm"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // api: AppDatabase extends RoomDatabase, so Room types are part of this module's ABI.
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
