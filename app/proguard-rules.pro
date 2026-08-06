# 应用自身的 Activity / Fragment：系统与 AndroidX 会通过清单/反射恢复实例，不能混淆
-keep public class com.chajianzhushou.app.*Activity { *; }
-keep public class com.chajianzhushou.app.*Fragment { *; }

# 自定义 View：XML 布局按类名反射构造，保留全部成员
-keep class com.chajianzhushou.app.FlowBorderView { *; }
-keep class com.chajianzhushou.app.DissolveView { *; }

# 保留行号信息，便于以后排查崩溃日志
-keepattributes SourceFile,LineNumberTable

# OkHttp / Okio：保留全部类与接口，避免混淆后网络请求异常
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
