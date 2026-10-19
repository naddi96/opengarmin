plugins {
    id("com.android.application")
    kotlin("android")
    id("com.google.devtools.ksp")
}

/** Quote a value for a generated BuildConfig String field. */
fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val envOpenAiBaseUrl =
    System.getenv("PULSE_OPENAI_BASE_URL") ?: System.getenv("OPENAI_BASE_URL") ?: ""
val envOpenAiApiKey =
    System.getenv("PULSE_OPENAI_API_KEY") ?: System.getenv("OPENAI_API_KEY") ?: ""
val envOpenAiModel =
    System.getenv("PULSE_OPENAI_MODEL") ?: System.getenv("OPENAI_MODEL") ?: ""

// Release versioning is overridden by the release workflow from the git tag.
val appVersionName = System.getenv("VERSION_NAME")?.trim()?.takeIf { it.isNotEmpty() } ?: "0.1.0"
val appVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1

// The release keystore is intentionally public so CI can sign installable APKs
// without repository secrets. Override via RELEASE_* env vars to use your own.
val releaseKeystore = rootProject.file(
    System.getenv("RELEASE_STORE_FILE") ?: "keystore/opengarmin-release.jks"
)

android {
    namespace = "dev.opengarmin.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.opengarmin.app"
        minSdk = 28
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "OPENAI_BASE_URL", buildConfigString(envOpenAiBaseUrl))
        buildConfigField("String", "OPENAI_API_KEY", buildConfigString(envOpenAiApiKey))
        buildConfigField("String", "OPENAI_MODEL", buildConfigString(envOpenAiModel))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: "opengarmin-release"
            keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "opengarmin"
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: "opengarmin-release"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
}
