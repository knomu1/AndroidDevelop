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

rootProject.name = "AndroidApps"
// 1 アプリ = apps/<名前>/ 配下に android/（Gradle モジュール）、pc/（.NET）、docs/ をまとめる
val androidApps = listOf("qrscanner", "camerastream", "webrtc-cam")
androidApps.forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("apps/$name/android")
}