# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# build.gradle.

# 保留行号信息，方便线上崩溃时定位问题
-keepattributes SourceFile,LineNumberTable

# ===== CameraX =====
-keep class androidx.camera.** { *; }
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.video.** { *; }
-keep class androidx.camera.lifecycle.** { *; }
-keep class androidx.camera.view.** { *; }
-keep class androidx.camera.camera2.** { *; }

# ===== OpenCV =====
-keep class org.opencv.** { *; }

# ===== 阿里云 OSS =====
-keep class com.aliyun.dpa.oss.** { *; }
-keep class com.alibaba.** { *; }

# ===== ML Kit =====
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }

# ===== Google Guava / ListenableFuture =====
-keep class com.google.common.util.concurrent.** { *; }

# ===== AndroidX Security Crypto =====
-keep class androidx.security.** { *; }

# ===== 本工程代码（保留类名与方法，避免业务逻辑被混淆导致反射/序列化问题） =====
-keep class com.example.cameraphonedata.** { *; }

# ===== JSON / 反射相关 =====
-keepclassmembers class * {
    @org.json.JSONField <fields>;
}
-keepclassmembers class * {
    public <init>(org.json.JSONObject);
}

# ===== 枚举类 =====
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ===== Parcelable =====
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
