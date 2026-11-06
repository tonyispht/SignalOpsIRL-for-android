plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Bump both for every release. The release tag must be "v" + versionName (the workflow checks this), and
// F-Droid reads these two lines to find new versions.
val appVersionCode = 130
val appVersionName = "1.3.0"

/** The app's original ID. Built only as the "bridge" that moves existing users to the new ID (-Plegacy). */
val legacyId = "com.example.signalopsirl"
val newId = "io.github.tonyispht.signalopsirl"
val legacy = project.hasProperty("legacy")

android {
    namespace = "io.github.tonyispht.signalopsirl"
    compileSdk = 34

    defaultConfig {
        applicationId = if (legacy) legacyId else newId
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        // The bridge shows as "Signal OPS (old)" so the two icons can be told apart while moving.
        manifestPlaceholders["appLabel"] = if (legacy) "Signal OPS (old)" else "@string/app_name"
        buildConfigField("String", "LEGACY_ID", "\"$legacyId\"")
        buildConfigField("String", "NEW_ID", "\"$newId\"")
    }

    // github: the APK published on GitHub releases, with in-app updates.
    // fdroid: the build F-Droid makes from source; F-Droid delivers updates itself, so no self-updater.
    flavorDimensions += "dist"
    productFlavors {
        create("github") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("fdroid") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    // Permanent release key. Supplied by CI from repository secrets; never commit the keystore.
    val keystorePath = System.getenv("SIGNING_STORE_FILE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // F-Droid: no Google-encrypted dependency metadata block in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
