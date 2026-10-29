# MinimumPlayerGate 2 — embedded virtual waiting server

Version 2 removes LimboAPI entirely. It bundles the multiversion NanoLimbo runtime, starts it **inside the proxy JVM**, binds it only to `127.0.0.1` on an automatically assigned free port, and registers a private backend through Velocity's public API. There is no Paper lobby, extra Java process, manually managed port, or separate NanoLimbo plugin to install.

Independent game-server queues share this virtual void world. Players see a spectator waiting world; the gate moves them to their selected game backend when enough players are present, and optionally returns them when the population falls below the closing threshold.

## Install / upgrade from version 1

1. Build with JDK 25 and Maven: `mvn clean verify` from the project root.
2. Stop Velocity. Replace the old MinimumPlayerGate JAR with `target/MinimumPlayerGate-2.0.0.jar`. Install only this final JAR, not `original-*` or `embedded-limbo-*`.
3. Remove LimboAPI if no other installed plugin requires it. This gate no longer requires or loads it; leaving a broken LimboAPI JAR installed can still prevent the proxy from starting.
4. Keep the existing `plugins/minimum-player-gate/config.properties`. All per-backend gate settings retain their meanings. New `limbo.*` defaults are added automatically.
5. Start Velocity. The log reports the virtual waiting endpoint. The reserved name `minimum-player-gate-waiting` must not already exist in `velocity.toml`.

No waiting server needs to be added to `velocity.toml`. Keep your normal game backends in `servers.try` / forced hosts. Velocity chooses the initial destination normally; this plugin redirects closed-gate requests to its virtual server while remembering the intended destination.

The project preserves the supplied Java 25 / Velocity API 4.3.0 build baseline. It uses public Velocity APIs, with no proxy reflection or injected connection handlers. The embedded runtime and its Netty, Adventure, TOML, and configuration libraries are relocated in a separate Maven module before packaging. This prevents those libraries from replacing Velocity's versions. The adapter boundary uses only JDK types.

## Forwarding configuration

By default `limbo.forwarding-mode=AUTO` reads `player-info-forwarding-mode` and `forwarding-secret-file` from `velocity.toml` in the proxy working directory. It supports NONE, LEGACY, MODERN and BUNGEEGUARD. It reads the existing secret without copying it into the plugin config or logging it.

If Velocity starts with a custom configuration path, set `limbo.velocity-config` accordingly. Alternatively, set `limbo.forwarding-mode` explicitly. Set `limbo.forwarding-secret-file` to override the secret file path; an empty value uses Velocity's configured path in AUTO mode, or `forwarding.secret` in explicit mode. Relative paths are relative to the proxy working directory, matching Velocity's file location convention. For Java properties on Windows, prefer forward slashes in paths.

The embedded listener is always loopback-only. It is not an external lobby and should not be exposed through a port forward. Configuration/bind/startup failures block gate-controlled connection handling with a clear administrator message instead of silently disabling gates. Fix the startup error and restart the proxy.

Changes to `limbo.*` require a restart. `/gate reload` rejects such changes while running. Gate settings can be reloaded normally.

## Per-backend settings

The first run generates entries for every registered game server, disabled by default. The embedded waiting backend is excluded. See `src/main/resources/config.example.properties`.

| Setting | Meaning |
| --- | --- |
| `server.<name>.enabled` | Enable gating for this exact Velocity backend name; default false. |
| `server.<name>.minimum-players` | Open when population is **at least** this value; default 4. |
| `server.<name>.close-below` | Close when population is **strictly below** this value; default 2; between 1 and minimum-players. |
| `server.<name>.close-delay-seconds` | Continuous low-population duration before closure; default 30; range 0..86400. |
| `server.<name>.return-to-waiting` | On closure, move remaining backend players to the virtual room. If false, they stay playing and only new arrivals queue. |
| `transfer-timeout-seconds` | Entering/release timeout; default 30; range 5..300. |
| `backend-retry-seconds` | Backend availability retry interval; default 10; range 1..3600. |

Population includes actual backend residents, players waiting for that backend, and releases in progress. Players entering the waiting server are counted only after arrival. One player is never counted twice or assigned to two queues. Pending transfers defer closure so a batch does not trigger a false population drop. State is evaluated on events and once per second.

For example, minimum=4, close-below=2, delay=30 opens at four players, remains open with two or three, and closes after 30 continuous seconds with zero or one. With return enabled, the remaining player waits for three more. Set close-below equal to minimum to close whenever population falls below the opening minimum. Set minimum to X+1 for “more than X.”

Recovery resets the closing timer. Reload validates the whole file before applying it, preserves active queue assignments, resets closing grace, and reevaluates thresholds. Disabling a gate releases its waiters after the backend is reachable. Already-issued transfers finish under their existing permit. Newly registered servers are discovered on reload; removed targets cannot retain live queues. Only configuration is persistent; live connections and gate states reset on proxy restart.

## Commands and connection behavior

