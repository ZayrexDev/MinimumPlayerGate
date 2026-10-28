# MinimumPlayerGate

A Velocity plugin using Elytrium LimboAPI virtual waiting rooms. Each registered backend has an independent population gate. All queues share one lightweight virtual world; players are assigned to exactly one backend, and populations are never pooled across backends.

## Build and install

This project retains the supplied Java 25 / Velocity API 4.3.0 baseline and compiles against LimboAPI API 1.1.26. Use JDK 25 and Maven 3.8.6 or newer:

```sh
mvn clean verify
```

Install `target/MinimumPlayerGate-1.0.0.jar` and a LimboAPI **runtime build compatible with your exact Velocity build and client protocol** in the proxy's `plugins` folder, then restart. Do not put this plugin on the Minecraft backend. Dependencies are provided by the proxy and LimboAPI; they are not shaded into this JAR. `velocity-plugin.json` is the single plugin descriptor, so annotation processing is intentionally disabled.

The first startup generates `plugins/minimum-player-gate/config.properties` with an entry for every registered server, disabled by default. Edit it and run `/gate reload` from the console or with `minimumplayergate.admin`. Newly registered servers are discovered on reload. Existing unknown-server entries are retained for future registration. Runtime queues and open/closed states are deliberately not persisted across proxy restarts.

See `src/main/resources/config.example.properties` for a complete three-server example. The example is bundled for reference, not automatically applied.

## Configuration and exact semantics

| Setting | Meaning |
| --- | --- |
| `server.<name>.enabled` | Enable gating for this exact, case-sensitive Velocity server name. Default false. |
| `server.<name>.minimum-players` | Open at population **greater than or equal to** this number. Default 4. |
| `server.<name>.close-below` | Close when population is **strictly less than** this number. Default 2; must be between 1 and minimum-players. |
| `server.<name>.close-delay-seconds` | Population must remain below the closing threshold continuously for this duration. Default 30; 0 closes immediately at evaluation. Range 0..86400. |
| `server.<name>.return-to-waiting` | When closed, move backend residents into virtual waiting. If false, leave residents playing but queue new arrivals. Default true. |
| `transfer-timeout-seconds` | Maximum time for entering limbo or completing release, including remaining login steps. Default 30; range 5..300. |
| `backend-retry-seconds` | Minimum interval between backend status probes while an open gate has waiting players. Default 10; range 1..3600. |

Population is the sum of actual backend residents plus ready waiting players plus releases in progress for that backend. An entering-limbo player is not counted toward opening until `onSpawn`. A release remains a reservation until `ServerPostConnectEvent`, disconnection, kick, or timeout. Backend snapshots are filtered to avoid counting a person both in limbo and on a backend.

Example: minimum 4, close-below 2, delay 30 opens with four ready players, remains open with two or three, and closes after 30 continuous seconds with zero or one. With return enabled, the remaining player returns to limbo and waits for three others. To close whenever population falls below the opening minimum, set close-below equal to minimum-players. To implement “strictly more than X,” set minimum-players to X+1.

Gate evaluation happens on events and once per second. Recovery resets the closing timer. Pending spawn/release operations suppress closure until they finish or time out, avoiding false closures during batch transfer. A closed gate with return enabled also collects existing residents after startup or reload. A disabled gate releases existing waiters after a successful backend probe. Changing thresholds resets the closing grace period but preserves the current gate state until reevaluation. An already-issued transfer is allowed to finish after reload.

All configuration is validated before live changes. Invalid booleans, missing fields for retained servers, unknown keys, invalid numbers, or invalid threshold relationships reject reload. Adding default entries uses a temporary file plus atomic replacement where supported; existing settings are retained. Standard Java properties escaping applies to unusual server names.

## Routing and commands

Login selects the first registered server from Velocity's matching forced-host list, or from `servers.try` if no forced host matches. It does **not** silently choose a different server because the preferred gate is closed or its backend is offline. For gated targets, a LAST-order `LoginLimboRegisterEvent` callback queues the player; even an already-open gated login briefly enters limbo to acquire a tracked release. Disabled targets pass the login step without spawning.

After a player enters limbo, release uses `LimboPlayer.disconnect(RegisteredServer)`. This continues the remaining login steps and selects the backend during login, or initiates a normal backend transfer during play. `LimboFactory.passLoginLimbo` is used only when skipping the step before spawning. The plugin does not call LimboAPI implementation methods or Velocity internals.

`ServerPreConnectEvent` inspects the current allowed destination, respects earlier denials, and intercepts closed-gate switches. It uses an awaited event task so the connection cannot proceed before the decision. Switching from a backend into a closed gate enters limbo; switching between waiting queues changes the assignment without spawning another limbo session.

- `/gate`: show per-backend status and queue sizes.
- `/gate join <server>`: request another registered backend. Public by design; normal pre-connect access-control listeners still run on actual release.
- `/gate reload`: reload configuration; requires `minimumplayergate.admin`. Use the console or an authenticated backend session.

In virtual limbo, the session handler recognizes `/gate` and `/gate join <server>` directly. Other proxy commands, including `/server` and admin reload, are not provided through this limbo handler. A limbo player should use `/gate join`.

