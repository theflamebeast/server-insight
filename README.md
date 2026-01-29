## Support
- Donate: https://paypal.me/theflamebeast

## Command
- `/serverinsight` — Sends all details of the server in an organized structure

## Features
- **Server details**: address, MOTD, version, brand (when available)
- **Plugins**:
  - Reads namespaces from the command tree
  - Checks Tab-completion
  - Includes a **copy button** for the detected list and individual plugins
  - Color codes popular plugins and security/anticheat plugins
- **Player details**: gamemode, ping, coordinates
- **World details**: dimension, biome, time, weather
- **Performance estimate**: TPS + ms/t (derived from server time update packets)

## Note
- **TPS and ms/t are estimates**, based on timing packets the server sends.
- **Plugin detection is not guaranteed** — many servers intentionally hide or restrict this information.
- This mod is **client-side** and does not need to be installed on the server.

## Installation
1. Install **Fabric Loader** for your Minecraft version
2. Install **Fabric API**
3. Drop the `.jar` into your `mods` folder
4. Launch the game and run `/serverinsight`

## Compatibility
- **Loader**: Fabric
- **Environment**: Client
- **Minecraft**: 1.21.11 (update this if you support more versions)

## Preview
![Preview](https://cdn.modrinth.com/data/cdJrY41V/images/0fc75292051ee8a174f5f826a19a489b81db2de0.png)

## [Support / Suggestions](https://github.com/theflamebeast/serverinsight/issues)
If you find a server where output looks wrong, please include:
- Minecraft version
- Fabric Loader version
- Fabric API version
- The exact chat output from `/serverinsight`
