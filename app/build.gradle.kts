plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Dane podpisu pochodzą wyłącznie z GitHub Secrets (zmienne środowiskowe). W repo nie ma haseł ani keystore.
val ksFile = rootProject.file("release.jks")
val ksPassword = System.getenv("KEYSTORE_PASSWORD").orEmpty()
val ksAlias = System.getenv("KEY_ALIAS").orEmpty()
val ksKeyPassword = System.getenv("KEY_PASSWORD").orEmpty()
val hasSigning = ksFile.exists() && ksPassword.isNotEmpty() && ksAlias.isNotEmpty() && ksKeyPassword.isNotEmpty()

android {
    namespace = "pl.mdinstal.narzedzia"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.mdinstal.narzedzia"
        minSdk = 24
        targetSdk = 34
        versionCode = 18
        versionName = "3.2"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = ksFile
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val vName = versionName ?: "1.0"
            val vCode = versionCode.toString()
            output.outputFileName = "MD-Tools-${vName}-${vCode}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
}
