import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing credentials are read from keystore.properties (gitignored).
// Copy keystore.properties.template → keystore.properties and fill in your values.
val keystoreProps = Properties().also { props ->
    val f = rootProject.file("keystore.properties")
    if (f.exists()) props.load(f.inputStream())
}

android {
    namespace = "com.mudita.timer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mudita.timer"
        minSdk = 28
        targetSdk = 31
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile     = keystoreProps["storeFile"]?.let { file(it.toString()) }
            storePassword = keystoreProps["storePassword"]?.toString()
            keyAlias      = keystoreProps["keyAlias"]?.toString()
            keyPassword   = keystoreProps["keyPassword"]?.toString()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig   = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Zero external dependencies — Android framework only.
dependencies {}
