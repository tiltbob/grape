plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The release workflow passes the version from the git tag (v1.2.3 -> 1.2.3 / 1003003).
val grapeVersionName: String = (project.findProperty("grapeVersionName") as String?) ?: "0.3.3"
val grapeVersionCode: Int = (project.findProperty("grapeVersionCode") as String?)?.toInt() ?: 3003

// Release signing comes from the environment so the keystore never lives in the repo.
// Without these variables assembleRelease still works and produces an unsigned APK.
val releaseKeystore: String? = System.getenv("GRAPE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.tiltbob.grape"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.tiltbob.grape"
        minSdk = 26
        targetSdk = 35
        versionCode = grapeVersionCode
        versionName = grapeVersionName
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("GRAPE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GRAPE_KEY_ALIAS")
                keyPassword = System.getenv("GRAPE_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
