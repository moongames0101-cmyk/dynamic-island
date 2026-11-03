plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Every cloud build gets a higher number, so the app can tell when GitHub has a newer version.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.moongames.dynamicisland"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moongames.dynamicisland"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.$buildNumber"
        buildConfigField("String", "GITHUB_REPO", "\"moongames0101-cmyk/dynamic-island\"")
    }

    // One fixed key, so each new APK installs over the old one.
    // The key lives in GitHub's encrypted secrets, never in this repo:
    // the workflow writes it to a temp file and passes its path + password in here.
    val keystorePath = System.getenv("SIGNING_KEYSTORE")
    val keystorePassword = System.getenv("SIGNING_PASSWORD")
    val hasKey = !keystorePath.isNullOrEmpty() && !keystorePassword.isNullOrEmpty()

    signingConfigs {
        if (hasKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = "island"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without the secret (e.g. a local build) fall back to the debug key.
            signingConfig = if (hasKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
