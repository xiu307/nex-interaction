若运行时报「models/arcface.tflite」找不到，任选其一：

1) 在仓库 face-detc-java 中放入 facedet/vendor/arcface.tflite（团队提供的文件），再执行：
   ./gradlew :facedet:downloadFacedetModels
   然后重新编译本 App（会与 ../face-detc-java/facedet/build/generated/facedetAssets/assets 合并）。

2) 或将 arcface.tflite 直接复制到本目录（与 facedet 中路径一致：assets/models/arcface.tflite），再编译本 App。
