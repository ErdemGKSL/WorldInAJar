plugins {
    `java-library`
    id("io.papermc.paperweight.userdev")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    disableAutoTargetJvm()
}

// The 1.21.11 remapper bundled by paperweight cannot read Java 25 class files.
// Compile this isolated legacy adapter to Java 21 bytecode with the Java 25 toolchain;
// the distribution and common core remain Java 25.
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}
