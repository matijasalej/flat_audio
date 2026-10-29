plugins {
    id("com.android.application")
}

val runNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Release signing comes from the environment (CI decodes the keystore secret to a file and exports
// these). Without them the release build is signed with the debug key, so local and branch builds
// still produce an installable APK.
val envStoreFile: String? = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
val envStorePassword: String? = System.getenv("SIGNING_STORE_PASSWORD")?.takeIf { it.isNotEmpty() }
val envKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val hasReleaseSigning: Boolean =
    envStoreFile != null && envStorePassword != null && envKeyAlias != null

android {
    namespace = "io.github.matijasalej.wholeflat"
    compileSdk = 35
    // The NDK the native core is built with (cargo-ndk in CI); Gradle only uses it to strip symbols.
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "io.github.matijasalej.wholeflat"
        minSdk = 26
        // Stays 34: with targetSdk 35 Android blocks starting a mediaPlayback foreground service
        // from BOOT_COMPLETED, which is how this app comes back after a reboot.
        targetSdk = 34
        versionCode = runNumber
        versionName = "0.1.$runNumber"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(envStoreFile!!)
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                // Same password for store and key; the keystore is PKCS12.
                keyPassword = envStorePassword
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // The Sonos local websocket API (wss, self-signed certificate).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // An adb client: the phone page's switch of Synced on HDMI mutes the Spotify app through the box's own adb (AdbSelf).
    implementation("dev.mobile:dadb:1.2.10")

    // The sync math is plain Kotlin and is tested on the JVM; org.json is only here because the
    // Android one is a stub in unit tests.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
