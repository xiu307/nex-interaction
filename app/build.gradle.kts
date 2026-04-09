import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.gradle.api.Project
import org.gradle.api.Task

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.navigation.safe.args)
}

android {
    namespace = "ai.nex.interaction"
    compileSdk = 36

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "ai.nex.interaction"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 兼容 app 内仍直接读取 BuildConfig 的 OSS STS 路径；其余对话配置已下沉到 agroacore/ConvoConfig
        buildConfigField(
            "String",
            "OSS_STS_TOKEN_URL",
            "\"https://ai-sprite.geely-test.com/vaep/v1/sts/token\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // facedet 运行时从 assets 加载 models/arcface.tflite 与 .task；若 AAR 未带齐资源，可从本仓库 sibling 的
    // face-detc-java 在运行 :facedet:downloadFacedetModels（并放置 vendor/arcface.tflite）后生成的目录合并进 APK。
    sourceSets {
        getByName("main") {
            val facedetGeneratedAssets = rootProject.file("../face-detc-java/facedet/build/generated/facedetAssets/assets")
            if (facedetGeneratedAssets.isDirectory) {
                assets.srcDirs(facedetGeneratedAssets)
            }
        }
    }
}

/**
 * 将 [variantName] 对应目录下已生成的 APK 复制到仓库根目录 [dist-apk]，文件名追加当前时间戳（秒级）。
 * 供 **package**（打包）与 **install**（Android Studio 运行/安装，走 install* 任务）共用。
 */
fun copyVariantApksToDist(project: Project, variantName: String) {
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val destDir = project.rootProject.layout.projectDirectory.dir("dist-apk").asFile
    destDir.mkdirs()
    val apkOutDir = project.layout.buildDirectory.dir("outputs/apk/$variantName").get().asFile
    if (!apkOutDir.isDirectory) return
    apkOutDir.listFiles { _, fileName -> fileName.endsWith(".apk") }?.forEach { apk ->
        val destName = "${apk.nameWithoutExtension}-$timestamp.apk"
        apk.copyTo(File(destDir, destName), overwrite = true)
    }
}

/**
 * - **package***：每次重新打出 APK 后复制到 dist-apk。
 * - **install***：每次点击 Run/安装（执行 installDebug 等）后同样复制；若本次仅安装、package 为 UP-TO-DATE，仍会按「安装时刻」再生成一份带时间戳的副本。
 */
@Suppress("DEPRECATION")
android.applicationVariants.configureEach {
    val variantName = name
    packageApplicationProvider.configure {
        doLast { copyVariantApksToDist(project, variantName) }
    }
}

/** install* 在配置阶段可能尚未注册，延后绑定，保证 Android Studio「运行/安装」也会复制到 dist-apk。 */
afterEvaluate {
    @Suppress("DEPRECATION")
    android.applicationVariants.configureEach {
        val variantName = name
        val installTaskName = "install${variantName.replaceFirstChar { it.uppercaseChar() }}"
        tasks.findByName(installTaskName)?.let { t: Task ->
            t.doLast { copyVariantApksToDist(project, variantName) }
        }
    }
}

dependencies {
    implementation(project(":agroacore"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // Photo Picker（BiometricRegisterActivity 选视频）；显式版本避免传递依赖偏旧
    implementation("androidx.activity:activity-ktx:1.9.3")
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

    // facedet AAR（与 Android/scenes/convoai 对齐的传递依赖）
    implementation(files("libs/facedet-release.aar"))
    val mediapipe = "0.10.14"
    implementation("com.google.mediapipe:tasks-vision:$mediapipe")
    implementation("com.google.mediapipe:tasks-core:$mediapipe")
    implementation("org.openpnp:opencv:4.9.0-0")
    val tflite = "2.14.0"
    implementation("org.tensorflow:tensorflow-lite:$tflite")
    implementation("org.tensorflow:tensorflow-lite-gpu:$tflite")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:$tflite")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // 阿里云 OSS（与 Android common OssTestBucketUploader 一致）
    implementation("com.aliyun.dpa:oss-android-sdk:2.9.19")
}
