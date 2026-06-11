// Root build file. Plugin versions are declared here and applied in module build files.
plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin >= 2.2 is required to read the litertlm-android AAR's Kotlin 2.3 metadata.
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
