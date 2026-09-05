# The qualification build intentionally keeps the Pica dependency under the default R8 rules.

# okhttp/okio and the androidx.test runner are Kotlin libraries; keep the Kotlin
# runtime so R8 full mode does not strip the classes they call at runtime
# (Intrinsics, Lambda, LazyKt, ...), which fails the minified release smoke.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# The instrumented test APK is minified separately from the app APK, so the smoke
# entry point and the Pica API must keep their names and members across the two
# APKs or the test resolves to NoClassDefFoundError at runtime.
-keep class io.github.jukomu.picacomic.** { *; }

# Core library desugaring rewrites java.nio.file / java.time into the j$ package;
# R8 would otherwise strip the methods, failing the minified release smoke with
# NoSuchMethodError (e.g. j$.nio.file.Path.getFileSystem, j$.time.Duration.ofSeconds).
-keep class j$.** { *; }
