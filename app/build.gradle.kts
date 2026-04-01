import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.navigation.safe.args)
}

// Load env.properties file for ShengWang configuration
val envProperties = Properties()
val envPropertiesFile = rootProject.file("env.properties")
if (envPropertiesFile.exists()) {
    envPropertiesFile.inputStream().use { envProperties.load(it) }
}

// Validate required ShengWang configuration properties
// APP_CERTIFICATE is required because this project uses HTTP token auth
// ("Authorization: agora token=<token>") for REST API calls, which requires
// the App Certificate to be enabled in the ShengWang console.
val requiredProperties = listOf(
    "APP_ID",
    "APP_CERTIFICATE",
    "LLM_API_KEY",
)

val missingProperties = mutableListOf<String>()
requiredProperties.forEach { key ->
    val value = envProperties.getProperty(key)
    if (value.isNullOrEmpty()) {
        missingProperties.add(key)
    }
}

if (missingProperties.isNotEmpty()) {
    val errorMessage = buildString {
        append("Please configure the following required properties in env.properties:\n")
        missingProperties.forEach { prop ->
            append("  - $prop\n")
        }
        append("\nPlease refer to env.properties for configuration reference.")
    }
    throw GradleException(errorMessage)
}

/** Escape for embedding env string values inside Java string literals in BuildConfig. */
fun quoteForBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "cn.shengwang.convoai.quickstart"
    compileSdk = 36

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "cn.shengwang.convoai.quickstart.kotlin"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Load ShengWang configuration from env.properties
        buildConfigField("String", "APP_ID", "\"${envProperties.getProperty("APP_ID", "")}\"")
        buildConfigField("String", "APP_CERTIFICATE", "\"${envProperties.getProperty("APP_CERTIFICATE", "")}\"")

        // LLM configuration
        buildConfigField("String", "LLM_API_KEY", "\"${envProperties.getProperty("LLM_API_KEY", "")}\"")
        buildConfigField("String", "LLM_URL", "\"${envProperties.getProperty("LLM_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")}\"")
        buildConfigField("String", "LLM_MODEL", "\"${envProperties.getProperty("LLM_MODEL", "qwen-plus")}\"")
        buildConfigField("String", "LLM_VENDOR", "\"${quoteForBuildConfig(envProperties.getProperty("LLM_VENDOR", ""))}\"")
        buildConfigField(
            "String",
            "LLM_PARRAMS",
            "\"${quoteForBuildConfig(envProperties.getProperty("LLM_PARRAMS", ""))}\""
        )
        buildConfigField(
            "String",
            "LLM_SYSTEM_MESSAGES",
            "\"${quoteForBuildConfig(envProperties.getProperty("LLM_SYSTEM_MESSAGES", ""))}\""
        )
        buildConfigField(
            "String",
            "LLM_MAX_HISTORY",
            "\"${envProperties.getProperty("LLM_MAX_HISTORY", "").trim()}\""
        )

        // ASR (open-source style pipeline)
        buildConfigField("String", "ASR_LANG", "\"${quoteForBuildConfig(envProperties.getProperty("ASR_LANG", ""))}\"")
        buildConfigField("String", "ASR_VENDOR", "\"${quoteForBuildConfig(envProperties.getProperty("ASR_VENDOR", ""))}\"")
        buildConfigField("String", "ASR_PARAMS", "\"${quoteForBuildConfig(envProperties.getProperty("ASR_PARAMS", ""))}\"")

        // TTS configuration (vendor + JSON params; optional legacy ByteDance fields for KeyCenter)
        buildConfigField("String", "TTS_VENDOR", "\"${quoteForBuildConfig(envProperties.getProperty("TTS_VENDOR", ""))}\"")
        buildConfigField("String", "TTS_PARAMS", "\"${quoteForBuildConfig(envProperties.getProperty("TTS_PARAMS", ""))}\"")
        buildConfigField("String", "TTS_BYTEDANCE_APP_ID", "\"${envProperties.getProperty("TTS_BYTEDANCE_APP_ID", "")}\"")
        buildConfigField("String", "TTS_BYTEDANCE_TOKEN", "\"${envProperties.getProperty("TTS_BYTEDANCE_TOKEN", "")}\"")
        // SAL sample_urls（对齐 CovLivingViewModel.buildSalSampleUrls：无本地 biometric 条目时用实验室 PCM）
        buildConfigField(
            "Boolean",
            "SAL_ENABLE_PERSONALIZED",
            "${envProperties.getProperty("SAL_ENABLE_PERSONALIZED", "false").equals("true", ignoreCase = true)}"
        )
        buildConfigField(
            "String",
            "SAL_PERSONALIZED_PCM_URL",
            "\"${quoteForBuildConfig(envProperties.getProperty("SAL_PERSONALIZED_PCM_URL", ""))}\""
        )
        buildConfigField(
            "String",
            "SAL_BIOMETRIC_SAMPLE_URLS",
            "\"${quoteForBuildConfig(envProperties.getProperty("SAL_BIOMETRIC_SAMPLE_URLS", ""))}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.gson)

    // Shengwang SDKs
    implementation(libs.shengwang.rtc.full)
    implementation(libs.shengwang.rtm.lite)
    
    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)
    
    // Lifecycle components
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    
    // RecyclerView
    implementation(libs.androidx.recyclerview)
    
    // Navigation Component
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
}
