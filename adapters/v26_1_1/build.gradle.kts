plugins {
    `java-library`
    id("io.papermc.paperweight.userdev")
}

dependencies {
    implementation(project(":core"))
    paperweight.paperDevBundle("26.1.1.build.+")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

val generateAdapterSources = tasks.register<Sync>("generateAdapterSources") {
    from("../v1_21_11/src/main/java")
    exclude("**/ProtocolEntityPreview.java", "**/paper/PaperRuntimeAdapter.java")
    into(layout.buildDirectory.dir("generated/sources/adapter/java"))
    eachFile { path = path.replace("v1_21_11", "v26_1_1") }
    filter { line: String -> line.replace("v1_21_11", "v26_1_1")
        .replace("\"1.21.11\"", "\"26.1.1\"") }
}

sourceSets.main {
    java.srcDir(generateAdapterSources)
}
