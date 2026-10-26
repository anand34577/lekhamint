import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

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

// Receipt reading runs PaddleOCR models with ncnn (Tencent, BSD-3-Clause) inside the app: no Google services,
// nothing sent anywhere. The prebuilt ncnn release is downloaded once into Gradle's cache and checked against
// this SHA-256 before use, so every build links exactly the same library.
val ncnnVersion = "20260526"
val ncnnSha256 = "85b18b875488585c2d21360430e0e54abb6c04aa88094b471c20208ab55ff796"
val ncnnZip = File(gradle.gradleUserHomeDir, "caches/lekhamint/ncnn-$ncnnVersion-android.zip")
val ncnnDir: File = layout.buildDirectory.dir("ncnn").get().asFile
val ncnnRoot = File(ncnnDir, "ncnn-$ncnnVersion-android")

fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    md.digest().joinToString("") { "%02x".format(it) }
}

val fetchNcnn by tasks.registering {
    description = "Downloads and unpacks the pinned ncnn release used for on-device receipt reading."
    outputs.dir(ncnnRoot)
    doLast {
        if (!ncnnZip.isFile || sha256(ncnnZip) != ncnnSha256) {
            ncnnZip.parentFile.mkdirs()
            val url = "https://github.com/Tencent/ncnn/releases/download/$ncnnVersion/ncnn-$ncnnVersion-android.zip"
            logger.lifecycle("Downloading $url")
            val tmp = File(ncnnZip.path + ".part")
            URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val got = sha256(tmp)
            if (got != ncnnSha256) { tmp.delete(); throw GradleException("ncnn download checksum mismatch: $got") }
            tmp.renameTo(ncnnZip)
        }
        if (!File(ncnnRoot, "arm64-v8a/lib/libncnn.a").isFile) {
            ZipFile(ncnnZip).use { zip ->
                for (e in zip.entries()) {
                    val out = File(ncnnDir, e.name)
                    if (!out.canonicalPath.startsWith(ncnnDir.canonicalPath)) continue
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile.mkdirs()
                    zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
        }
    }
}

// Release signing: keystore.properties next to this project (storeFile, storePassword,
// keyAlias, keyPassword) or, in CI, ANDROID_KEYSTORE_FILE plus the ANDROID_KEYSTORE_PASSWORD,
// ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD environment variables (see .github/workflows/release.yml).
// Without either, a release build fails fast instead of silently producing a debug-signed
// APK that pretends to be a release; pass -PallowDebugSigning=true for a local test build.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { setProperty("storeFile", it) }
    System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }?.let { setProperty("storePassword", it) }
    System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() }?.let { setProperty("keyAlias", it) }
    // The key's own password (PKCS12 keystores usually use the store password for both, so fall back to it).
    (System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() })
        ?.let { setProperty("keyPassword", it) }
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
        // A suffix such as "-rc1" stays in the name but is ignored for the code.
        // Local builds take the newest release from CHANGELOG.md, so About never shows a stale number.
        val changelogVersion = rootProject.file("../CHANGELOG.md").takeIf { it.exists() }?.useLines { lines ->
            lines.firstNotNullOfOrNull { Regex("^## (\\d+\\.\\d+\\.\\d+)").find(it)?.groupValues?.get(1) }
        }
        versionName = (findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: changelogVersion ?: "1.0.0"
        val semver = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)").find(versionName!!)
            ?: throw GradleException("versionName must start with MAJOR.MINOR.PATCH, got '$versionName'")
        versionCode = semver.destructured.let { (ma, mi, pa) -> ma.toInt() * 10000 + mi.toInt() * 100 + pa.toInt() }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // Phones: 64- and 32-bit ARM. Debug builds add x86_64 for the emulator (see below); x86 phones are long gone
        // and x86 Chromebooks run ARM apps, so release builds skip the 6 MB x86_64 copy of the receipt reader.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild {
            cmake { arguments += listOf("-DNCNN_ROOT=${ncnnRoot.absolutePath.replace('\\', '/')}", "-DANDROID_STL=c++_static") }
        }
    }

    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
        val allowDebugSigning = (findProperty("allowDebugSigning") as String?)?.toBoolean() == true
        // Fail only when a release artifact is actually requested; plain debug
        // builds configure the release block too and must keep working.
        val wantsRelease = gradle.startParameter.taskNames.any {
            it.contains("Release", ignoreCase = true) || it.equals("build", true) || it.equals("assemble", true) || it.equals("check", true)
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            } else if (allowDebugSigning || !wantsRelease) {
                signingConfig = signingConfigs.getByName("debug")
            } else {
                throw GradleException(
                    "No release keystore found. Add keystore.properties (or the ANDROID_KEYSTORE_* variables), " +
                        "or build with -PallowDebugSigning=true for a debug-signed test build.",
                )
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            ndk { abiFilters += "x86_64" }
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
        // Bouncy Castle's post-quantum tables (1.2 MB); opening password-protected PDFs needs only AES, RC4 and SHA.
        resources.excludes += "org/bouncycastle/pqc/**"
        // Store the receipt reader compressed: a smaller download, unpacked once when the app is installed.
        jniLibs.useLegacyPackaging = true
    }
    // The OCR models are already compact; compressing them again saves little and slows loading.
    androidResources { noCompress += listOf("bin") }
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
// CMake needs ncnn unpacked before it configures.
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake") || name.startsWith("generateJsonModel")) dependsOn(fetchNcnn)
}

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
    // PDF statements: text extraction only. Its own BouncyCastle copy is left out (the app already has one,
    // and it is only needed for certificate-locked PDFs, which statements never are).
    implementation(libs.pdfbox) {
        exclude(group = "org.bouncycastle")
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
