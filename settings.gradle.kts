pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "WorldInAJar"
include("core")
include("adapters:v1_21_11")
include("adapters:v26_1")
include("adapters:v26_1_1")
include("adapters:v26_1_2")
include("adapters:v26_2")
