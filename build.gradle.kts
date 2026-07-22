import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.7.1")
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testImplementation("org.postgresql:postgresql:42.7.13")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3")
        bundledPlugins("com.intellij.database")

        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginVerification {
        freeArgs = listOf("-mute", "TemplateWordInPluginName")
    }
}
