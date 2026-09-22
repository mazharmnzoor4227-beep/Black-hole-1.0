import java.net.URI
import java.security.MessageDigest

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Ship a current, checksum-pinned extractor. Users need no GitHub API access
// or first-launch updater success merely to obtain recent platform fixes.
val extractorVersion = "2026.08.19"
val extractorSha256 = "1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6"
val extractorAssets = layout.buildDirectory.dir("generated/extractorAssets")
val prepareExtractor by tasks.registering {
    inputs.property("version", extractorVersion)
    inputs.property("sha256", extractorSha256)
    outputs.dir(extractorAssets)
    doLast {
        val connection = URI("https://github.com/yt-dlp/yt-dlp/releases/download/$extractorVersion/yt-dlp").toURL().openConnection()
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        val bytes = connection.getInputStream().use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == extractorSha256) { "Extractor checksum mismatch" }
        val directory = extractorAssets.get().asFile.apply { mkdirs() }
        directory.resolve("yt-dlp").writeBytes(bytes)
    }
}
tasks.named("preBuild").configure { dependsOn(prepareExtractor) }
android {
    namespace = "com.blackhole.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.blackhole.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "1.1.2"
        buildConfigField("String", "EXTRACTOR_VERSION", "\"$extractorVersion\"")
        buildConfigField("String", "EXTRACTOR_SHA256", "\"$extractorSha256\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    sourceSets.getByName("main").assets.srcDir(extractorAssets)
    signingConfigs {
        create("production") {
            val key = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!key.isNullOrBlank()) {
                storeFile = file(key)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank()) signingConfig = signingConfigs.getByName("production")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs.useLegacyPackaging = true }
    lint { abortOnError = true }
}
dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
