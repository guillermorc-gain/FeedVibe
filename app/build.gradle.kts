import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Firebase (inicio de sesión con Google + sincronización) solo se activa si existe
// app/google-services.json. Sin él la app funciona en modo local.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Firma de release: keystore.properties local o variables de entorno en CI.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
val ciVersionName = System.getenv("VERSION_NAME")

android {
    namespace = "com.feedvibe.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.feedvibe.app"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "1.0.0"

        // Repositorio de GitHub del que se descargan las actualizaciones (Releases).
        buildConfigField("String", "UPDATE_REPO", "\"guillermorc-gain/FeedVibe\"")
        // Clave opcional de YouTube Data API v3 (secreto YOUTUBE_API_KEY en CI o en local.properties).
        // También se puede escribir desde la app: Perfil → Reproducción → YouTube.
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val ytKey = localProps.getProperty("youtubeApiKey") ?: System.getenv("YOUTUBE_API_KEY") ?: ""
        buildConfigField("String", "YOUTUBE_API_KEY", "\"$ytKey\"")
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        val storeFilePath = signingValue("storeFile", "SIGNING_STORE_FILE")
        create("release") {
            if (storeFilePath != null && rootProject.file(storeFilePath).exists()) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            } else {
                // Clave por defecto incluida en el repo: así todas las compilaciones de CI
                // tienen la misma firma y las actualizaciones se instalan encima.
                storeFile = rootProject.file("keystore/feedvibe-default.jks")
                storePassword = "feedvibe"
                keyAlias = "feedvibe"
                keyPassword = "feedvibe"
            }
        }
    }

    buildTypes {
        release {
            // Sin minificar: evita fallos de R8 por clases opcionales de Firebase/OkHttp.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
        abortOnError = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)

    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.media3.hls)
}
