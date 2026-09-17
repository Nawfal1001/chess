pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "DecentralChess"
include(":app", ":chess-core", ":identity", ":p2p", ":training", ":ai", ":security", ":storage")
