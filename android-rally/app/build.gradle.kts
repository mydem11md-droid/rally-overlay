plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.mythic.rally"
    compileSdk = 34
    defaultConfig { applicationId = "com.mythic.rally"; minSdk = 26; targetSdk = 35; versionCode = 3; versionName = "3.0.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
