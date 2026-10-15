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
rootProject.name = "PUNK"
include(":app")
include(":core-bridge")
include(":core-axmanager")
include(":core-tools")
include(":core-accessibility")
include(":core-media")
include(":core-voice")
include(":feature-chat")
include(":feature-hud")
include(":core-security")
