plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Wartości z -P (GitHub Actions) lub domyślne (lokalnie)
val propVersionName: String = (project.findProperty("versionName") as String?) ?: "2.6"
val propVersionCode: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 15

android {
    namespace = "pl.mdinstal.narzedzia"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.mdinstal.narzedzia"
        minSdk = 24
        targetSdk = 34
        versionCode = propVersionCode
        versionName = propVersionName

        // Repo updatera — na sztywno, zawsze to samo (kolczan07/MD-Tools)
        buildConfigField("String", "UPDATE_REPO", "\"kolczan07/MD-Tools\"")
    }

    signingConfigs {
        create("release") {
            // Lokalnie: release.jks w root projektu. W CI: ścieżka z env KEYSTORE_FILE
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "${rootDir}/release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: "mdinstal"
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
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
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    // composeOptions USUNIĘTE — od Kotlina 2.0 plugin sam dobiera wersję kompilatora

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val vName = versionName ?: "1.0"
            val vCode = versionCode.toString()
            output.outputFileName = "MD-INSTAL-${vName}-${vCode}.apk"
        }
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

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

    // Media3 (radio w tle — masz już RadioService)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")

    // Updater — HTTP + JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}
