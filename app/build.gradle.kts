plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Bump both for every release. The release tag must be "v" + versionName (the workflow checks this).
val appVersionCode = 200
val appVersionName = "2.0.0"

// Test builds (the workflow's manual "Run workflow" button) are numbered one below the release, so the public
// release still installs over them through the in-app updater: e.g. 139 / "1.4.0-test.42".
val testBuild: String? = System.getenv("TEST_BUILD")

android {
    namespace = "io.github.tonyispht.signalopsirl"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.tonyispht.signalopsirl"
        minSdk = 26
        targetSdk = 34
        versionCode = if (testBuild != null) appVersionCode - 1 else appVersionCode
        versionName = if (testBuild != null) "$appVersionName-test.$testBuild" else appVersionName
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
    buildFeatures { compose = true }
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
