import java.io.File
import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
        ?: "1.2.0"

fun versionCodeOf(name: String): Int {
    val parts = name.split("-", "+")[0].split(".").map { it.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    require(minor in 0..99 && patch in 0..99) {
        "Version $name : minor et patch doivent rester < 100, sinon le versionCode " +
            "cesse d'être strictement croissant."
    }
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
        resourceConfigurations += setOf("fr", "en")
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
        // Remplace une vingtaine de `lateinit` + `findViewById` : un identifiant
        // erroné devient une erreur de compilation au lieu d'un plantage.
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Les *erreurs* lint bloquent désormais la CI : ce sont de vrais défauts
        // (appel d'API trop récente, permission manquante, format de chaîne invalide).
        // Les avertissements restent informatifs pour ne pas bloquer sur du style.
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        checkDependencies = false
        htmlReport = true
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
