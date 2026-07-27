plugins {
    `java-library`
    id("io.papermc.paperweight.userdev")
}

dependencies {
    implementation(project(":core"))
    paperweight.paperDevBundle("26.2.build.+")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

val generateAdapterSources = tasks.register<Copy>("generateAdapterSources") {
    from("../v1_21_11/src/main/java")
    exclude("**/ProtocolEntityPreview.java")
    into(layout.buildDirectory.dir("generated/sources/adapter/java"))
    eachFile { path = path.replace("v1_21_11", "v26_2") }
    filter { line: String -> line.replace("v1_21_11", "v26_2")
        .replace("\"1.21.11\"", "\"26.2\"")
        .replace("net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.EntityTypes")
        .replace("EntityType.", "EntityTypes.")
        // Since 26.2, the entity constructor allocates its ID through the level.
        .replace("EntityTypes.BLOCK_DISPLAY, null",
            "EntityTypes.BLOCK_DISPLAY, ((CraftWorld) location.getWorld()).getHandle()") }
}

sourceSets.main {
    java.srcDir(generateAdapterSources)
}
