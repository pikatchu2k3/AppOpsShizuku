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
rootProject.name = "AppOpsShizuku"
include(":app")
// spooftest ist nur ein Test-Fixture (nutzt Google Play Services fuer die Werbe-ID) und fehlt
// im F-Droid-Build (dort per `rm:` entfernt) - das Modul daher nur einbinden, wenn es vorhanden ist.
if (file("spooftest").isDirectory) include(":spooftest")
