import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.planlock.plugin.forceblocker"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    compileOnly(project(":core-plugin"))
    testImplementation(project(":core-plugin"))
    testImplementation(libs.junit)
}

tasks.register<Exec>("assemblePlugin") {
    dependsOn("assembleRelease", ":core-plugin:assembleRelease")
    workingDir(rootProject.projectDir)
    commandLine(
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", project.file("build-plugin.ps1").absolutePath,
    )
}
