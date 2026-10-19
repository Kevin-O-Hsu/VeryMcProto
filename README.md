# VeryMcProto

> A **protocol-layer port** that re-implements **Fabric-only protocol mods** as a **pure Paper plugin**.
> The client still uses the original Fabric mods; the server swaps from "Fabric server + server-side mod" to "standard Paper server + this plugin", with identical protocol behavior.

```
Paper 1.21.11 · Java 21 · paperweight userdev · Version 1.21.11-b5
Servux ✅  ·  JEI ✅  ·  Syncmatica ✅  ·  Carpet ✅   (all four modules implemented and tested)
```

> **Author:** Kevin O. Hsu · **GitHub:** https://github.com/Kevin-O-Hsu/VeryMcProto · **Modrinth:** https://modrinth.com/plugin/verymcproto

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

---

## 1. What It Is

**VeryMcProto** is a **Paper plugin** that **re-implements the network protocols and data collection expected by several Fabric protocol mods** on a standard Paper 1.21.11 server, so that a "Fabric client + Paper server" combination behaves exactly like a "Fabric client + vanilla Fabric server mod" combination.

> **It is a *protocol-layer port*, not a port of the Fabric mods themselves.** The client keeps using masa's / endte's / JEI's own Fabric mods; our job is to implement on the Paper server the **custom network channels + server→client data delivery + server-side behavior cooperation** they expect.

Why this is necessary:

- The server-side parts of these protocol mods rely heavily on **NMS internals** (`NaturalSpawner.SpawnState`, `ServerTickRateManager`, `ChunkAccess.getAllReferences()`, `StructureStart.createTag()`, `Recipe.CODEC` + `NbtOps`, `BlockEntity.saveWithFullMetadata()`, etc.) — unreachable via the pure Paper API.
- They use **Mojang's vanilla `CustomPacketPayload`** mechanism (Fabric's `ServerPlayNetworking` is merely a registration wrapper around this vanilla mechanism). Paper's plugin messaging channels map directly to vanilla custom payload channels, and S2C large packets can be sent via NMS `ClientboundCustomPayloadPacket` to bypass plugin messaging size limits.
- Server-side cooperative features (e.g. EasyPlace) use Mixin in the original; Paper has no Mixin runtime, so an equivalent is implemented via **PacketEvents**.

This project uses **paperweight `userdev`** to reference fully-deobfuscated Mojang NMS at dev time; `reobfJar` converts the artifact into a jar that standard Paper loads directly. **It depends on no server patch / Mixin / private fork.**

---

## 2. Protocol Mods

|  Mod            |  Client Mod                                   |  Nature                                                                   |  Status  |
| --------------- | --------------------------------------------- | ------------------------------------------------------------------------- | -------- |
|  **Servux**     |  masa's **MiniHUD / Litematica / Tweakeroo**  |  Server→client **one-way broadcast** (6 providers)                        |  ✅ Full  |
|  **JEI**        |  **JEI** (mezz/JustEnoughItems 1.21.11)       |  **Full server protocol**: recipe sync + cheat + recipe transfer          |  ✅ Full  |
|  **Syncmatica** |  **endte syncmatica**                         |  **Bidirectional, stateful, multi-player shared** schematic repository    |  ✅ Full  |
|  **Carpet**     |  **gnembon/fabric-carpet** (branch 1.21.11)   |  **Portable subset of a behavior-injecting mod**: rule framework (`/carpet`, 18 rules, `carpet.conf`) + fake players (`/player` full family) + `carpet:hello` protocol + `/log` HUD framework (tps/mobcaps) + pure-calculation commands (`/distance` `/info` `/perimeterinfo` `/draw` `/spawn`) |  ✅ Subset |

**Servux** delivers world metadata (difficulty/weather/spawn/seed), TPS/MobCap periodic data, structure bounding boxes, Litematica schematic transmit/paste, entity/block-entity NBT queries, and the EasyPlace precise-placement protocol over `servux:*` channels. Handshake field `MOD_STRING = servux-paper-<version>` (the 1.21.11 client only matches the `servux-` prefix + version segment; channel protocol versions are the authority).

