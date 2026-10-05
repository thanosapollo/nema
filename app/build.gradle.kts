import com.android.build.api.artifact.SingleArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

abstract class MergeUnitTestAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appAssets: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemas: DirectoryProperty

    @get:OutputDirectory
    abstract val outputAssets: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun merge() {
        fileSystem.sync {
            from(appAssets, schemas)
            into(outputAssets)
            duplicatesStrategy = DuplicatesStrategy.FAIL
        }
    }
}

plugins {
    id("com.android.application")
    id("androidx.room")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.thanosapollo.nema"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "org.thanosapollo.nema"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.3.2-reliability.2"

        // The opt-in network journey must never start NemaApplication against installed data.
        testInstrumentationRunner = if (providers.gradleProperty("nemaSharedThreadNetworkProof").orNull == "true")
            "org.thanosapollo.nema.service.SharedThreadNetworkProofRunner"
        else "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Alpha releases retain the established signer so existing users can upgrade.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Exported schemas are migration fixtures, not application assets.
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
    sourceSets["test"].java.srcDir("src/sharedTest/java")
    if (providers.gradleProperty("nemaSharedThreadNetworkProof").orNull == "true") {
        sourceSets["androidTest"].java.srcDirs("src/sharedTest/java", "src/networkAndroidTest/java")
    }
}

// App-module JVM tests reuse the app's binary resources; src/test/assets is not
// merged by AGP. Transform only the unit-test artifact, never the app's assets.
androidComponents {
    onVariants { variant ->
        variant.unitTest?.let { unitTest ->
            val fixtureAssets = tasks.register<MergeUnitTestAssets>("${unitTest.name}FixtureAssets") {
                schemas.set(layout.projectDirectory.dir("schemas"))
            }
            unitTest.artifacts.use(fixtureAssets)
                .wiredWithDirectories(MergeUnitTestAssets::appAssets, MergeUnitTestAssets::outputAssets)
                .toTransform(SingleArtifact.ASSETS)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// JVM tests otherwise accept duplicate classes/resources that Android cannot package.
// Use AGP's checks on the app runtime (not Robolectric's separate dependency graph).
listOf("Debug", "Release").forEach { variant ->
    tasks.matching { it.name == "test${variant}UnitTest" }.configureEach {
        dependsOn("check${variant}DuplicateClasses", "merge${variant}JavaResource")
    }
}

configurations.configureEach {
    exclude(group = "xpp3", module = "xpp3")
}

dependencies {
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")

    implementation("org.igniterealtime.smack:smack-android:4.4.8") {
        // Nema does not implement OpenPGP. Its unused PGPainless 0.1 stack brings
        // bcprov-jdk15on alongside our SHA3 provider; do not mix those families.
        exclude(group = "org.igniterealtime.smack", module = "smack-openpgp")
    }
    implementation("org.igniterealtime.smack:smack-tcp:4.4.8")
    implementation("org.igniterealtime.smack:smack-im:4.4.8")
    implementation("org.igniterealtime.smack:smack-extensions:4.4.8")
    implementation("org.igniterealtime.smack:smack-experimental:4.4.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Lightweight digest API: Android 26 does not guarantee a SHA3 JCA provider.
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    debugImplementation("androidx.compose.ui:ui-tooling")
    // Device Compose tests launch this Activity in the target debug application.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // AGP requires instrumentation dependencies to match the target app's versions.
    // Room's schema parser needs serialization 1.8.1; keep this debug-only alignment.
    debugImplementation(platform("org.jetbrains.kotlinx:kotlinx-serialization-bom:1.8.1"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.room:room-testing:2.8.4")
    // Room's test schema parser is compiled against serialization 1.8.1.
    testImplementation(platform("org.jetbrains.kotlinx:kotlinx-serialization-bom:1.8.1"))

    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
