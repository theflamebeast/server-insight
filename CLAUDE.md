# Server Insight

Client-side Fabric mod for Minecraft Java. Adds one command — `/serverinsight` —
that dumps everything the client can *legitimately* infer about the server it is
connected to: address, MOTD, brand, version/protocol, player count, difficulty,
permission level, your own gamemode/ping/coords, world dimension/biome/time/weather,
an estimated TPS, and a best-effort list of server plugins.

**It is client-only and it never needs to be installed on the server.** Everything
it reports comes from packets the vanilla client already receives, plus exactly one
user-triggered probe (see "The plugin scan is the only thing we send").

Published on Modrinth: https://modrinth.com/mod/server-insight

Shared workflow rules: ~/.claude/CLAUDE.md § Every repo. This file only holds what is local.

## Architecture

```
ServerInsightClient          entrypoint: command, join/disconnect resets, tick hook, DNS kickoff
command/ServerInsightCommand /serverinsight — reads state, formats, prints. All output lives here.
state/ServerInsightRuntime   enum singleton; owns all mutable state
state/TimingTracker          TPS from the gameTime counter in ClientboundSetTimePacket
state/PluginScanner          plugin detection (command tree + one tab-completion probe)
state/AddressResolver        async DNS, started on join so the command never blocks
detect/ServerSoftware        pure: brand string -> Paper/Fabric/vanilla/proxy + family
detect/ServerMods            pure: server-declared channels -> server-side mod ids
detect/CommandFingerprints   pure: command name -> plugin it implies (a GUESS)
detect/LocationInfo          pure: geolocation JSON -> where the address points
state/GeoLocator             the only third-party call: IP -> country, cached + throttled
mixin/ServerEntryFlagMixin   flag + hover on each multiplayer server list entry
mixin/EntryGeometryAccessor  interface mixin to reach the entry's layout accessors
text/ChatFormat              branded prefix, gradient header, key/value lines
mixin/…NetworkHandlerMixin   3 injects on ClientPacketListener
```

**A mixin the gametest never loads is an unguarded mixin.** Injection failures
surface at class-load, so a target class the test never touches is never
verified — `ServerEntryFlagMixin` shipped broken exactly once for this reason,
because the server-list entry class only loads when that screen builds a row.
Any new mixin needs a `Class.forName` on its target in the gametest at minimum.

**Nothing in `detect/` holds state** — both are pure functions over data the
client already has, called fresh at command time. Keep them that way; if
something needs to accumulate across packets, it belongs in `state/`.

**Never block the client thread in the command.** DNS was doing it. Anything
that can take unbounded time starts on join and gets read from cache, or runs
async like the plugin scan.

Data flow is one-directional: **mixin → `ServerInsightRuntime` → command reads it.**
The mixin never formats and the command never touches packets. Keep it that way.

- **All mutable state hangs off `ServerInsightRuntime.INSTANCE`.** If you add
  state, it goes there and it gets cleared in *both* `resetForJoin()` and
  `resetForDisconnect()`. Stale state leaking across a server switch is the
  obvious failure mode for a mod like this — a TPS reading from the last server
  is worse than no reading.
- **Anything needing a countdown hangs off `tick()`**, driven by
  `ClientTickEvents.END_CLIENT_TICK`. That's how the scan timeout works; don't
  spin up threads or timers.
- Packet handlers run on the client thread, but a `CompletableFuture` completed
  from elsewhere does not. Hop back with `mc.execute(...)` before touching client
  state or sending chat — `ServerInsightCommand.printPlugins` already does.

## Conventions

- **Tabs, not spaces**, in both Java and Gradle files. Match the surrounding file.
- **All chat output goes through `ChatFormat`** (`kv`, `header`, `prefix`). Never
  build a raw `Component` line in the command — the branded prefix on every line
  is the mod's identity.
- Colors: `ChatFormatting` constants where a vanilla color fits, the named RGB
  constants at the top of `ServerInsightCommand` otherwise. Don't scatter new
  hex literals through the file.
- Values the user will want elsewhere (address, coords, plugin list, individual
  plugin names) get a `ClickEvent.CopyToClipboard` plus a `HoverEvent` saying so.
  This is the mod's main quality-of-life feature; new fields should follow it.
- `POPULAR_PLUGINS` is a data table — lowercase entries, additions only. Adding a
  known plugin means adding a string, never a branch.
- Comments (global rule: none unless WHY) — where one IS earned here: inference
  whose method isn't obvious. The TPS window and the scan timeout are the kind of
  thing that needs a line.
