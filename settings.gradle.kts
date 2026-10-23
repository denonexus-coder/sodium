rootProject.name = "sodium"

pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }

        mavenCentral()
        gradlePluginPortal()
    }
}

// The atlas cache is a separate mod that is built and shipped next to Sodium.
// It captures the texture atlases (and their full sprite metadata) into a
// persistent cache and reuses that cache on later runs, so the atlas does not
// have to be stitched again.
include("atlas-cache")
