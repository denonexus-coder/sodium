# Sodium Atlas Cache

A small Fabric mod that lives next to Sodium in this repository. It captures the
texture atlases that Minecraft builds at startup, together with the complete metadata
of every sprite in them, and reuses that capture on later runs.

The point of the capture is the texture conversion: the image of an atlas plus the
exact rectangle and texture coordinates of every sprite is everything a converter needs
to rebuild the atlas, and the source file of every sprite is what lets it map a
converted block back to the file it came from.

## What is written

```
.minecraft/mobileglues-atlas-cache/
├── config.json
├── manifest.json
├── capture_complete
└── atlases/
    ├── minecraft_textures_atlas_blocks_0.png
    ├── minecraft_textures_atlas_blocks.json
    ├── minecraft_textures_atlas_particles_0.png
    └── minecraft_textures_atlas_particles.json
```

`capture_complete` is written when a capture ran to the end. Without it the cache is
never used.

`manifest.json` pins the format version, the game version, the resource packs that were
active and the SHA-256 of every file of the cache. The per atlas JSON holds the
geometry of the atlas and one entry per sprite:

```json
{
  "atlas": "minecraft:textures/atlas/blocks.png",
  "width": 1024,
  "height": 1024,
  "mipLevel": 0,
  "sprites": [
    {
      "id": "minecraft:block/stone",
      "x": 0,
      "y": 0,
      "width": 16,
      "height": 16,
      "u0": 0.0,
      "u1": 0.015625,
      "v0": 0.0,
      "v1": 0.015625,
      "animated": false,
      "frameCount": 0,
      "source": "minecraft:textures/block/stone.png",
      "sourceSha256": "..."
    }
  ]
}
```

## The second run

When the game asks for an atlas, the loader is intercepted. If the cache is valid the
atlas is not built at all: no texture is read, no PNG is decoded and the stitcher never
runs. The atlas is rebuilt from the cached image and the cached sprite list instead, and
the result is the same object the loader would have produced on its own.

The cache is only used when all of the following hold:

- the format version, the game version and the active resource packs are unchanged
- every file of the cache is present and still hashes to the value in the manifest
- every source resource still hashes to the value that was recorded for it

The last check only reads the bytes of the source files, it never decodes them, so it is
far cheaper than building the atlas.

Animated sprites are the one exception: the cache only holds the first frame of them, so
they are loaded from the resource pack again. There are only a handful of those.

## Configuration

`config.json` is written on the first run:

```json
{
  "enabled": true,
  "skipAtlasCreation": true,
  "validateSources": true
}
```

- `enabled` turns the capture on or off.
- `skipAtlasCreation` allows a valid cache to replace the atlas construction. Turning it
  off keeps the capture but always lets the game build the atlas, which is useful to
  compare the two paths.
- `validateSources` turns the check of the source resources on or off. Turning it off
  makes the validation cheap, but a resource pack that was replaced without changing its
  name would not invalidate the cache.

## Building

The module is a normal Gradle subproject, so it is built by the same workflows as
Sodium:

```
./gradlew :atlas-cache:build
```
