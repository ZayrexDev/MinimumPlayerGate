# Third-party runtime

Bundled source: NanoLimboPlugin API, https://github.com/supern64/NanoLimboPlugin
Pinned commit: 46da82626fff33401fef8c34961075acafafd8c0, 1.13.0-TCP.
Original NanoLimbo author: Nan1t. Plugin adaptation: bivashy. See upstream README for community credits including BoomEaro and SuperN64.
License: GPL-3.0-or-later as stated in source headers. Full upstream license: embedded-limbo/vendor/LICENSE; also included in the assembled JAR under META-INF/nanolimbo/LICENSE.

The source archive includes the complete vendored API module sources/resources and all local modifications. Preserve source/license availability when redistributing the combined binary under the applicable GPL terms.

Local changes to vendored LimboServer.java:
- Retain the bound Channel and await bind completion, propagating startup errors.
- Use the OS-assigned port when binding to port zero; expose the Channel via the existing Lombok getter convention.
- Close the listening channel and await bounded event-loop shutdown.
- Remove the unconditional explicit System.gc() call from startup.

Integration code lives outside the vendor tree. It fixes transport to portable NIO, uses a loopback ephemeral port, configures a spectator virtual world, and supplies forwarding settings. Maven relocates the runtime and dependency packages in the embedded module to avoid conflicts with the proxy. No Velocity internals are included in the runtime.

Dependency versions are pinned in the Maven POMs. Dependency license/notice resources are retained in the shaded artifact; upstream Netty and Configurate notices are also included in vendor resources. Test dependencies are not bundled. NanoLimbo's networking protocol mappings remain upstream code and require updates for future Minecraft versions.

The fork omitted codec_26_2.nbt and tags_26_2.nbt while its Java code requires them at startup. These two resources are supplied from Nan1t/NanoLimbo tag v1.13.0, commit 28168bd4e6d3bba5556b3e3621922fc7e1d33022, under the same upstream license.
