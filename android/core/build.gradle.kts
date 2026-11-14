import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.coroutines.core)
    implementation(libs.serialization.json)
    testFixturesApi(libs.kotlin.test)
    testFixturesApi(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

// Themes, Paletten und Fixtures liegen unverändert unter ../../shared und dienen den Tests als Ressourcen
// (Pfade: themes/*.json, palettes/*.json, fixtures/*.ics). Die App liest die JSON-Dateien aus ihren Assets.
sourceSets {
    test { resources.srcDir("../../shared") }
}
