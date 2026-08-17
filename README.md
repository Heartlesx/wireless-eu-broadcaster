# Wireless EU Broadcaster

Wireless EU Broadcaster is a Minecraft 1.20.1 Forge mod for GTCEu. It adds tiered wireless EU broadcaster machines that can transfer energy to configured targets without running long physical cable lines.

## Compatibility

- Minecraft `1.20.1`
- Forge `47.4.13` or newer 1.20.1 Forge builds
- Currently only supports the Monifactory modpack.
- Jade `11.13.2` or newer (optional integration)
- Java 17

## Features

- Tiered Wireless EU Broadcaster machines from LV through MAX.
- Configurable target selection and transfer behavior.
- In-game broadcaster screen and range preview.
- Optional Jade information integration.
- Client-side nearby-container search and broadcaster status display.

## Installation

1. Install Minecraft `1.20.1` with Forge.
2. Install the required GTCEu version and its dependencies.
3. Place `Wireless-EU-Broadcaster-1.1.2.jar` in the instance `mods` directory.
4. Install Jade if you want the optional integration.

This mod is intended for a GTCEu-based setup. Back up existing worlds before installing a new version.

## Building From Source

The Forge subproject is in `Forge/`; source files are shared from `src/main`. Use Java 17 and the Gradle 8.1 distribution or a compatible ForgeGradle setup:

```text
gradle :Forge:build
```

The release JAR is generated under `Forge/build/libs/`.

## License

Wireless EU Broadcaster is licensed under LGPL-3.0-or-later. See [LICENSE](LICENSE).

