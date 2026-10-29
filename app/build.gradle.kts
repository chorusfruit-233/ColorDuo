plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Keep the old property aliases for upstream build scripts.
val tagVersionName = ((findProperty("colorduoVersionName") ?: findProperty("hyperduoVersionName")) as String?)?.takeIf { it.isNotBlank() }
val tagVersionCode = ((findProperty("colorduoVersionCode") ?: findProperty("hyperduoVersionCode")) as String?)?.toIntOrNull()
val releaseKeystorePath = providers.environmentVariable("COLORDUO_KEYSTORE_PATH").orNull

android {
    // Retain the source namespace so shared upstream code can be merged.
    namespace = "io.github.yixing233.hyperduo"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        // Keep the source namespace for upstream merges; install ColorDuo independently.
        applicationId = "io.github.chorusfruit_233.colorduo"
        minSdk = 29
        targetSdk = 36
        versionCode = tagVersionCode ?: 10024
        versionName = tagVersionName ?: "0.3.4"
    }

    signingConfigs {
        // Keep installed alpha test builds upgradeable with the development key.
        create("development") {
            storeFile = rootProject.file(".tools/debug.keystore")
            storePassword = "android"
            keyAlias = "hyperduo"
            keyPassword = "android"
        }
        if (releaseKeystorePath != null) {
            create("publication") {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("COLORDUO_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("COLORDUO_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("COLORDUO_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("development")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Missing private credentials produce an unsigned APK, never a
            // public-development-key release. The release workflow requires them.
            signingConfig = signingConfigs.findByName("publication")
        }
    }

    compileOptions {
        // Must match the Kotlin target below: AGP fails the build outright on a
        // mismatch. 21 rather than 17 because Miuix ships Java 21 bytecode and
        // miuix-nav's entry points are inline functions, which cannot be inlined
        // into 17-targeted output.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Keep META-INF/xposed/{java_init.list,scope.list,module.prop} in the APK.
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Provided by the Xposed framework at runtime, never bundled.
    compileOnly("io.github.libxposed:api:102.0.0")
    // Needed by the settings app to talk to the framework service.
    implementation("io.github.libxposed:service:102.0.0")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    // The updater does its network work off the main thread and reports state
    // back on it; Compose alone has no scheduling primitive for that.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // @Serializable on the nav routes. miuix-nav only exposes it as a runtime
    // dependency, so the annotation and the inlined serializer() lookup need it
    // declared here to be on the compile classpath.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Backdrop and squircle shapes. These are NOT Miuix: they are kyant0's libraries, and
    // they are what gives the frosted-glass surfaces their moving, layered look -
    // Miuix's own miuix-blur is the lower-level primitive that `textureBlur` wraps.
    //
    // Sampled from HyperChanger, which is where the visual reference came from. Its pins
    // are older (backdrop 2.0.0, shapes 1.2.0); these are the current releases of the same
    // libraries, and both still publish under io.github.kyant0.
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    // Lucide's icon set, for the six glyphs the module shows as UI affordances (the two
    // tab icons and the four row/action icons). Miuix ships its own icon font, but its
    // set has no matching outline for every affordance and its `Tune`/`Info` pair is the
    // only tab vocabulary it offers. Lucide is Apache-2.0, one artifact, and depends on
    // nothing but the Compose version already here.
    implementation("com.composables:icons-lucide-android:1.1.0")
    // Blurred top bar. The artifact declares minSdk 33, but every effect in it
    // is gated on isRuntimeShaderSupported() and degrades to plain drawing
    // below that, so the manifest override keeps our own minSdk at 29.
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    // Only for the isRuntimeShaderSupported() gate the settings screen applies
    // before it touches any blur API. Blur pulls it in transitively, but the
    // code imports from it directly, so it is declared directly.
    implementation("top.yukonga.miuix.kmp:miuix-shader-android:0.9.4")
    // Compose navigation with a continuous back stack. Exists only from 0.9.4,
    // which is why the whole Miuix stack above moved up with it. Its aar
    // declares minSdk 24, so it needs no manifest override of its own.
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.4")
}
