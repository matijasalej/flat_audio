// Android Gradle plugin 9.x compiles Kotlin itself (built-in Kotlin, KGP 2.2.10 as its runtime
// dependency), so there is no org.jetbrains.kotlin.android plugin here.
plugins {
    id("com.android.application") version "9.4.1" apply false
}
