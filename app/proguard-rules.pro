# 默认混淆规则(release 构建用)。debug 构建不混淆。
# OkHttp / Okio 平台兼容
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ZXing
-keep class com.google.zxing.** { *; }
