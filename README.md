# VeryMcProto

> A **protocol-layer port** that re-implements **Fabric-only protocol mods** as a **pure Paper plugin**.
> The client still uses the original Fabric mods; the server swaps from "Fabric server + server-side mod" to "standard Paper server + this plugin", with identical protocol behavior.

```
Paper 26.1.2 · Java 25 · paperweight userdev · Version 26.1.2-b3
Servux ✅  ·  JEI ✅  ·  Syncmatica ✅   (all three targets fully implemented; server-side verified on 26.1.2)
```

---

## Table of Contents

- [1. What It Is](#1-what-it-is)
- [2. Protocol Mods](#2-protocol-mods)
- [3. Requirements](#3-requirements)
- [4. Installation](#4-installation)
- [5. Architecture](#5-architecture)
- [6. Feature Matrix](#6-feature-matrix)
- [7. Docs & Guides](#7-docs--guides)
- [8. FAQ](#8-faq)
- [9. Credits](#9-credits)
- [License](#license)

---

## 1. What It Is

**VeryMcProto** is a **Paper plugin** that **re-implements the network protocols and data collection expected by several Fabric protocol mods** on a standard Paper 26.1.2 server, so that a "Fabric client + Paper server" combination behaves exactly like a "Fabric client + vanilla Fabric server mod" combination.

> **It is a *protocol-layer port*, not a port of the Fabric mods themselves.** The client keeps using masa's / endte's / JEI's own Fabric mods; our job is to implement on the Paper server the **custom network channels + server→client data delivery + server-side behavior cooperation** they expect.

Why this is necessary:

- The server-side parts of these protocol mods rely heavily on **NMS internals** — unreachable via the pure Paper API (see the [FAQ](#8-faq)).
- They use **Mojang's vanilla `CustomPacketPayload`** mechanism; Paper's plugin messaging channels map directly onto it, and S2C large packets can go via NMS `ClientboundCustomPayloadPacket`.
- Server-side cooperative features (e.g. EasyPlace) use Mixin in the original; Paper has no Mixin runtime, so an equivalent is implemented via **PacketEvents**.

This project uses **paperweight `userdev`** to reference fully-deobfuscated Mojang NMS at dev time. **Since MC 26.1 Paper no longer supports remapping plugins to Spigot mappings** (Mojang removed server obfuscation), the build artifact is the **Mojang-mapped jar itself** — standard Paper 26.1+ loads it directly. **It depends on no server patch / Mixin / private fork.**

---

## 2. Protocol Mods

| Mod | Client Mod | Nature | Status |
| --- | --- | --- | --- |
| **Servux** | masa's **MiniHUD / Litematica / Tweakeroo** | Server→client **one-way broadcast** (6 providers over `servux:*` channels) | ✅ Full |
| **JEI** | **JEI** (mezz/JustEnoughItems 26.1) | **Full server protocol**: recipe sync + cheat + recipe transfer (`fabric:recipe_sync` / `neoforge:recipe_content` + 10 `jei:*` channels) | ✅ Full |
| **Syncmatica** | **endte syncmatica** | **Bidirectional, stateful, multi-player shared** schematic repository (`syncmatica:main` + 18 PacketTypes + Exchange sessions) | ✅ Full |

**Servux** delivers world metadata / spawn / weather / TPS / MobCap, structure bounding boxes, entity & block-entity NBT queries, and Litematica schematic paste (C2S upload + server-side paste). EasyPlace (Tweakeroo precise placement) is served via PacketEvents. The S2C file-transmit path was removed — the stock 26.1 client has no receiver for it.

**JEI**: recipe sync (loader-level channels, join-time packet ordering via `RecipeSyncJoinOrderer`), the `jei:*` cheat/transfer channels with a server-side permission model, and the line-by-line ported `BasicRecipeTransferHandlerServer`.

**Syncmatica**: the server acts as a central `.litematic` repository; players upload / download / collaboratively modify placements through Exchange request-acknowledgement sessions, with JSON persistence and upload quotas.

> 26.1 introduced hard client-side gates (protocol versions must match exactly; the `servux` handshake string must start with `servux-fabric-<exact upstream MC id>`), a new DataTag NBT wire carrier, and a 16MB client reassembly cap. All of this is implemented and documented in [`docs/09-DELIVERY.md`](docs/09-DELIVERY.md) §26.1.

Protocol deep-dives: [docs/02](docs/02-network-protocol.md) (Servux network) · [docs/21](docs/21-syncmatica-protocol.md) (Syncmatica) · [docs/30](docs/30-jei-protocol.md) (JEI).

---

## 3. Requirements

### Server

| Item | Requirement |
| --- | --- |
| Server | **Paper 26.1.2** (`api-version: '26.1.2'`; standard Paper, no patch / fork needed) |
| Java | **25** |
| Optional | **PacketEvents 2.13.0** (only for EasyPlace; if absent it is gracefully skipped — everything else is unaffected) |

### Client

| Feature family | Client must install |
| --- | --- |
| All of Servux (HUD / structures / NBT query / schematic paste / EasyPlace) | **MiniHUD** + **Litematica** + **Tweakeroo** (the masa suite, 26.1 LTS) |
| JEI recipe sync + cheat + recipe transfer | **JEI** (mezz/JustEnoughItems, 26.1 line) |
| Schematic sharing | **Syncmatica** (endte client, 26.1 LTS) |

> The client version must match the server's **MC 26.1 line (26.1.2)**. The client is the "receiving end" of these protocols; every field semantic and reassembly behavior is verified against the client sources.

---

## 4. Installation

1. Get `VeryMcProto-26.1.2-b3.jar` from the project Releases page, or build it with `./gradlew build` (the Mojang-mapped artifact loads directly on standard Paper 26.1+ — no reobf step exists anymore).
2. Drop it into the server's `plugins/` directory.
3. **(Optional, only for EasyPlace)** Install the PacketEvents plugin.
4. Restart the server; players join with the corresponding client Fabric mods — handshake is automatic.

On startup the console shows:

```
[VeryMcProto] 启动中 (MC 26.1.2, paper)...
[VeryMcProto] 已注册协议 mod: servux
[VeryMcProto] 已注册协议 mod: jei
[VeryMcProto] 已注册协议 mod: syncmatica
[VeryMcProto] 服务端启动完成，已捕获 RegistryAccess 并加载 servux.json。
[VeryMcProto] 框架就绪。
```

> **Defensive design**: the three mods are assembled each inside its own try-catch; any one failing only logs and degrades gracefully — **it never blocks server startup** and never affects the other mods.

Commands (`/servux`, `/syncmatica`, `/jei`), permission nodes (incl. LuckPerms recipes), every config key of `servux.json` / `jei.json` / `syncmatica-config.json`, the on-disk data layout, and a troubleshooting table live in **[`docs/40-configuration.md`](docs/40-configuration.md)**.

---

## 5. Architecture

Package root `verymc.top.veryMcProto`, split into a **framework layer** and a **protocol-mod layer**:

```
verymc.top.veryMcProto
├── VeryMcProto            Main class (JavaPlugin.onEnable assembles the framework + registers the three mods + registers commands)
├── framework/             Framework layer (infrastructure decoupled from any specific protocol mod)
│   ├── network/           plugin messaging channel wrappers, byte-stream codec, splitting, handler registry
│   ├── dataproviders/     Provider registry / scheduler / config hub (used by Servux)
│   ├── event/             Bukkit event → Provider lifecycle bridge (ServerLoad/Join/Quit/Respawn/RegisterChannel + tick)
│   ├── debug/             Generic debug logging engine (independent instances per mod; master + category orthogonal; persisted)
│   ├── permission/ reflect/ nms/ settings/ util/   permission utility, NMS reflection, conversions, config system, JSON utils
└── mod/                   Protocol-mod layer (each ported Fabric protocol mod occupies one directory unit)
    ├── servux/            Servux (app/command/dataproviders/network/easyplace/loggers/schematic/scheduler/util)
    ├── jei/               JEI full server protocol (recipe sync + jei:* channels)
    └── syncmatica/        Syncmatica (communication/exchange/data/litematica/service/...)
```

**Assembly** (`VeryMcProto.onEnable`): initialize the framework → register `servux` → `jei` → `syncmatica` → register the three commands; every step is individually try-catch guarded.

The full Fabric→Paper replacement map (lifecycle hooks → Bukkit events, `ServerPlayNetworking` → plugin messaging + NMS direct send, Mixin → reflection/PacketEvents/omission, etc.), the degradation matrix and the feasibility argument are in [`docs/07-migration-architecture.md`](docs/07-migration-architecture.md).

> **Maintainers & AI assistants**: read **[`AGENTS.md`](AGENTS.md)** first — it is the canonical repo guide (architecture, branch/version model, core design constraints, working conventions).
>
> **Original implementation archive**: `OriginImpl/` holds the original Fabric sources of every ported mod for line-by-line comparison — **in case of divergence, the real source wins**.

---

## 6. Feature Matrix

The original Servux has **26 Mixins + 2 AccessWideners**; Syncmatica has **5 server-side Mixins**. Paper has no Mixin runtime, so each is handled per the table below (full matrix: [docs/07](docs/07-migration-architecture.md) §4):

| Feature | Handling | Status |
| --- | --- | --- |
| Protocol data collection (reading private fields) | **Reflection / direct NMS access** | ✅ |
| Collection triggers / lifecycle | **Bukkit events + tick scheduling** | ✅ |
| **EasyPlace** (Tweakeroo precise placement) | **PacketEvents intercepts `PLAYER_BLOCK_PLACEMENT` + replays `BlockItem.place` side effects** | ✅ needs PacketEvents |
| **Mirror fixes** (chest/rail/stairs) | **Inlined fixes on paste** (`fix_chest_mirror` / `fix_rail_rotations` / `fix_stairs_mirror`) | ✅ (rail/stairs may be less perfect than Mixin) |
| **Fill/Delete via servux tasks** (26.1 new) | `PACKET_C2S_TASK_REQUEST` group (types 14-17): per-tick budgeted fill/delete + InfoHud status sync | ✅ (26.1+) |
| **UpdateSuppression** | ⛔ **Omitted** (no Paper equivalent) | ❌ |
| **Shulker-box stacking** | ⛔ **Impossible + all code removed** (see [FAQ](#8-faq)) | ❌ |
| Debug (`SharedConstants.IS_RUNNING_IN_IDE`) | Omitted | — |

---

## 7. Docs & Guides

**Start with [`docs/00-INDEX.md`](docs/00-INDEX.md)** — the full doc map and suggested reading order (Chinese). Quick pointers:

| Doc | Content |
| --- | --- |
| [`AGENTS.md`](AGENTS.md) | **Canonical repo guide** — architecture, branch/version model, core constraints, conventions |
| [`docs/02-network-protocol.md`](docs/02-network-protocol.md) ⭐ | Core network protocol: `CustomPacketPayload`, `PacketSplitter`, channels, byte layouts |
| [`docs/07-migration-architecture.md`](docs/07-migration-architecture.md) ⭐ | Fabric→Paper architecture comparison, degradation matrix, feasibility |
| [`docs/09-DELIVERY.md`](docs/09-DELIVERY.md) ⭐ | Delivery / byte limits + the authoritative 26.1 migration record (§26.1) |
| [`docs/40-configuration.md`](docs/40-configuration.md) | **Ops reference**: commands, permissions, config keys, data layout, troubleshooting |
| [`docs/10`](docs/10-testing-guide.md) / [`docs/24`](docs/24-syncmatica-testing-guide.md) | Client compatibility testing (Servux / Syncmatica) |
| [`docs/20–24`](docs/20-syncmatica-architecture.md) | Syncmatica implementation notes (architecture / protocol / migration / overview / testing) |
| [`docs/30-jei-protocol.md`](docs/30-jei-protocol.md) ⭐ | Full JEI protocol |

Building from source: `./gradlew build` (Mojang-mapped jar) · `./gradlew test` · `./gradlew runServer` — JDK 25 resolves automatically via the foojay toolchain plugin. Everything else a developer needs (NMS constraints, versioning & branch model, upgrade runbook) lives in [`AGENTS.md`](AGENTS.md).

---

## 8. FAQ

**Q: Why must we use paperweight / NMS instead of the pure Paper API?**
A: Data collection depends heavily on NMS internals (`NaturalSpawner.SpawnState`, `ServerTickRateManager`, `ChunkAccess.getAllReferences()`, `StructureStart.createTag()`, `Recipe.CODEC` + `NbtOps`, `BlockEntity.saveWithFullMetadata()`); the network layer reuses vanilla `FriendlyByteBuf` / `CompoundTag`; and JEI/Syncmatica large-packet S2C relies on NMS `ClientboundCustomPayloadPacket`. The pure Paper API cannot reach these.

**Q: Why does Syncmatica default to NMS direct send instead of plugin messaging?**
A: Testing showed the pure Fabric syncmatica client is unreachable over the plugin-messaging wire and only reachable via NMS `DiscardedPayload` direct send.

**Q: Do shulker-box stacking / UpdateSuppression work?**
A: ⛔ No. The former alters a global NMS method (no Mixin / no equivalent on Paper; all code removed); the latter needs a Mixin adding an interface to `Level`/`LevelChunk`, and is omitted.

**Q: What happens if PacketEvents isn't installed for EasyPlace?**
A: EasyPlace is skipped automatically (`EasyPlaceBootstrap` reflective load + `catch(Throwable)` fallback); all other Servux channels, JEI, and Syncmatica are completely unaffected.

**Q: What's the difference between `permission_level` 2 and 3?**
A: Under pure Bukkit op there is **no difference** (both go through the `isOp()` binary). To differentiate levels, use LuckPerms to explicitly grant the corresponding permission node (see [docs/40](docs/40-configuration.md) §2).

**Q: What does pasting a schematic require?**
A: The player needs creative mode + the `servux.provider.litematic_data.paste` permission (governed by `litematic_data:permission_level_paste`, default 0 = everyone).

---

## 9. Credits

This project is a Paper protocol-layer port of the following Fabric protocol mods — full credit to the original authors and the maintainers who keep them alive:

- **Servux** — originally by **masa** ([`maruohon/servux`](https://github.com/maruohon/servux)); now maintained by **sakura-ryoko** ([`sakura-ryoko/servux`](https://github.com/sakura-ryoko/servux)). The server-side protocol implementation delivering data to MiniHUD / Litematica / Tweakeroo.
- **Litematica / malilib / MiniHUD / Tweakeroo / Item Scroller** — originally by **masa** (`maruohon/*`); now maintained by **sakura-ryoko** since masa retired from active development — the 26.1 LTS builds all live under sakura-ryoko:
  - [`sakura-ryoko/litematica`](https://github.com/sakura-ryoko/litematica) · [`sakura-ryoko/malilib`](https://github.com/sakura-ryoko/malilib) · [`sakura-ryoko/minihud`](https://github.com/sakura-ryoko/minihud) · [`sakura-ryoko/tweakeroo`](https://github.com/sakura-ryoko/tweakeroo) · [`sakura-ryoko/itemscroller`](https://github.com/sakura-ryoko/itemscroller)
  - These are the client-side receivers of the protocols.
- **Syncmatica** — originally by **endte** ([`End-Tech/syncmatica`](https://github.com/End-Tech/syncmatica)); now maintained by **sakura-ryoko** ([`sakura-ryoko/syncmatica`](https://github.com/sakura-ryoko/syncmatica)). The shared schematic central repository.
- **JustEnoughItems (JEI)** — by **mezz** ([`mezz/JustEnoughItems`](https://github.com/mezz/JustEnoughItems), `26.1` branch). **The JEI upstream since 2026-09** — the full server protocol (recipe sync via loader channels + `jei:*` cheat/transfer channels) is ported from it.
- **JEIRecipeBridge** — by **Mrbysco** ([`Mrbysco/JEIRecipeBridge`](https://github.com/Mrbysco/JEIRecipeBridge)). The historical JEI recipe-sync reference (1.21.11 line still uses it; kept in `OriginImpl/` for the neoforge-layer wire reference).

### References

- [Paper dev docs](https://docs.papermc.io/paper/dev/) · [plugin messaging](https://docs.papermc.io/paper/dev/plugin-messaging/) · [PaperWeight guide](https://github.com/PaperMC/paperweight)
- [Minecraft Protocol Wiki](https://wiki.vg/Protocol) (`Custom Payload` packet structure) · [Fabric networking docs](https://docs.fabricmc.net/develop/networking)
- [FabricMC Discussion #4430](https://github.com/orgs/FabricMC/discussions/4430) (Spigot/Paper ↔ Fabric custom-channel evidence)
- Full link registry: [`docs/references.md`](docs/references.md)

---

## License

This project is licensed under the **GNU Lesser General Public License v3.0 only** — SPDX identifier [`LGPL-3.0-only`](https://spdx.org/licenses/LGPL-3.0-only.html). See the [LICENSE](LICENSE) file.

> The reference archives under `OriginImpl/` belong to their respective authors and licenses: `servux` / `litematica` / `malilib` / `minihud` / `tweakeroo` / `itemscroller` (masa → sakura-ryoko) are LGPL-3.0; `syncmatica` (endte → sakura-ryoko) is CC0. VeryMcProto is an independent Paper re-implementation (a protocol-layer port), not a derivative of their source.

---

<sub>Built for **Paper 26.1.2** · Java 25 · No Mixin / No patch / No private fork</sub>

<sub>A protocol-layer port: client uses the original Fabric mods; server uses standard Paper + this plugin.</sub>
