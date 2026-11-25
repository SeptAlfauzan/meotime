import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.compottie)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
    // `rasteriseAppIcon` allocates and draws a real Bitmap, which needs an Android runtime to mean anything. Same
    // Robolectric version the shared module's host tests already run on.
    testImplementation(libs.robolectric)
}

android {
    namespace = "com.septaalfauzan.meotime"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.septaalfauzan.meotime"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 5
        versionName = "1.0.3"
    }
    flavorDimensions += "edition"
    productFlavors {
        create("free") { }
        create("pro") { applicationId = "com.septaalfauzan.meotime.pro" }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // AGP 9 no longer generates BuildConfig by default; `BuildConfig.FLAVOR` is required by
        // AppContainer to select the Pro limit editor (Task 7) and by CompileKotlin flavor plumbing.
        buildConfig = true
    }
}