**JEI** (upstream = mezz/JustEnoughItems branch `1.21.11`): three layers — loader-level recipe sync (`fabric:recipe_sync` + `neoforge:recipe_content`, NMS direct send for >1MiB recipe packs), the `jei:*` channel family (8 C2S + 2 S2C; the client's `isJeiOnServer()` gate requires the server to have declared `jei:delete_player_item`), and the server behavior layer (cheat permission model + the line-by-line ported `BasicRecipeTransferHandlerServer`). **The client only needs JEI itself — nothing extra is ever installed on the client.** Protocol deep-dive: [docs/jei/protocol.md](docs/jei/protocol.md).

**Syncmatica**: the server acts as a **central repository** storing `.litematic` files; multiple players upload/download/collaboratively modify placements over a single physical channel + 18 logical PacketTypes + an Exchange session layer, negotiating a full `FeatureSet` on handshake.

**Carpet**: the portable subset of gnembon's Carpet — the `carpet:hello` handshake (`HI` version string = upstream mod_version `1.4.194`), an upstream-faithful rule framework (`/carpet list|setDefault|query|removeDefault`, plain-text `carpet.conf`), the fake-player `/player` family (spawning through the vanilla `placeNewPlayer` pipeline), the `/log` subscription framework with cross-session persistence and tab-list tps/mobcaps HUD, and five pure-calculation commands. See [docs/carpet/architecture.md](docs/carpet/architecture.md).

> 26.1+ introduced hard client-side gates (fabric-prefix MOD_STRING, a new DataTag NBT carrier, lower reassembly caps). This is the frozen 1.21.11 line — those changes do not apply here; see each line's own wire-truth doc.

---

## 3. Requirements

### Server

|  Item      |  Requirement                                                                                                               |
| ---------- | -------------------------------------------------------------------------------------------------------------------------- |
|  Server    |  **Paper 1.21.11** (`api-version: 1.21.11`; standard Paper, no patch / fork needed)                                           |
|  Java      |  **21**                                                                                                                    |
|  Optional  |  **PacketEvents 2.13.0** (only for EasyPlace; if absent, EasyPlace is gracefully skipped — other features are unaffected)  |

### Client

|  Feature family                                                              |  Client must install                                                         |
| ---------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
|  All of Servux (HUD / structures / NBT query / schematic transmit+paste / EasyPlace)  |  **MiniHUD** + **Litematica** + **Tweakeroo** (the masa suite, 1.21.11 LTS)  |
|  JEI recipe sync + cheat + recipe transfer                                  |  **JEI** (mezz/JustEnoughItems, 1.21.11 line — the server protocol runs server-side, no extra client mod)  |
|  Schematic sharing                                                           |  **Syncmatica** (endte client, 1.21.11 LTS)                                  |
|  Carpet rule browser / fake-player HUD                                       |  **Carpet** (gnembon/fabric-carpet branch `1.21.11`, mod_version `1.4.194`)  |

> The client version must match the server's **MC 1.21.11**. The client is the "receiving end" of these features; every protocol field semantic and packet-reassembly behavior is verified against the client source (see `OriginImpl/`).

---

## 4. Installation

1. Get `VeryMcProto-<version>-reobf.jar` from the project Releases page, or build it with `./gradlew build` (the `-reobf` artifact is the deliverable that loads directly on standard Paper; the plain jar is Mojang-mapped, do NOT deploy it).
2. Drop it into the server's `plugins/` directory.
3. **(Optional, only for EasyPlace)** Install the PacketEvents plugin — with it EasyPlace is enabled automatically, without it it is skipped automatically.
4. Restart / `/restart` the server.
5. Players join and install the corresponding client Fabric mods — handshake is automatic.

> **Defensive design**: the four modules are assembled each inside its own try-catch; any one failing only logs and degrades gracefully — **it never blocks server startup** and never affects the other modules.

---

## 5. Architecture

Package root `verymc.top.veryMcProto`, split into a **framework layer** (`framework/` — network / dataproviders / events / debug / permission / reflection / NMS bridge / settings / util) and a **protocol-mod layer** (`mod/<modid>/` — one directory per ported mod: `servux` `jei` `syncmatica` `carpet`). Adding a protocol mod = implement `ModModule`, register in the main class, add commands/permissions to `plugin.yml` — the framework never changes.

Key replacements (Fabric → Paper): `ModInitializer.onInitialize` → `JavaPlugin.onEnable`; Mixin lifecycle hooks → Bukkit events + tick scheduling; Mixin/AccessWidener → reflection / events / PacketEvents / omission; `ServerPlayNetworking` → plugin messaging + NMS `ClientboundCustomPayloadPacket` direct send; `fabric-permissions-api` → `framework.permission.Perms`.

The full framework contract, the three S2C delivery paths, byte-limit model, the **Mixin→Paper disposal matrix**, the Fabric↔Paper per-domain comparison and the build toolchain live in **[`docs/architecture.md`](docs/architecture.md)**.

---

## 6. Feature Matrix

The original Servux has **26 Mixins + 2 AccessWideners**; Syncmatica has **5 server-side Mixins**. Paper has no Mixin runtime, so each is handled per the table below (full matrix: [docs/architecture.md](docs/architecture.md) §5):

|  Feature                              |  This project's handling                                       |  Status  |
| ------------------------------------- | --------------------------------------------------------------- | -------- |
|  Data collection / lifecycle triggers |  Reflection / direct NMS + Bukkit events                        |  ✅      |
|  **EasyPlace** (Tweakeroo)            |  PacketEvents intercept + fix-listener placement veto           |  ✅ needs PacketEvents  |
|  **Mirror fixes** (chest/rail/stairs) |  Inlined fixes on paste (`fix_*` settings)                      |  ✅ (rail/stairs may be less perfect than Mixin)  |
|  **UpdateSuppression**                |  ⛔ Omitted (no Paper equivalent)                               |  ❌      |
|  **Shulker-box stacking**             |  ⛔ Impossible + all code removed (metadata not delivered either)  |  ❌      |
|  **Carpet behaviors**                 |  Portable subset via pure subclasses + events (no Mixin)         |  ✅ subset  |

---

## 7. Docs & Guides

**Start with [`docs/index.md`](docs/index.md)** — the full doc map and suggested reading order (Chinese). Quick pointers:

| Doc | Content |
| --- | --- |
| [`AGENTS.md`](AGENTS.md) | **Canonical repo guide** — architecture, branch/version model, core constraints, conventions |
| [`docs/architecture.md`](docs/architecture.md) ⭐ | Global architecture: framework, network paths & byte limits, **Mixin→Paper matrix (canonical)**, Fabric↔Paper comparison, build toolchain, NMS pitfalls |
| [`docs/servux/protocol.md`](docs/servux/protocol.md) ⭐ | Core Servux network protocol + §9 the authoritative 1.21.11 wire truths |
| [`docs/operations.md`](docs/operations.md) ⭐ | **Ops reference** (the previous README ops sections live here now): commands, permissions, config keys, data layout, troubleshooting |
| [`docs/servux/testing.md`](docs/servux/testing.md) / [`docs/syncmatica/testing.md`](docs/syncmatica/testing.md) | Client compatibility testing (Servux / Syncmatica) |
| [`docs/syncmatica/architecture.md`](docs/syncmatica/architecture.md) + [`docs/syncmatica/protocol.md`](docs/syncmatica/protocol.md) | Syncmatica implementation notes (architecture / protocol) |
| [`docs/jei/protocol.md`](docs/jei/protocol.md) ⭐ | Full JEI protocol |
| [`docs/carpet/architecture.md`](docs/carpet/architecture.md) + [`docs/carpet/protocol.md`](docs/carpet/protocol.md) | Carpet as-built (rules, fake players, `/log`, commands) + `carpet:hello` protocol |

Commands (`/servux`, `/syncmatica`, `/jei`, `/carpet`, `/player`, `/log`, `/distance`, `/info`, `/perimeterinfo`, `/draw`, `/spawn`), permission nodes (incl. LuckPerms recipes), every config key of `servux.json` / `jei.json` / `syncmatica-config.json` / `carpet.conf` / `carpet-logs.json`, the on-disk data layout, and a troubleshooting table live in **[`docs/operations.md`](docs/operations.md)**.

Building from source: `./gradlew build` (reobf jar) · `./gradlew test` · `./gradlew runServer` — JDK 21. Everything else a developer needs (NMS constraints, versioning & branch model, upgrade runbook) lives in [`AGENTS.md`](AGENTS.md).

---

## 8. FAQ

**Q: Why must we use paperweight / NMS instead of the pure Paper API?**
A: Data collection depends heavily on NMS internals; the network layer reuses vanilla `FriendlyByteBuf` / `CompoundTag`; and JEI/Syncmatica large-packet S2C relies on NMS `ClientboundCustomPayloadPacket`. The pure Paper API cannot reach these.

**Q: Why does Syncmatica default to NMS direct send instead of plugin messaging?**
A: Testing showed the pure Fabric syncmatica client is unreachable over the plugin-messaging wire and only reachable via NMS `DiscardedPayload` direct send.

**Q: Do shulker-box stacking / UpdateSuppression work?**
A: ⛔ No. The former alters a global NMS method (no Mixin / no equivalent on Paper; all code removed); the latter needs a Mixin adding an interface to `Level`/`LevelChunk`, and is omitted.

**Q: What happens if PacketEvents isn't installed for EasyPlace?**
A: EasyPlace is skipped automatically (`EasyPlaceBootstrap` reflective load + `catch(Throwable)` fallback); all other channels, JEI, Syncmatica and Carpet are completely unaffected.

**Q: What's the difference between `permission_level` 2 and 3?**
A: Under pure Bukkit op there is **no difference** (both go through the `isOp()` binary). To differentiate levels, use LuckPerms to explicitly grant the corresponding permission node (see [docs/operations.md](docs/operations.md) §2).

**Q: What does pasting a schematic require?**
A: The player needs creative mode + the `servux.provider.litematic_data.paste` permission (governed by `litematic_data:permission_level_paste`, default 0 = everyone).

---

## 9. Credits

This project is a Paper protocol-layer port of the following Fabric protocol mods — full credit to the original authors and the maintainers who keep them alive:

- **Servux** — originally by **masa** ([`maruohon/servux`](https://github.com/maruohon/servux)); now maintained by **sakura-ryoko** ([`sakura-ryoko/servux`](https://github.com/sakura-ryoko/servux)).
- **Litematica / malilib / MiniHUD / Tweakeroo / Item Scroller** — originally by **masa** (`maruohon/*`); the 1.21.11 LTS builds all live under **sakura-ryoko** ([litematica](https://github.com/sakura-ryoko/litematica) · [malilib](https://github.com/sakura-ryoko/malilib) · [minihud](https://github.com/sakura-ryoko/minihud) · [tweakeroo](https://github.com/sakura-ryoko/tweakeroo) · [itemscroller](https://github.com/sakura-ryoko/itemscroller)). These are the client-side receivers of the protocols.
- **Syncmatica** — originally by **endte** ([`End-Tech/syncmatica`](https://github.com/End-Tech/syncmatica)); now maintained by **sakura-ryoko** ([`sakura-ryoko/syncmatica`](https://github.com/sakura-ryoko/syncmatica)).
- **JustEnoughItems (JEI)** — by **mezz** ([`mezz/JustEnoughItems`](https://github.com/mezz/JustEnoughItems), `1.21.11` branch). The JEI upstream of this module; the `fabric:recipe_sync` wire authority is Fabric API (`FabricMC/fabric`, `1.21.11` branch).
- **JEIRecipeBridge** — by **Mrbysco** ([`Mrbysco/JEIRecipeBridge`](https://github.com/Mrbysco/JEIRecipeBridge)). The historical recipe-bridge form this module was migrated from (kept in `OriginImpl/` for the neoforge-layer wire reference).
- **Carpet** — by **gnembon** ([`gnembon/fabric-carpet`](https://github.com/gnembon/fabric-carpet), branch `1.21.11`). The behavior-injecting mod whose portable subset is carried here.

Full link registry: [`docs/references.md`](docs/references.md).

---

## License

This project is licensed under the **GNU Lesser General Public License v3.0 only** — SPDX identifier [`LGPL-3.0-only`](https://spdx.org/licenses/LGPL-3.0-only.html). See the [LICENSE](LICENSE) file.

> The reference archives under `OriginImpl/` belong to their respective authors and licenses: `servux` / `litematica` / `malilib` / `minihud` / `tweakeroo` / `itemscroller` (masa → sakura-ryoko) are LGPL-3.0; `syncmatica` (endte → sakura-ryoko) is CC0. VeryMcProto is an independent Paper re-implementation (a protocol-layer port), not a derivative of their source.

---

<sub>Built for **Paper 1.21.11** · Java 21 · No Mixin / No patch / No private fork</sub>

<sub>A protocol-layer port: client uses the original Fabric mods; server uses standard Paper + this plugin.</sub>
