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
        // NewPipeExtractor (YouTube Music bez reklam) publikuje się tylko przez JitPack
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.TeamNewPipe") ; includeGroupByRegex("com\\.github\\..*") }
        }
    }
}
rootProject.name = "MD-Tools"
include(":app")
