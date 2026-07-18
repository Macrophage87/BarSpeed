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
    namespace = "com.macrophage.accelerometerlifting.data"
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
