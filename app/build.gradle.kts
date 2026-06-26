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
    namespace = "com.jppelt.muditatimer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jppelt.muditatimer"
        minSdk = 28
        targetSdk = 31
        versionCode = 4
        versionName = "1.3.1"
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

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

// Zero external dependencies — Android framework only.
dependencies {}
