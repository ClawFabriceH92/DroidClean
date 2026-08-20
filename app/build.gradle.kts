import java.io.File
import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * La version est pilotée par la propriété Gradle `droidcleanVersionName`
 * (la CI la dérive du tag git : `-PdroidcleanVersionName=1.2.3`).
 * Le versionCode en découle mécaniquement : 1.2.3 -> 10203, donc strictement
 * croissant tant que les versions le sont.
 */
val appVersionName: String =
    (providers.gradleProperty("droidcleanVersionName").orNull)
        ?.trim()?.removePrefix("v")?.takeIf { it.isNotBlank() }
        ?: "1.1.0"

fun versionCodeOf(name: String): Int {
    val parts = name.split("-", "+")[0].split(".").map { it.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    return major * 10_000 + minor * 100 + patch
}

val keystoreB64: String? = providers.environmentVariable("DROIDCLEAN_KEYSTORE_B64")
    .orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "com.fabrice.droidclean"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fabrice.droidclean"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeOf(appVersionName)
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            if (keystoreB64 != null) {
                val tmp = System.getenv("RUNNER_TEMP") ?: System.getProperty("java.io.tmpdir") ?: "/tmp"
                val ks = File(tmp, "droidclean-release.keystore")
                val bytes = Base64.getDecoder().decode(keystoreB64)
                // N'écrit que si nécessaire : évite de réécrire le keystore à chaque configuration.
                if (!ks.exists() || ks.length() != bytes.size.toLong()) ks.writeBytes(bytes)
                storeFile = ks
                storePassword = System.getenv("DROIDCLEAN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DROIDCLEAN_KEY_ALIAS")
                keyPassword = System.getenv("DROIDCLEAN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (keystoreB64 == null) null else signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = false
        buildConfig = true
    }

    lint {
        warningsAsErrors = false
        // La CI publie le rapport sans bloquer la release sur un avertissement.
        abortOnError = false
        checkReleaseBuilds = true
        // Les libellés de l'app sont volontairement en français uniquement.
        disable += setOf("MissingTranslation")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/** Utilisé par la CI pour nommer l'APK sans dupliquer la logique de version. */
tasks.register("printVersionName") {
    val version = appVersionName
    doLast { println(version) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
