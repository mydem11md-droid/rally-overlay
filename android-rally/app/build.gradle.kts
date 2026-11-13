plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

import java.util.Properties

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
val releaseStoreFile = System.getenv("ANDROID_KEYSTORE_BASE64")?.takeIf { it.isNotBlank() }?.let { encoded ->
    val file = layout.buildDirectory.file("release-signing/rally-release.jks").get().asFile
    file.parentFile.mkdirs()
    file.writeBytes(java.util.Base64.getDecoder().decode(encoded))
    file
}
val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null &&
    !releaseStorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.mythic.rally"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mythic.rally"
        minSdk = 26
        targetSdk = 35
        versionCode = versionProperties.getProperty("versionCode").toInt()
        versionName = versionProperties.getProperty("versionName")
    }
    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
