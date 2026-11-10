plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kevinpostal.fiberirc"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kevinpostal.fiberirc"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Opt-in live tests (`@LiveE2e`) stay out of a plain connectedDebugAndroidTest, and TestLab's `testlab` argument
        // narrows a run to its selection; see androidTest LiveE2e.kt and tools/testlab/android.py.
        testInstrumentationRunnerArguments["filter"] = "com.kevinpostal.fiberirc.LiveE2eFilter"

        // Same URL as iOS AppGroup.swift:15-20's debug override. `-Pfiberirc.apiBaseUrl=…` overrides it.
        val apiBaseUrl = (project.findProperty("fiberirc.apiBaseUrl") as String?) ?: "https://ircfiber.com"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")

        // Sentry error reporting (to self-hosted Bugsink via the gateway's reverse proxy). Blank = SDK never starts.
        val sentryDsn = ((project.findProperty("fiberirc.sentryDsn") as String?) ?: "").trim()
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")

        // Firebase Cloud Messaging (Android push), without the google-services plugin, mirroring
        // vanlife/android/app/build.gradle.kts's Firebase.init pattern. Blank = Firebase never starts (M12 §1).
        val fcmSenderId = ((project.findProperty("fiberirc.fcmSenderId") as String?) ?: "").trim()
        val fcmApiKey = ((project.findProperty("fiberirc.fcmApiKey") as String?) ?: "").trim()
        val fcmAppId = ((project.findProperty("fiberirc.fcmAppId") as String?) ?: "").trim()
        val fcmProjectId = ((project.findProperty("fiberirc.fcmProjectId") as String?) ?: "").trim()
        buildConfigField("String", "FCM_SENDER_ID", "\"$fcmSenderId\"")
        buildConfigField("String", "FCM_API_KEY", "\"$fcmApiKey\"")
        buildConfigField("String", "FCM_APP_ID", "\"$fcmAppId\"")
        buildConfigField("String", "FCM_PROJECT_ID", "\"$fcmProjectId\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Release variants require the existing upgrade-compatible key; never fall back to a debug signature.
    val sideloadKeystore = file(System.getProperty("user.home") + "/.android/fiberirc-release.jks")
    signingConfigs {
        create("release") {
            storeFile = sideloadKeystore
            storePassword = "fiberirc-release"
            keyAlias = "fiberirc"
            keyPassword = "fiberirc-release"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { jvmToolchain(21) }

    testOptions {
        unitTests.isReturnDefaultValues = true
        managedDevices {
            localDevices {
                create("pixel6Api35") {
                    device = "Pixel 6"
                    sdkVersion = 35
                    systemImageSource = "google"
                    require64Bit = true
                }
            }
        }
    }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// connected*AndroidTest installs on, and afterwards uninstalls from, every adb device. Require ANDROID_SERIAL so
// one run touches one device. Mirrors vanlife/android/app/build.gradle.kts:154-161.
tasks.configureEach {
    if (name.startsWith("connected") && name.endsWith("AndroidTest")) {
        val serial = providers.environmentVariable("ANDROID_SERIAL")
        doFirst { check(serial.isPresent) { "Set ANDROID_SERIAL to one device from `adb devices`." } }
    }
}

tasks.register("writeUpdateManifest") {
    dependsOn("assembleRelease")
    val applicationId = checkNotNull(android.defaultConfig.applicationId)
    val versionCode = checkNotNull(android.defaultConfig.versionCode)
    val versionName = checkNotNull(android.defaultConfig.versionName)
    val minSdk = checkNotNull(android.defaultConfig.minSdk)
    val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val manifest = layout.buildDirectory.file("outputs/updates/android-update.json")
    inputs.properties(mapOf("applicationId" to applicationId, "versionCode" to versionCode,
        "versionName" to versionName, "minSdk" to minSdk))
    inputs.file(apk)
    outputs.file(manifest)
    doLast {
        check(versionCode > 0 && minSdk > 0) { "Update versionCode and minSdk must be positive." }
        check(Regex("[0-9]+(\\.[0-9]+)*").matches(versionName)
            && versionName.split('.').all { it.toLongOrNull() != null }) { "Invalid numeric update versionName." }
        check(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(applicationId)) {
            "Invalid update applicationId."
        }
        check(apk.get().asFile.isFile) { "Signed release APK is missing." }
        val output = manifest.get().asFile
        output.parentFile.mkdirs()
        output.writeText("""{"applicationId":"$applicationId","versionCode":$versionCode,"versionName":"$versionName","minSdk":$minSdk}""" + "\n")
    }
}

dependencies {
    implementation(libs.livekit)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.sentry.android)
    implementation(libs.play.services.auth.blockstore)
    implementation(libs.play.app.update)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    implementation(libs.zxing.core)
    // Native passkeys with the WebAuthn PRF extension (encryption backup/unlock); the Play Services module is the
    // Credential Manager provider bridge before Android 14.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
