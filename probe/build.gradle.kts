import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val signStoreFile: String? = keystoreProperties.getProperty("storeFile") ?: System.getenv("DUCKMOCK_STORE_FILE")
val signStorePassword: String? = keystoreProperties.getProperty("storePassword") ?: System.getenv("DUCKMOCK_STORE_PASSWORD")
val signKeyAlias: String? = keystoreProperties.getProperty("keyAlias") ?: System.getenv("DUCKMOCK_KEY_ALIAS")
val signKeyPassword: String? = keystoreProperties.getProperty("keyPassword") ?: System.getenv("DUCKMOCK_KEY_PASSWORD")
val hasSigning = signStoreFile != null && signStorePassword != null && signKeyAlias != null && signKeyPassword != null

android {
    namespace = "com.strawing.duckprobe"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.strawing.duckprobe"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(signStoreFile!!)
                storePassword = signStorePassword
                keyAlias = signKeyAlias
                keyPassword = signKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
