import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// 真机联调：-PPLANLOCK_API_BASE_URL=http://<PC局域网IP>:8080/api/v1/（默认模拟器回环 10.0.2.2）
val apiBaseUrl: String = (project.findProperty("PLANLOCK_API_BASE_URL") as? String)
    ?: "http://10.0.2.2:8080/api/v1/"

// Debug 默认保留本地 mock；Release 强制真实接口，避免发布包静默走演示数据。
val debugUseMock: Boolean = (project.findProperty("PLANLOCK_USE_MOCK") as? String)
    ?.toBooleanStrictOrNull() ?: true

android {
    namespace = "com.planlock.core.api"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    buildTypes {
        debug { buildConfigField("boolean", "USE_MOCK", "$debugUseMock") }
        release { buildConfigField("boolean", "USE_MOCK", "false") }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.androidx.datastore.preferences)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
