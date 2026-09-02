plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
}

android {
    namespace = "com.macrophage.barspeed"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.macrophage.barspeed"
        minSdk = 26
        targetSdk = 35
        versionCode = 49
        versionName = "0.1.48"
    }

    signingConfigs {
        create("release") {
            val keystore = file("release.keystore")
            if (keystore.exists()) {
                storeFile = keystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.exists() == true) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    jvmToolchain(17)
}

// :core:data's block, copied here for the same reason and with the same
// effect. This module is jvmToolchain(17) and would otherwise run its unit
// tests on a JVM whose class loader stops at class file version 61, while
// :core:model, :core:dsp, :core:hrm and :core:witmotion are jvmToolchain(21)
// and emit 65. Compilation is unaffected -- that is why the seven-module
// build has always been green -- but the test JVM's loader is strict, and
// any :app test that causes a :core:model type to be LOADED dies before its
// first assertion:
//
//   com/macrophage/barspeed/model/ExerciseDef has been compiled by a more
//   recent version of the Java Runtime (class file version 65.0), this
//   version of the Java Runtime only recognizes class file versions up to
//   61.0
//
// That is not a hypothesis: it is the exact failure observed here by running
// a throwaway test that read PlannedSlot's declared fields, on the tree this
// commit's parent produced. PlanQueueTest survived only by never touching
// such a type, and its own KDoc says so.
//
// This changes no produced bytecode: the APK's classes stay at major version
// 61. It sets only which JVM runs the tests. CI installs Temurin 21 and does
// not disable toolchain auto-detection, so the launcher resolves there too.
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) },
    )
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:dsp"))
    implementation(project(":core:witmotion"))
    implementation(project(":core:hrm"))
    implementation(project(":core:ble"))
    implementation(project(":core:data"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
