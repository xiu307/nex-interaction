若运行时报「models/w600k_mbf.onnx」或 MediaPipe .task 找不到，任选其一：

1) 在仓库 face-detc-java 中配置 ONNX（vendor/w600k_mbf.onnx 或 gradle.properties 中 FACEDET_RECOGNITION_ONNX_*），再执行：
   ./gradlew :facedet:downloadFacedetModels
   然后重新编译本 App（会与 ../face-detc-java/facedet/build/generated/facedetAssets/assets 合并）。

2) 或将 w600k_mbf.onnx 与 .task 直接复制到本目录（路径与 facedet 一致：assets/models/），再编译本 App。

宿主还须依赖 com.microsoft.onnxruntime:onnxruntime-android（与 facedet 模块版本一致），见 app/build.gradle.kts。
