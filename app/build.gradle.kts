plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.mdinstal.narzedzia"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.mdinstal.narzedzia"
        minSdk = 24
        targetSdk = 34
        versionCode = 15
        versionName = "2.6"
    }

    signingConfigs {
        create("release") {
            // Ścieżka do klucza — w CI odtwarzany z Base64 do rootProject/release.jks
            storeFile = rootProject.file("release.jks")
            // Hasła z GitHub Secrets (w CI) lub lokalnych zmiennych środowiskowych
            storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "mdinstal"
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val vName = versionName ?: "1.0"
            val vCode = versionCode.toString()
            output.outputFileName = "MD-INSTAL-${vName}-${vCode}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    // Sieć / WebView
    implementation("androidx.webkit:webkit:1.11.0")
    // Biometria (jeśli używasz do logowania)
    implementation("androidx.biometric:biometric:1.1.0")
    // Szyfrowane preferencje (loginy/hasła do WebView)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Fragment (jeśli jeszcze gdzieś używasz)
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")

    // Updater — HTTP + JSON (bez Retrofit, żeby nie ciągnąć dodatkowych zależności)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}
