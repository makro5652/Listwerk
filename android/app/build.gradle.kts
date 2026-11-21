import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.listwerk.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.listwerk.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        // Nur die Sprachen, die die App tatsächlich mitbringt (Deutsch); schlankere APK.
        resourceConfigurations += listOf("de")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "/DebugProbesKt.bin", "/kotlin-tooling-metadata.json")
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
    }

    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// Themes und Paletten kommen unverändert aus ../../shared; nur diese beiden Ordner gehören in die APK.
val sharedAssets = layout.buildDirectory.dir("generated/shared-assets")
val copySharedAssets by tasks.registering(Copy::class) {
    from("../../shared/themes") { into("themes") }
    from("../../shared/palettes") { into("palettes") }
    into(sharedAssets)
}
android.sourceSets.getByName("main").assets.srcDir(sharedAssets)
tasks.matching { it.name.matches(Regex("(merge|generate|lint).*Assets|.*[Ll]int.*|process.*Resources")) }.configureEach { dependsOn(copySharedAssets) }

ksp { arg("room.generateKotlin", "true") }

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
