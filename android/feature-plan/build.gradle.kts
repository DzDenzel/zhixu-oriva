import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localMapProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
val domesticMapKey = localMapProperties.getProperty("TIANDITU_KEY", "").trim()
require(domesticMapKey.matches(Regex("[A-Za-z0-9_-]*"))) { "TIANDITU_KEY 格式无效" }

android {
    namespace = "com.planlock.feature.plan"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        buildConfigField("String", "TIANDITU_KEY", "\"$domesticMapKey\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation(project(":core-plugin"))
    implementation(project(":core-api"))
    implementation(project(":core-record"))
    implementation(project(":core-sync"))
    implementation(project(":core-engine"))
    implementation(project(":core-security"))
    implementation(project(":core-design"))
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(libs.junit)
}
