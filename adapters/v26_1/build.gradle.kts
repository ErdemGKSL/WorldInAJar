plugins {
    `java-library`
    id("io.papermc.paperweight.userdev")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
    // Paper did not publish a 26.1 dev bundle; 26.1.1 is the first available
    // unobfuscated 26.1-family implementation bundle.
    paperweight.paperDevBundle("26.1.1.build.+")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

val generateAdapterSources = tasks.register<Sync>("generateAdapterSources") {
    from("../v1_21_11/src/main/java")
    into(layout.buildDirectory.dir("generated/sources/adapter/java"))
    eachFile {
        path = path.replace("v1_21_11", "v26_1")
        if (name == "ProtocolEntityPreview.java") name = "ProtocolEntityPreview26_1.java"
    }
    filter { line: String -> line.replace("v1_21_11", "v26_1")
        .replace("\"1.21.11\"", "\"26.1\"")
        .replace("ProtocolEntityPreview", "ProtocolEntityPreview26_1") }
}

sourceSets.main {
    java.srcDir(generateAdapterSources)
}
