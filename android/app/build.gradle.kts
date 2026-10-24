import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// The default category set is shared with the server; copy it into the app's assets.
val seedDir = layout.buildDirectory.dir("generated/seedAssets")
val copySeed by tasks.registering(Copy::class) {
    from(rootProject.file("../server/internal/seed/default-categories.json"))
    into(seedDir)
}

// Release signing: keystore.properties next to this project (storeFile, storePassword,
// keyAlias, keyPassword) or, in CI, ANDROID_KEYSTORE_FILE plus the ANDROID_KEYSTORE_PASSWORD,
// ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD environment variables (see .github/workflows/release.yml).
// Without either, release builds use the debug key so they still install for testing.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { setProperty("storeFile", it) }
    System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }?.let { setProperty("storePassword", it) }
    System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() }?.let { setProperty("keyAlias", it) }
    System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() }?.let { setProperty("keyPassword", it) }
}
val hasReleaseKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !keystoreProps.getProperty(it).isNullOrBlank() }

android {
    namespace = "app.kosh"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.kosh"
        minSdk = 26
        targetSdk = 36
        // Release builds pass -PversionName=1.2.3 (from the git tag); versionCode is derived from it
        // (major * 10000 + minor * 100 + patch) so every release installs over the previous one.
        versionName = (findProperty("versionName") as String?) ?: "1.0.0"
        versionCode = versionName!!.split(".").map { it.toInt() }.let { (ma, mi, pa) -> ma * 10000 + mi * 100 + pa }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    sourceSets["main"].assets.srcDir(seedDir)
    // Exported Room schemas, for MigrationTestHelper in instrumented tests.
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
    testOptions.unitTests.isReturnDefaultValues = true
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

tasks.named("preBuild") { dependsOn(copySeed) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
