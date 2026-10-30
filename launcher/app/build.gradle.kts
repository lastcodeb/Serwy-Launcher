import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Secrets stay out of git: pixabay.apiKey lives in the (ignored) local.properties
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

// -Ppixabay.apiKey=… overrides local.properties; an empty value builds a key-free APK for publishing
val pixabayKey: String = (findProperty("pixabay.apiKey") as String?) ?: localProps.getProperty("pixabay.apiKey", "")

android {
    namespace = "dev.mibox.neon"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.mibox.neon"
        minSdk = 30 // Mi TV Box S 2nd Gen runs Android 11
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"
        buildConfigField("String", "PIXABAY_KEY", "\"$pixabayKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal sideload build: sign with the local debug key
            signingConfig = signingConfigs.getByName("debug")
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
        compose = true
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.palette:palette-ktx:1.0.0")
    // Screensaver video: hardware decoding, streamed through a small disk cache
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-database:1.4.1")
    // Installs the baseline profiles shipped with Compose for faster cold start
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // "Tüm uygulamaları kapat": force-stop through the box's own adbd (see LocalAdb)
    implementation("dev.mobile:dadb:1.2.10")

    testImplementation("junit:junit:4.13.2")
}