- Keep files small, single responsibility, no spaghetti. If the command file
  starts growing sections, split by concern (it's already close to the limit).
- Prefer minimal safe changes over broad rewrites. Preserve existing behaviour
  unless the request says to change it.
- When removing a feature, clean up its registrations, imports and state. No
  orphaned code.

## Plugin detection has three confidence tiers — keep them separate

1. **Namespaced commands** (`essentials:home` → essentials). Passive, high confidence.
2. **Tab-completion probes.** The only thing this mod sends.
3. **Command-name fingerprints** (`/lp` → LuckPerms, `detect/CommandFingerprints`).
   Passive but a **guess** — a command name is not proof, and `/tab` or `/npc`
   could be anything.

Tier 3 lives in its own bucket (`guessedPlugins()`), gets its own `guess:N`
counter in the summary, and renders greyed out with a trailing `?` and a hover
that says GUESS. **Never merge it into the confirmed buckets.** The per-source
breakdown is the user's only handle on how much to trust the total, and a mod
that inflates its plugin count with guesses is worse than one that finds fewer.

## Geolocation is the one thing that leaves the Minecraft connection

The server-list flags need an IP-to-country lookup, which means calling a
third-party HTTP API. That is a different kind of outbound traffic from
everything else here, and the rules around it are not negotiable:

- **Only look up addresses the user is actually looking at**, and cache the
  result — successes *and* failures — for the session. `GeoLocator.lookup()` is
  called from a render path, so an uncached failure would otherwise fire a
  request every frame.
- **Never send private or loopback addresses.** `isPublicAddress()` filters
  them. A LAN address cannot be geolocated anyway, so sending it leaks the shape
  of someone's home network in exchange for a guaranteed failure.
- **Throttle below the published quota** (45/min; the code sits at 40) and park
  everything on a 429. A server list can hold far more entries than the quota.
- **Never block a render or the client thread on it.** `lookup()` returns cached
  or null and does the work elsewhere; a missing flag means "not known yet".
- The free endpoint is **HTTP, not HTTPS** — its free tier does not offer TLS.
  Nothing sensitive goes over it (a hostname the user is about to connect to
  anyway), but do not add anything that would be.
- Tests must **never hit the live endpoint**. `LocationInfo.fromJson` is pure so
  the parser is tested against a captured response; CI being offline or the
  quota being exhausted must not fail a build.

## The probes are the only thing we send to the Minecraft server

The mod is passive except for tab-completion probes: one
`ServerboundCommandSuggestionPacket` per plugin-listing alias the server actually
advertised (`/version`, `/plugins`, `/pl`, `/about`, the `bukkit:` and `paper:`
forms), capped at `MAX_PROBES`, fired **only** when the user runs the command,
with a shared 100-tick timeout.

Two rules that are easy to break when touching this:

- **Only probe aliases present in the command tree.** Never send speculative
  probes for commands the server never advertised — that is unbounded packets for
  guaranteed-empty replies.
- **The scan completes when every probe has answered**, not on the first reply. A
  fast empty `/pl` response must not cut off the slower `/version` reply that
  carries the actual list.

That restraint is a design rule, not a fear of detection — the developer's
position is that server-side plugins can't meaningfully fingerprint a client
this way, and that's their call. The reasons it stays:

- **Never add background or periodic probing.** Every outbound packet should be
  a direct consequence of the user running the command. Background polling costs
  the user bandwidth and the server work, forever, for a feature nobody is
  looking at — and it makes the mod's behaviour impossible to reason about.
- **Prefer passive sources over probes.** Server-side mod detection reads
  channels the server already announced; the plugin command-tree scan reads a
  packet that already arrived. Both cost nothing. Reach for a probe only when
  there is no passive equivalent.
- One scan at a time — `requestCompletionScan()` rejects a concurrent call rather
  than queueing. Keep that.

## Everything reported is an estimate — label it as one

Servers routinely hide, fake, or restrict this data, and the README promises
users that we're honest about it.

- **TPS is inferred** from the server's own `gameTime` counter over wall-clock
  elapsed, clamped to 0–20, and printed with `(est)`. When there is too little
  recent data — too few samples, too short a span, or the server went quiet —
  `estimatedTps()` returns an **empty** `OptionalDouble` and the command prints
  `unknown`. Never substitute a plausible default: an earlier version returned a
  flat `20.0` whenever it was starved, so servers that suppress time packets
  showed a confident perfect score forever. That is the exact failure this
  section exists to prevent.
- **Don't derive one reading from another and present it as a second
  measurement.** `ms/t` used to be `1000/tps`, which is the TPS restated; real
  ms/t is server-side tick processing time and a client cannot see it. It was
  removed, and the gametest asserts it stays gone.
- **Mod detection is a lower bound**, not a mod list — a server-side mod with no
  networking declares no channels and is invisible. The hover text says so.
- **Plugin detection is a guess.** The count line separates `cmd:`, `tab:` and
  `guess:` sources on purpose — that's the user's only signal about how much to
  trust it. See the three-tier section above.
- When a value can't be determined, print `unknown` / `N/A` in dark gray. Never
  invent a fallback that reads like a real reading, and never let a failed lookup
  throw out of the command — the existing `try`/`catch` around biome lookup and
  `safeOnlineCount` is the pattern.

## Porting to a new Minecraft version

This repo gets bumped every MC release and the lookup URLs are easy to forget.
The full procedure — which versions to look up where, which files to touch, and
how to verify — is in the **`minecraft-port` skill** (`.claude/skills/`). Read it
before starting a bump rather than rediscovering the endpoints.

Current targets live in `gradle.properties`, `build.gradle` (Loom),
`gradle/wrapper/gradle-wrapper.properties` (Gradle), `src/main/resources/fabric.mod.json`
(declared `depends`) and `README.md` (the Compatibility section). All five must agree.

## Workflow

### Build, test, run

```bash
./gradlew build              # compile + remap + jar
./gradlew runClientGameTest  # boots a real client + dedicated server, ~2 min. THE gate.
./gradlew runClient          # interactive dev client, for looking at things yourself
./gradlew genSources         # decompile MC when you need to read vanilla code
```

`runClientGameTest` launches an actual Minecraft window and a real dedicated
server, plays through a join, runs the command and asserts on the result. It is
slow and it is the only check that can fail for the reason this mod actually
breaks — run it before calling any change to detection, formatting, or the
Minecraft version done. CI runs it headless under Xvfb on every push.

- **Use `./gradlew`, never a bare `javac`/`java`.** `java` on PATH here is 24;
  `JAVA_HOME` points at Adoptium **25**, which is what the build needs. The
  wrapper honours `JAVA_HOME`; your shell doesn't.
- **`bin/` is stale Eclipse output.** Gitignored, not the build output, ignore it.
  Real artifacts land in `build/libs/`.
- CI (`.github/workflows/build.yml`) runs `./gradlew build` on JDK 25 and uploads
  the jar as a run artifact, so a build can be grabbed and tested in-game without
  a local Gradle setup.

#### `./gradlew build` passing does NOT mean the mod works

This is the single most important thing to know about this repo. Mixins are
matched by **method name at runtime**, not at compile time. If Mojang renames
`handleSetTime`, the build stays green and the mod dies on launch with an
injection failure. The three targets in
`src/main/java/dev/flamebeast/serverinsight/mixin/ClientPlayNetworkHandlerMixin.java`
are the whole risk surface.

`./gradlew runClientGameTest` is the real answer: the mixin config sets
`defaultRequire: 1`, so a missing target throws while `ClientPacketListener`
loads, and the test crashes on connect before any assertion runs. The test also
asserts each inject actually *fired*, which catches the subtler case where the
method still exists but is no longer called on the path we assumed.

Cheap check without launching the game — javap the remapped jar Loom cached:

```bash
javap -p -cp ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/<VER>/minecraft-merged-deobf-<VER>.jar \
  net.minecraft.client.multiplayer.ClientPacketListener | grep -E 'handleSetTime|handleCommands|handleCommandSuggestions'
```

All three must be present with the expected packet parameter. If they are, the
mixin will apply. `runClient` is the real proof; the javap check is the fast one.

- **This mod ships to real users on Modrinth.** A broken build is a mod that
  crashes someone's client on launch. Don't push a version bump you haven't at
  least verified compiles and whose mixin targets you haven't confirmed exist.
- **Verify against a real server, not just singleplayer.** Most of what this mod
  reports (brand, MOTD, protocol, plugins, ping, TPS) is either absent or
  meaningless in singleplayer. If a change touches detection or formatting, say
  plainly that it's unverified in multiplayer rather than implying it works.
- **Docs to update with the code:** a change to the command's output, the
  detection logic, dependencies, or the supported MC version means `README.md`
  (and this file, if a rule changed) changes in the same commit.
- **Leave clear breadcrumbs.** If you hit something half-finished or deferred,
  say so explicitly in the final message rather than burying it in a comment.

### Done-checklist gates

**Build, Gametest, Multiplayer**, then the standard tail (Review, Committed,
Pushed, CI). A README-only change has no Gametest line. If you skipped the
gametest because it takes two minutes, say that on the line rather than implying
it passed.

```
✅ Build — ./gradlew build, BUILD SUCCESSFUL
✅ Gametest — ./gradlew runClientGameTest, BUILD SUCCESSFUL
⏳ Multiplayer — detection change unverified against a real server
⏳ Review — /code-review is user-only. Self-reviewed the 2-file diff
✅ Committed — 8db830b
✅ Pushed — origin/main
❌ CI — still running, not yet green
```

**Skills for the line-up here:** `minecraft-port`.

- **`origin` is `theflamebeast/server-insight`, branch `main`.**

### Review

- **Why the gate matters here:** with no unit tests and a release channel
  pointing at real users, it is the only thing that hunts correctness bugs before
  they ship.
- **Default effort `high`; `max` for mixins and anything on the release path.** This
  ships to real users on Modrinth and a bad mixin target crashes a client on launch.
- **Areas worth naming in the handoff, in prose** — paths are not an argument: the
  `mixin` package, `detect` (the three confidence tiers), and `text` (where an
  estimate must be labelled as one).
- **Branch `main`, committed straight to it**, so a pushed branch has no diff — pass
  the sha from `.claude/last-reviewed` as the base ref.
