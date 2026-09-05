# The qualification build intentionally keeps the Pica dependency under the default R8 rules.

# okhttp/okio are Kotlin libraries; R8 full mode otherwise strips the intrinsics
# they call at runtime, failing the minified release smoke with NoClassDefFoundError.
-keep class kotlin.jvm.internal.Intrinsics { *; }
