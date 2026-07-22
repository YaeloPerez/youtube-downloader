plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "mx.devtech.ytdownloader"
    compileSdk = 36

    defaultConfig {
        applicationId = "mx.devtech.ytdownloader"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = false
    }

    // extractNativeLibs=true (required by youtubedl-android's .so handling)
    // needs legacy packaging, or the app crashes at native-lib load time.
    packaging {
        jniLibs.useLegacyPackaging = true
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
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Bundles a working yt-dlp + ffmpeg for Android (see /android/README.md for why).
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    // youtubedl-android depends on this internally (only as `implementation`,
    // so it doesn't leak to our compile classpath) — we call its public
    // getObjectMapper() directly to parse playlist JSON, so declare it too,
    // pinned to the exact version the library itself resolves at runtime.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.11.1")
}

// Keep templates/index.html as the single source of truth for the UI — copy it
// into assets at build time instead of maintaining a second copy in the repo.
val copyWebAssets = tasks.register<Copy>("copyWebAssets") {
    from("${rootProject.projectDir}/../templates/index.html")
    into("${projectDir}/src/main/assets")
}
tasks.named("preBuild") {
    dependsOn(copyWebAssets)
}
