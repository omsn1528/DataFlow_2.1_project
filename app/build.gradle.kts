import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Release signing values come from keystore.properties (git-ignored) or environment variables.
// Nothing secret is stored in this project. If they are missing, the release build is left UNSIGNED
// (it never falls back to the debug key).
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, env: String): String? =
    (ksProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val ksFile = secret("storeFile", "DATAFLOW_KEYSTORE_FILE")

android {
    namespace = "app.viday"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.viday"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "2.1"
    }
    signingConfigs {
        if (ksFile != null) {
            create("release") {
                storeFile = rootProject.file(ksFile)
                storePassword = secret("storePassword", "DATAFLOW_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "DATAFLOW_KEY_ALIAS")
                keyPassword = secret("keyPassword", "DATAFLOW_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
