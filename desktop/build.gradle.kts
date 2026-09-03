plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm()
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.metadata.extractor)
                implementation(libs.commons.imaging)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
    jvmToolchain(17)
}

compose.desktop.application {
    mainClass = "com.inventory.desktop.MainKt"
    // Required by the packaging tasks; we ship the fat JAR and run it on the
    // system JRE, so the jpackage/native-distribution tasks are never invoked
    // (no bundled runtime, per the project requirement).
    nativeDistributions {
        packageName = "photoinventory"
    }
}

// Compose Multiplatform 1.6.11 names the runnable fat-JAR task
// `packageUberJarForCurrentOS`; this alias keeps the canonical
// `:desktop:packageFatJar` invocation working (same output, system JRE,
// no bundled runtime).
tasks.register("packageFatJar") {
    group = "distribution"
    description = "Builds the runnable fat JAR (alias of packageUberJarForCurrentOS)."
    dependsOn("packageUberJarForCurrentOS")
}
