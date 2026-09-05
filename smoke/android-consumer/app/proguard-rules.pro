# The qualification build intentionally keeps the Pica dependency under the default R8 rules.

# okhttp/okio and the androidx.test runner are Kotlin libraries; R8 full mode
# otherwise strips the kotlin.jvm.internal classes they call at runtime, failing
# the minified release smoke with NoClassDefFoundError (Intrinsics, Lambda, ...).
-keep class kotlin.jvm.internal.** { *; }
