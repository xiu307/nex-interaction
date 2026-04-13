import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
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

    // facedet 运行时从 assets 加载 models/w600k_mbf.onnx 与 MediaPipe .task；本地 AAR 未带齐时可合并 sibling
    // face-detc-java 在 :facedet:downloadFacedetModels 后生成的 facedet/build/generated/facedetAssets/assets。
    sourceSets {
        getByName("main") {
            val facedetGeneratedAssets = rootProject.file("../face-detc-java/facedet/build/generated/facedetAssets/assets")
            if (facedetGeneratedAssets.isDirectory) {
                assets.srcDirs(facedetGeneratedAssets)
            }
        }
    }
}

extensions.configure<BaseAppModuleExtension>("android") {
    packagingOptions.pickFirst("lib/arm64-v8a/libc++_shared.so")
    packagingOptions.pickFirst("lib/armeabi-v7a/libc++_shared.so")
    packagingOptions.pickFirst("lib/x86_64/libc++_shared.so")

    packagingOptions.pickFirst("lib/arm64-v8a/libonnxruntime.so")
    packagingOptions.pickFirst("lib/armeabi-v7a/libonnxruntime.so")
    packagingOptions.pickFirst("lib/x86_64/libonnxruntime.so")
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

    // facedet AAR（flat files() 不解析传递依赖，须与 :facedet 中声明的版本对齐）
    // 与 ttsplayer 的 native 依赖统一到 1.19.2，避免同进程内 ONNX Runtime 符号版本冲突。
    implementation(files("libs/facedet-release.aar"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // 从 AiGlasses 抽出的本地 TTS 播放能力
    implementation(files("libs/ttsplayer_v0.0.1_2025-12-08_platform_speech.aar"))
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
    implementation("com.squareup.okhttp3:okhttp:4.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.9.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.9.0")
    implementation("com.squareup.okio:okio:2.8.0")
}
