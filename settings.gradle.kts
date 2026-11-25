// `local.properties` is machine-specific: never commit it. It is regenerated from
// the ANDROID_HOME (or ANDROID_SDK_ROOT) environment variable when missing or
// pointing elsewhere, so no SDK path is hard-coded anywhere in the repo.
val envSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
if (envSdk != null) {
    val localProps = file("local.properties")
    val currentSdk = localProps.takeIf { it.exists() }
        ?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?.substringAfter("sdk.dir=")
        ?.trim()
    if (currentSdk != envSdk) {
        localProps.writeText("sdk.dir=$envSdk\n")
    }
}
else if (!file("local.properties").exists()) {
    throw GradleException(
        "Android SDK location not found: set ANDROID_HOME (or ANDROID_SDK_ROOT), " +
        "e.g. export ANDROID_HOME=/home/<you>/Android/Sdk"
    )
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PhotoInventory"
include(":app")
include(":shared")
include(":desktop")
include(":dumpserver")
