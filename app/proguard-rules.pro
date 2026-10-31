# sherpa-onnx AAR 已经自带 JNI 绑定，保留其类和 native 方法
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# 无障碍服务、前台服务需要被系统反射实例化
-keep class com.voiceassistant.service.** { *; }
-keep class com.voiceassistant.exec.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
