plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing config is loaded from keystore.properties (gitignored — never commit real
// signing credentials). See README's "Real ads / release signing" section for how to generate
// your own keystore and fill this file in. If it's missing, the release build type below simply
// builds unsigned (fine for compiling/checking the code, but won't install on a device).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = java.util.Properties()
val hasSigningConfig = keystorePropertiesFile.exists()
if (hasSigningConfig) {
    keystoreProperties.load(java.io.FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.socksrelay.vertex"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.socksrelay.vertex"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // AdMob. Check for newer versions at https://developers.google.com/admob/android/quick-start
    // before shipping — this ecosystem moves fast and Google periodically requires SDK upgrades.
    implementation("com.google.android.gms:play-services-ads:25.0.0")
    // Google's User Messaging Platform — handles the GDPR/UK consent form required before
    // loading personalized (or, in the EEA/UK, any) ads for users in those regions.
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")
}

