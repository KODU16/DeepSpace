# Deep Space (Unofficial Port)

This repository contains an unofficial NeoForge 1.21.1 port of Deep Space. The
mod provides planetary dimensions, solar systems, space rendering, and
related gameplay logic.

Original mod team: Landfall. Original authors: Mallowwww and confect1ondev.

Current author and unofficial port maintainer: KODU16.

## License

This port remains licensed under MIT. [LICENSE](LICENSE) retains the original
copyright notice for confect1on (2025) and the complete permission and warranty
text. Built mod JARs include this unchanged license and [NOTICE.md](NOTICE.md),
which preserves upstream distribution requests and space imagery attribution.

## Crafting

- DeepSpace Helmet: one iron helmet + one Heart of the Sea, shapeless.
- DeepSpace Terminal: one Tide Armor Trim Smithing Template + one iron block, shapeless.

This port is not an official release from, and is not supported by, the original
authors.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.200 or newer in the 21.1 release line
- Veil 4.x
- Sable 2.0.3
- Java 21 for development and builds

Iris 1.8.12 or a compatible 1.8.x build and BSL 10.x are required only for the
shader-pack point-sun path.
Deep Space does not bundle or redistribute BSL. At load time it recognizes BSL
10.0 through the current 10.1 series and adds the physical center sun to BSL's
native material-lighting coefficient. Other shader packs are left unchanged.

Normal `build` and `check` tasks verify Sable, Sable Companion, and the matching
Rapier runtime so a compile-only dependency cannot pass unnoticed.

Photon is optional. Install Photon and its own dependencies to enable hyper-relay
light effects. Without Photon, the relay model still renders normally and only
the Photon effect is skipped. The engine indicators and enchantment particles
remain available without Photon.

## Development

The Gradle wrapper uses project-relative paths, so the repository can be moved
without changing the build configuration. On Windows, run `gradlew.bat help`
to verify Java and Gradle setup without launching Minecraft.

Normal builds use the local Photomancy library and run Gradle in offline mode,
reusing the local Gradle and NeoForm caches. When dependencies
must intentionally be refreshed, pass `-Puse_local_dependencies=false`.

Sarrion uses the bundled `deepspace:open_nether` noise settings. It preserves
the vanilla Nether biomes, lava sea, surface materials, and bedrock floor while
removing the solid upper density and roof surface rules so the planet has open
sky.

## Planet appearance

A planet entry in `config/planets.json` may set an explicit texture:

```json
{
  "id": "example:planet",
  "texture": "example:textures/planet.png"
}
```

When `texture` is omitted, the server asynchronously samples 100 globally
distributed surface chunks per possible biome in the target dimension, capped
at 300 chunks per planet. Sixteen columns vote for each chunk's dominant map
color before its result enters the palette. Each client creates a 640-by-320 continent-style
 texture from that palette (ten 16 by 16
 chunks per cube face). The layout seed is the planet ID, so the same ID,
palette, and fragmentation setting always produce the same pixels. Explicit
textures bypass this generator.

Set `generatedPlanetTextureFragmentation` from `0.0` to `1.0` in the common
config. Lower values produce broad land masses; higher values produce more
small regions. Its default is `0.35`.

Atmosphere is absent by default. Add an `atmosphere` decoration with a nonzero
ARGB `color` only when the planet needs one; omitting `color` or setting it to
zero disables that layer.

The built-in generated examples are `deepspace:aridia`, a noise-shaped desert planet
at solar distance 1650, and `deepspace:pelagos`, a fixed-deep-ocean planet at
distance 2300. Neither declares an atmosphere or an explicit texture. Aridia
uses the vanilla desert biome and vanilla Overworld terrain shape with oceans
and aquifers disabled, while Pelagos has a continuous ocean layer.

Data packs may add planets under
`data/<namespace>/deepspace/planets/<planet>.json` using the same fields as a
planet entry above. Data-pack planets are additive and override matching IDs
from the common configuration. Run `/reload` to reload, resample, and synchronize
them.

The primary galaxy and its host star use the active save name in game, including
ring-world origins. Internal legacy identifiers remain stable for existing
world data and cached surface maps.

### Space object scale

The common config provides two independent settings, both from `0.1` to `100`
with a default of `1`:

- `spaceObjectSizeScale` changes planet and star dimensions and the complete ring-world geometry.
- `spaceObjectDistanceScale` changes ordinary planet/star coordinates and relay coordinates. Relay sizes stay fixed. Ring worlds and their host stars ignore this distance setting; their sections expand around the host with the size setting.

Both values are saved when a new world first starts. Existing worlds without
saved scale values keep both at `1`, and changing the config does not rescale
an established world. Planet-surface sky models retain their original sizes
and placement. Space collision bounds, HUD/map coordinates, and planet transfers
use the save's scaled geometry. DeepSpace-dimension suffocation is temporarily
disabled.

### Download the Tropica example datapack

[Download deepspace_tropical_planet.zip](https://github.com/KODU16/DeepSpace/raw/refs/heads/main/examples/datapacks/deepspace_tropical_planet.zip)

This example adds **Tropica** to the initial galaxy, linking it to the
`tropicraft:tropics` dimension. It requires **Tropicraft** and **Deep Space**,
and uses a dynamically generated surface texture.

Place the ZIP directly in your world's `datapacks` directory, then re-enter
the world or run `/reload`. Run `/planets list` to check that Tropica is listed.
If using a global datapack loader, you may instead extract the archive into
its configured required-data directory.

The downloadable archive is stored in `examples/datapacks/`, outside all
Gradle resource directories, and is distributed separately from the mod JAR.

If a procedurally textured planet renders incorrectly, run
`/deepspaceclient texturedebug`. The client writes `report.txt` plus the actual
generated PNG files under `deepspace-debug/planet-textures-<timestamp>` in the
game directory. Include that directory and `logs/latest.log` in a bug report.

### Gravity boots

Craft gravity boots shapelessly from iron boots and a heavy core. They use the
vanilla iron-boots texture. Combine gravity boots with any other feet-slot
equipment in a shapeless recipe to add the ability while preserving that
equipment's enchantments, durability, name, and other components.

The ability starts enabled and can be toggled with **K** (rebindable). Its state
is stored on the equipment and synchronized with the server. Only in DeepSpace
galaxy dimensions, a Sable structure within eight blocks directly below the
wearer restores normal player gravity. Other entities, ship physics, planetary
dimensions, and other mods' space dimensions retain their existing gravity rules.
