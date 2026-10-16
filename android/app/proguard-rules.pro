# R8/ProGuard rules cho bản release (Gson + Firebase RTDB + Retrofit dùng reflection).

-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations

# Lớp dữ liệu (de)serialize bằng Gson / Firebase getValue(): giữ nguyên tên lớp, trường, constructor.
-keep class com.ttdk1507d.inspectioncamera.model.** { *; }
-keep class com.ttdk1507d.inspectioncamera.worker.PendingUploadMetadata { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Retrofit: giữ interface API (generic Response<...> cần Signature) và các tham số suspend.
-keep,allowobfuscation,allowshrinking interface com.ttdk1507d.inspectioncamera.api.ApiService
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# TypeToken<Map<String, Any>> trong FirebaseManager
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