Authentication plugins must place their login callbacks before this gate callback; LAST helps but cannot guarantee ordering against another LAST subscriber. Test the installed authentication stack. Custom initial-routing plugins that change the destination after the LimboAPI login phase need integration with this routing policy: an unexpected closed initial destination is rejected with a reconnect message instead of attempting to restart login. Other plugins must not overwrite gate decisions at the same event priority or forcibly replace its limbo session. This plugin is population control, not a replacement for backend authorization or authentication.

## Concurrency and failures

A single dedicated executor owns all mutable configuration, backend state, queue assignments, and transfer flags. Network probes complete asynchronously and hand results back to that executor. Velocity event tasks await only the short decision, not the network probe. LimboAPI marshals its spawn/disconnect operations onto the player's network event loop.

Visits carry the actual Player identity in addition to UUID. Old-session callbacks cannot remove a replacement connection with the same UUID. Retired connections cannot re-enter through stale backend snapshots. Each release has a destination-bound, single-use pre-connect permit. Limbo `onDisconnect` during release is expected and does not erase the reservation. Release callbacks, reload generations, target removal, and duplicate callbacks are checked before changing state.

An open gate probes the backend before releasing its waiting batch. Failed or timed-out probes leave players waiting and retry after the configured interval. Backends must answer status pings for this availability check. A successful status ping does not guarantee login success.

LimboAPI's public `disconnect(server)` has no completion future. Successful transfers are observed through Velocity events. A kick during release preserves its reason and disconnects the player rather than bypassing a ban or silently falling back. Missing completion or a denied transfer times out with a reconnect message. Reconnecting queues the player anew. Already-connected backend kicks retain Velocity's normal behavior. The plugin does not automatically pull all crash victims back into limbo or guarantee recovery from every protocol failure. Shutdown disconnects owned waiters/transfers and disposes the virtual world.

## API and Forge compatibility research

Checked on 2026-10-28:

- [PaperMC's current plugin setup guide](https://docs.papermc.io/velocity/dev/creating-your-first-plugin/) lists Java 25 and Velocity API 4.3.0, matching the supplied project.
- [Latest tagged LimboAPI release](https://github.com/Elytrium/LimboAPI/releases/tag/1.1.26) is 1.1.26. Its notes mention Velocity b426+ support; that is not a guarantee that the old release works with every later Velocity build. LimboAPI injects into proxy internals. API compilation and runtime compatibility are separate checks.
- The public API and release implementation were inspected, including [LimboPlayer](https://github.com/Elytrium/LimboAPI/blob/1.1.26/api/src/main/java/net/elytrium/limboapi/api/player/LimboPlayer.java), [LimboFactory](https://github.com/Elytrium/LimboAPI/blob/1.1.26/api/src/main/java/net/elytrium/limboapi/api/LimboFactory.java), and [disconnect implementation](https://github.com/Elytrium/LimboAPI/blob/1.1.26/plugin/src/main/java/net/elytrium/limboapi/server/LimboPlayerImpl.java). Current master was also inspected at `bfef5799f21d73a80155035c3430f6654aa6b9f8`.
- [PaperMC's server compatibility guide](https://docs.papermc.io/velocity/server-compatibility/) separates legacy Forge support from newer versions. It directs Forge 1.13–1.20.1 users to Ambassador; modern forwarding also needs suitable backend support. Proxy support does not establish LimboAPI compatibility.
- [LimboAPI issue #89](https://github.com/Elytrium/LimboAPI/issues/89) remains open and describes modded/legacy Forge connection failures. [PR #275](https://github.com/Elytrium/LimboAPI/pull/275), “Fix direct Forge connection,” is still listed among open pull requests, so it must not be assumed shipped.
- [LimboAPI issue #197](https://github.com/Elytrium/LimboAPI/issues/197) remains open for Forge 1.20.1 disconnects during offline/hybrid authentication. Its report specifically implicates the authentication step; it does not prove every Forge 1.20.1 setup fails.

This gate does not implement Forge handshake translation. Setting return-to-waiting=false avoids forced backend-to-limbo transitions, but still uses limbo on gated entry. Setting enabled=false bypasses this gate's limbo for that backend; it cannot prevent other LimboAPI plugins or LimboAPI itself from affecting the connection. Do not treat either setting as a universal Forge fix.

## Verification and live acceptance checklist

Automated tests cover threshold boundaries, hysteresis and grace resets, independent states, configuration persistence and rejection, release uniqueness, stale callbacks after reconnect, destination permits, earlier access denials, queue switching, forced-host routing, backend failures, transfer timeout, and resident return behavior. These run against the actual API artifacts with mocked network/player implementations.

Before production, test with the exact proxy, LimboAPI runtime, authentication plugins, and modpack:

1. Join with N-1 players; verify waiting. Join the Nth; verify a single release batch and successful gameplay.
2. Drop to exactly close-below, then one below; verify grace, recovery cancellation, and both return-to-waiting modes.
3. Queue simultaneously for two backends; switch queues; disconnect and reconnect during spawn/release.
4. Stop a backend before release, restart it, and verify retry. Also stop it after the status probe; check the kick/timeout and reconnect path.
5. Reload invalid and valid files while players are waiting and transferring. Disable a populated gate and change thresholds.
6. Check authentication order and normal access denials. Confirm kicks/bans are not bypassed.
7. On Forge, test fresh login, repeated reconnects, backend-to-limbo return, and limbo-to-backend release with every supported client/modpack version.

A live Minecraft/Forge client was not available in this implementation session; packet-level and deployment compatibility have not been verified.
