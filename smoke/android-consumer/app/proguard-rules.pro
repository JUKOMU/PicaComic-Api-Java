# The qualification build intentionally keeps the Pica dependency under the default R8 rules.

# okhttp/okio and the androidx.test runner are Kotlin libraries; keep the Kotlin
# runtime so R8 full mode does not strip the classes they call at runtime
# (Intrinsics, Lambda, LazyKt, ...), which fails the minified release smoke.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
