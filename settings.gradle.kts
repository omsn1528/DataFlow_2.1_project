pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
plugins {
    // lets Gradle auto-download the JDK named in gradle/gradle-daemon-jvm.properties
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "DataFlow"
include(":app")
