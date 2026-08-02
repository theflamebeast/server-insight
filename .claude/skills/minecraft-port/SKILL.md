---
name: minecraft-port
description: Bump Server Insight to a new Minecraft release — which version numbers to look up and where, the five files that must agree, and how to verify the mixins still apply. Use when asked to update/port/upgrade to a new Minecraft version, to bump Fabric API / Fabric Loader / Loom / Gradle / Java, or when the build breaks after a version change.
---

# Porting to a new Minecraft version

This repo gets bumped every Minecraft release. The work is mechanical; the part
that wastes time is rediscovering where each version number lives. Here they are.

## 1. Look up the versions

All five, in parallel — none depends on another except Fabric API, which is
keyed on the Minecraft version.

| What | Where | Read |
|---|---|---|
| Minecraft | `https://meta.fabricmc.net/v2/versions/game` | first entry with `"stable": true` |
| Fabric Loader | `https://meta.fabricmc.net/v2/versions/loader` | first entry with `"stable": true` |
| Fabric API | `https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%22<MC>%22%5D` | newest `version_number`, e.g. `0.156.0+26.2` |
| Fabric Loom | `https://maven.fabricmc.net/net/fabricmc/fabric-loom/` | highest version directory |
| Gradle | `https://services.gradle.org/versions/current` | the `version` field |

Then read the Fabric release post for that version — it is the only place that
lists Fabric-API-level changes, and it names the Loom/Gradle baseline Fabric
tested against:

- Blog index: `https://fabricmc.net/blog/` → post is `https://fabricmc.net/YYYY/MM/DD/<ver>.html`
- Porting guide: `https://docs.fabricmc.net/develop/porting/`
- Vanilla renames: NeoForge's migration primer for that version (the Fabric
  porting guide links it; Fabric does not maintain its own list)

**Java version comes from Minecraft, not from us.** Check what the release
requires before touching anything — `26.1` and `26.2` are both Java 25. Only
change the Java level if Mojang moved it.

**Don't jump ahead of the ecosystem.** If the newest stable Minecraft has no
Fabric API build yet, stop and say so — bumping Minecraft alone produces a
build that resolves but ships against an API that doesn't exist for it.

## 2. Change the five files — they must all agree

| File | Field |
|---|---|
| `gradle.properties` | `minecraft_version`, `loader_version`, `fabric_version` |
| `build.gradle` | `net.fabricmc.fabric-loom` plugin version; `options.release` + `sourceCompatibility`/`targetCompatibility` **only if Java moved** |
| `gradle/wrapper/gradle-wrapper.properties` | `distributionUrl` |
| `src/main/resources/fabric.mod.json` | `depends.minecraft`, `depends.fabricloader`, `depends.java` |
| `README.md` | the **Compatibility** section |

Two more, only when the Java level changes:
`src/main/resources/serverinsight.mixins.json` (`compatibilityLevel`) and
`.github/workflows/build.yml` (`java-version`).

`mod_version` in `gradle.properties` is a **release** decision, not a port
decision. Leave it alone and ask — the developer decides what number ships.

## 3. Verify — in this order

```bash
./gradlew build
```

Green build means the *compiler* is happy. It does **not** mean the mod runs.

**Then check the mixins**, which are matched by method name at runtime and fail
silently at compile time. This is the actual risk of a port:

```bash
javap -p -cp ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/<MC>/minecraft-merged-deobf-<MC>.jar \
  net.minecraft.client.multiplayer.ClientPacketListener \
  | grep -E 'handleSetTime|handleCommands|handleCommandSuggestions|commands'
```

Expect all three `handle*` methods with their packet parameter, plus the private
`commands` dispatcher field the mixin `@Shadow`s. A missing one means the mod
will throw an injection failure on launch even though the build passed — fix the
mixin, don't ship it.

**Then sweep for quietly-deprecated API.** The build doesn't lint by default, so
a temporary run catches methods that still compile but are on the way out:

```groovy
// build.gradle, in tasks.withType(JavaCompile) — remove after checking
it.options.compilerArgs.addAll(['-Xlint:deprecation', '-Xlint:unchecked'])
```

Zero warnings from `dev/flamebeast/**` means the source is genuinely current.
Ignore anything Loom or Gradle emits about itself.

**Then `./gradlew runClientGameTest`** — always, on a version bump, whatever the
javap check said. It boots a real client against a real dedicated server and
asserts all three injects actually fired, so it catches both a renamed target and
a target that survived but is no longer called on the path we assumed. Takes about
two minutes. Open the screenshot it drops in
`build/run/clientGameTest/screenshots/` afterwards to confirm the chat output
still renders sanely — component styling churns every release.

If the *gametest* breaks rather than the mod, suspect `src/gametest`'s fake
`/version` first. Minecraft ships an op-only `/version`; our literal merges into
it and inherits the op requirement, which silently removes the whole node from a
normal player's command tree and makes the scan no-op. The test ops the player
specifically to work around that, and that workaround is what breaks if Mojang
touches the command.

## 4. What actually breaks

In practice a Minecraft bump breaks this mod in one of three ways, in
descending order of likelihood:

1. **A mixin target got renamed.** Silent at compile time. Covered above.
2. **A `ClientPacketListener` / `Minecraft` accessor moved.** Loud at compile
   time — `serverBrand()`, `getOnlinePlayers()`, `getFps()`, `getCurrentServer()`,
   `getSingleplayerServer()`, `gameMode`, `level`. Check the class hierarchy
   before assuming a method is gone; several are inherited from
   `ClientCommonPacketListenerImpl` and won't show in `javap` on the subclass.
3. **Chat/component API churn.** `ClickEvent`/`HoverEvent` became sealed
   subtypes (`ClickEvent.CopyToClipboard`, `HoverEvent.ShowText`) and Mojang
   keeps touching this area. Loud at compile time.

Permissions (`PermissionSet` / `Permissions.COMMANDS_*`) and identifier accessors
(`dimension().identifier()`, `unwrapKey()`) are the other two spots that have
moved historically — check them if the build reddens there.

## 5. Finish

Update `README.md`'s Compatibility section in the **same commit** as the bump —
it is what users read to decide whether the jar works, and a stale version line
there generates support issues. Then commit and push per the workflow rules in
`CLAUDE.md`, and report the done-checklist with the mixin gate filled in.
