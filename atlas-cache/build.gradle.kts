object AtlasCacheConstants {
    const val MINECRAFT_VERSION: String = "1.20.1"
    const val YARN_VERSION: String = "1.20.1+build.10"
    const val FABRIC_LOADER_VERSION: String = "0.16.10"

    const val MOD_VERSION: String = "0.1.0"
}

plugins {
    id("fabric-loom") version "1.9.2"
}

base {
    archivesName = "sodium-atlas-cache"

    group = "me.jellysquid.mods.sodium"
    version = AtlasCacheConstants.MOD_VERSION
}

repositories {
    mavenCentral()
}

loom {
    accessWidenerPath = file("src/main/resources/sodium_atlas_cache.accesswidener")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    minecraft(group = "com.mojang", name = "minecraft", version = AtlasCacheConstants.MINECRAFT_VERSION)
    mappings(group = "net.fabricmc", name = "yarn", version = AtlasCacheConstants.YARN_VERSION, classifier = "v2")
    modImplementation(group = "net.fabricmc", name = "fabric-loader", version = AtlasCacheConstants.FABRIC_LOADER_VERSION)
}

tasks {
    processResources {
        inputs.property("version", project.version)

        filesMatching("fabric.mod.json") {
            expand(mapOf("version" to project.version))
        }
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
