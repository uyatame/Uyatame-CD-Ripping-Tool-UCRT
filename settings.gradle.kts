pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "UCRT"
// app: Android 6.0 以上。legacy: Android 4.0〜5.1(画面は View。共通の処理は core/ のソースをどちらにも入れる)
include(":app")
include(":legacy")
