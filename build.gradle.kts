import xyz.jpenilla.runpaper.task.RunServer

plugins {
    id("java")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" apply false
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("com.gradleup.shadow") version "9.4.1"
}

dependencies {
    implementation(project(":core"))
    runtimeOnly(project(":adapters:v1_21_11", configuration = "reobf"))
    runtimeOnly(project(":adapters:v26_1"))
    runtimeOnly(project(":adapters:v26_1_1"))
    runtimeOnly(project(":adapters:v26_1_2"))
    runtimeOnly(project(":adapters:v26_2"))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

sourceSets {
    main {
        java.setSrcDirs(emptyList<String>())
        resources.setSrcDirs(emptyList<String>())
    }
    test {
        java.setSrcDirs(emptyList<String>())
        resources.setSrcDirs(emptyList<String>())
    }
}

tasks {
    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier = ""
        configurations = listOf(project.configurations.runtimeClasspath.get())
    }

    assemble {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
        pluginJars.from(shadowJar.flatMap { it.archiveFile })
    }

    register<RunServer>("runServer26_1_1") {
        minecraftVersion("26.1.1")
        runDirectory = layout.projectDirectory.dir("run-26.1.1")
        pluginJars.from(shadowJar.flatMap { it.archiveFile })
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    register<RunServer>("runServer26_1_2") {
        minecraftVersion("26.1.2")
        runDirectory = layout.projectDirectory.dir("run-26.1.2")
        pluginJars.from(shadowJar.flatMap { it.archiveFile })
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    register<RunServer>("runServer26_2") {
        minecraftVersion("26.2")
        runDirectory = layout.projectDirectory.dir("run-26.2")
        pluginJars.from(shadowJar.flatMap { it.archiveFile })
        jvmArgs("-Xms2G", "-Xmx2G")
    }
}
