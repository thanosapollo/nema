plugins {
    id("com.android.application") version "8.13.2" apply false
    id("androidx.room") version "2.8.4" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.kapt") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}

subprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        inputs.file(rootProject.file("docs/android-signing-rotation.md"))
            .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    }
}
