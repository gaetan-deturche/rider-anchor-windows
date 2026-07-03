plugins {
    // Versions are centralized in settings.gradle.kts (pluginManagement).
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

val pluginGroup = project.property("pluginGroup") as String
val pluginVersion = project.property("pluginVersion") as String
val platformVersion = project.property("platformVersion") as String

group = pluginGroup
version = pluginVersion

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        rider(platformVersion) {
            useInstaller = false
        }
        jetbrainsRuntime()
    }
}

intellijPlatform {
    pluginConfiguration {
        name = project.property("pluginName") as String
        ideaVersion {
            sinceBuild = project.property("pluginSinceBuild") as String
            untilBuild = project.property("pluginUntilBuild") as String
        }
    }
}

kotlin {
    // Rider 2026.1 runs on JBR 25.
    jvmToolchain(25)
}
