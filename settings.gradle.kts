rootProject.name = "benny-ui-automator"
include(":android-instrumentation")
include(":device-controller-api")
include(":mcp-server")
rootProject.children.forEach { it.buildFileName = "${it.name}.gradle.kts" }

pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories { google(); mavenCentral() }
}