- `/gate`: show each backend's state and population.
- `/gate join <server>`: request a backend or change waiting queue.
- `/gate leave`: leave a queue while remaining in the virtual room. Use `/gate join` to join again.
- `/gate reload`: reload gate settings; requires `minimumplayergate.admin`.

Normal proxy commands work in the virtual room, including `/server` when available. Earlier access-control denials are respected; later plugins must not overwrite gate decisions. Entering a queue is not authorization to enter its game backend: normal pre-connect checks run again on release.

Closed destinations are redirected in `ServerPreConnectEvent`, so standard Velocity login routing, forced hosts and authentication remain in control. Changing queues while already waiting does not reconnect the virtual world. Transfers carry a destination-bound, single-use permit, and stale callbacks cannot remove a newer connection with the same UUID. State mutations run on one executor.

Open gates probe backends before releasing queued players. Network failures leave queued players in the room and retry with a delay. An explicit backend rejection, ban, or plugin cancellation ends that queue attempt and shows its reason; it is not retried automatically. Players still connected to the waiting room can choose another destination. A failure entering the waiting room disconnects with a clear message. A transfer timeout disconnects to prevent a late request from overriding a subsequent destination. Already-connected backend kicks retain Velocity's normal behavior; arbitrary crash recovery is not added.

The virtual server is a waiting world, not a gameplay server: no blocks to build on, mobs, persistence, Bukkit plugins or modded gameplay. Populations are independent even though the underlying world is shared. The internal server is registered in Velocity and may appear in other plugins' server lists.

## Minecraft versions and Forge 1.20.1

The pinned runtime includes protocol mappings from Minecraft 1.7.2 through 26.2, including 1.20/1.20.1 (protocol 763). Those are upstream mappings, not a claim that every client or modpack has been tested. Newer protocols require a runtime update. The proxy must also support the chosen client version. Protocol support in the waiting world does not translate arbitrary game backends between Minecraft versions.

For your **Forge 1.20.1** setup, two different problems matter:

1. LimboAPI's proxy-internal compatibility problem is removed by this implementation.
2. Forge's mod/registry synchronization when switching between a vanilla-style virtual world and a modded server still needs a compatible Forge proxy setup.

[PaperMC's compatibility guide](https://docs.papermc.io/velocity/server-compatibility/) directs Forge 1.13–1.20.1 users to Ambassador. [Ambassador's README](https://github.com/adde0109/Ambassador) documents switching between similar servers without a client mod and the Forge Client Reset Packet option for other switching scenarios. Its [non-modded server switching issue](https://github.com/adde0109/Ambassador/issues/22) is particularly relevant to returning from a modded backend to a virtual room. Modern player forwarding also requires suitable backend support, such as [Proxy Compatible Forge](https://github.com/adde0109/Proxy-Compatible-Forge).

No Ambassador build, client reset mod, or forwarding mod is silently installed by this project. Verify the exact versions compatible with your proxy/modpack. If backend-to-waiting return fails on that stack, set `return-to-waiting=false`; this avoids forced returns but does not guarantee initial waiting-to-Forge transfer compatibility. A vanilla-style virtual server cannot guarantee transparent switching for every Forge modpack.

## Build layout and verification

- `src/main/java/xyz/zcraft/mc/MinimumPlayerGate.java`: Velocity gate routing, queues, commands, transfers, lifecycle.
- `GateConfig.java` / `GateState.java`: persistent validated configuration and threshold state machine.
- `embedded-limbo/src/main/java/.../EmbeddedWaitingServer.java`: JDK-only adapter and automatic forwarding settings.
- `embedded-limbo/vendor`: pinned NanoLimboPlugin API sources, protocol assets, and upstream license.
- `plugin/pom.xml`: assembles the already-isolated runtime and gate into the final installable JAR.
- `src/test`: state/configuration tests, mocked Velocity lifecycle tests, and real TCP tests of the packaged JAR.

`mvn clean verify` runs unit tests and packaged-runtime integration tests. The latter load the final JAR with only the JDK as parent classloader, bind an actual loopback server, query several protocol versions, perform Minecraft 1.20.1 login/initial play exchange, and check valid/invalid modern-forwarding signatures. They do not simulate a full Forge client or prove every mod's handshake works. Test fresh login, return-to-waiting, reconnects, simultaneous queues, reload, backend outage, kicks, and grace periods on your actual proxy/modpack before production.

## Upstream provenance

The embedded API is taken from [supern64/NanoLimboPlugin](https://github.com/supern64/NanoLimboPlugin), commit `46da82626fff33401fef8c34961075acafafd8c0` (1.13.0-TCP), which incorporates the recent multiversion work from NanoLimbo and community forks. Research checked on 2026-10-28. The original [NanoLimbo releases](https://github.com/Nan1t/NanoLimbo/releases) also include the 26.2 update.

See `THIRD_PARTY.md` and the included GPL-3.0 license for attribution and local changes. The source distribution includes the runtime source and resources needed to rebuild; nothing is downloaded at plugin startup.
