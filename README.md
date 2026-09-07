# VeryMcProto

> A **protocol-layer port** that re-implements **Fabric-only protocol mods** as a **pure Paper plugin**.
> The client still uses the original Fabric mods; the server swaps from "Fabric server + server-side mod" to "standard Paper server + this plugin", with identical protocol behavior.

```
Paper 1.21.11 · Java 21 · paperweight userdev · Version 1.21.11-b1
Servux ✅  ·  JEI Recipe Bridge ✅  ·  Syncmatica ✅   (all three targets fully implemented and tested)
```

---

## Table of Contents

- [1. What It Is](#1-what-it-is)
- [2. Protocol Mods](#2-protocol-mods)
- [3. Requirements](#3-requirements)
- [4. Installation](#4-installation)
- [5. Architecture](#5-architecture)
- [6. Commands](#6-commands)
- [7. Permissions](#7-permissions)
- [8. Configuration](#8-configuration)
- [9. Data Layout](#9-data-layout)
- [10. Feature Matrix](#10-feature-matrix)
- [11. Network Protocol](#11-network-protocol)
- [12. Debugging](#12-debugging)
- [13. Compatibility Testing](#13-compatibility-testing)
- [14. Developers](#14-developers)
- [15. Docs Index](#15-docs-index)
- [16. FAQ](#16-faq)
- [17. Credits](#17-credits)

---

## 1. What It Is

**VeryMcProto** is a **Paper plugin** that **re-implements the network protocols and data collection expected by several Fabric protocol mods** on a standard Paper 1.21.11 server, so that a "Fabric client + Paper server" combination behaves exactly like a "Fabric client + vanilla Fabric server mod" combination.

> **It is a *protocol-layer port*, not a port of the Fabric mods themselves.** The client keeps using masa's / endte's / JEI's own Fabric mods; our job is to implement on the Paper server the **custom network channels + server→client data delivery + server-side behavior cooperation** they expect.

Why this is necessary:

- The server-side parts of these protocol mods rely heavily on **NMS internals** (`NaturalSpawner.SpawnState`, `ServerTickRateManager`, `ChunkAccess.getAllReferences()`, `StructureStart.createTag()`, `Recipe.CODEC` + `NbtOps`, `BlockEntity.saveWithFullMetadata()`, etc.) — unreachable via the pure Paper API.
- They use **Mojang's vanilla `CustomPacketPayload`** mechanism (Fabric's `ServerPlayNetworking` is merely a registration wrapper around this vanilla mechanism). Paper's plugin messaging channels map directly to vanilla custom payload channels, and S2C large packets can be sent via NMS `ClientboundCustomPayloadPacket` to bypass plugin messaging size limits.
- Server-side cooperative features (e.g. EasyPlace) use Mixin in the original; Paper has no Mixin runtime, so an equivalent is implemented via **PacketEvents**.

This project uses **paperweight `userdev`** to reference fully-deobfuscated Mojang NMS at dev time; the artifact is converted by `reobfJar` into a jar that standard Paper loads directly. **It depends on no server patch / Mixin / private fork.**

---

## 2. Protocol Mods

|  Mod                    |  Client Mod                                   |  Nature                                                                 |  Status  |
| ----------------------- | --------------------------------------------- | ----------------------------------------------------------------------- | -------- |
|  **Servux**             |  masa's **MiniHUD / Litematica / Tweakeroo**  |  Server→client **one-way broadcast** (6 providers)                      |  ✅ Full  |
|  **JEI Recipe Bridge**  |  **JEI** + **JEIRecipeBridge**                |  **One-shot S2C** recipe sync on player join                            |  ✅ Full  |
|  **Syncmatica**         |  **endte syncmatica**                         |  **Bidirectional, stateful, multi-player shared** schematic repository  |  ✅ Full  |

### 1. Servux

A server-side protocol mod that delivers data to masa's client mods (MiniHUD / Litematica / Tweakeroo) via `servux:*` custom channels:

- **World metadata** (difficulty/weather/spawn point/seed), **TPS / MobCap** periodic logging
- **Structure bounding boxes** (periodic chunk scanning; rendered by the structure debugger / MiniHUD)
- **Litematica schematic transmit / paste** (S2C delivery of `.litematic`; C2S receives client uploads and pastes them into the world)
- **Entity / block-entity NBT query** (client selects, server returns full NBT)
- **EasyPlace** server-side precise placement protocol (Tweakeroo cooperation; implemented via PacketEvents)

**6 channels** (the network channel name ≠ the provider logical name; source-verified in `ServuxReference.java`):

|  Channel (network name)  |  Provider (logical name)     |  Protocol version  |  Purpose                                                                                                                        |
| ------------------------ | ---------------------------- | ------------------ | ------------------------------------------------------------------------------------------------------------------------------- |
|  `servux:main`           |  `servux_main`               |  —                 |  **Config main channel** (ConfigProvider; **permanently enabled**; **sends no network packets**; only carries global settings)  |
|  `servux:hud_metadata`   |  `hud_data`                  |  2                 |  HUD: world metadata / spawn point / weather / TPS / MobCap / recipes                                                           |
|  `servux:entity_data`    |  `entity_data`               |  1                 |  Entities: block-entity / entity NBT query                                                                                      |
|  `servux:tweaks`         |  `tweaks_data`               |  1                 |  Tweaks: entity / block-entity NBT (same pattern as Entities)                                                                   |
|  `servux:structures`     |  `structure_bounding_boxes`  |  2                 |  Structures: structure bounding boxes (periodic chunk scan)                                                                     |
|  `servux:litematics`     |  `litematic_data`            |  1                 |  Litematics: schematic transmit / paste / bulk entities                                                                         |

> Handshake field `MOD_STRING = servux-paper-1.21.11-b1` (keeps the `servux-` prefix so masa's client recognizes that the server runs the Servux protocol; concrete version negotiation goes through each channel's protocol version).

### 2. JEI Recipe Bridge

On player join, syncs the **server's complete recipe table** to the JEI client, routed by client brand over two vanilla custom payload channels:

- Fabric client → `fabric:recipe_sync`
- NeoForge client → `neoforge:recipe_content`

Sent directly via NMS `ClientboundCustomPayloadPacket` (bypasses the plugin messaging size limit — recipe packs routinely exceed 32KiB). **Pure S2C / one-shot / a single `enabled` config option.** Handshake field `jei-recipe-bridge-paper-1.21.11-b1`.

### 3. Syncmatica

Fundamentally different from Servux (one-way broadcast):

- The **server acts as a central repository** storing `.litematic` files; **multiple players** can upload / download / collaboratively modify placement positions.
- **Bidirectional and stateful**: a single physical channel `syncmatica:main` + **18 logical PacketTypes** + an **Exchange session layer** (request-acknowledgement state machine).
- File storage + JSON persistence + **upload quota / debug** services.
- On handshake, both sides exchange a **FeatureSet** to negotiate the optional-field encoding of metadata / position packets.

**Feature enum** (negotiated on handshake): `CORE` `FEATURE` `MODIFY` `MESSAGE` `QUOTA` `DEBUG` `CORE_EX` `VERSION` `DISPLAY_NAME`. This server advertises the **full FeatureSet** (combined with `MOD_VERSION=1.21.11-b1` — the `-b` build suffix never matches the legacy version regex — to trigger FEATURE exchange so both sides encode with the full set).

---

## 3. Requirements

### Server

|  Item      |  Requirement                                                                                                               |
| ---------- | -------------------------------------------------------------------------------------------------------------------------- |
|  Server    |  **Paper 1.21.11** (`api-version: 1.21`; standard Paper, no patch / fork needed)                                           |
|  Java      |  **21**                                                                                                                    |
|  Optional  |  **PacketEvents 2.13.0** (only for EasyPlace; if absent, EasyPlace is gracefully skipped — other features are unaffected)  |

### Client

|  Feature family                                                              |  Client must install                                                         |
| ---------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
|  All of Servux (HUD / structures / NBT query / schematic paste / EasyPlace)  |  **MiniHUD** + **Litematica** + **Tweakeroo** (the masa suite, 1.21.11 LTS)  |
|  JEI recipe sync                                                             |  **JEI** + **JEIRecipeBridge**                                               |
|  Schematic sharing                                                           |  **Syncmatica** (endte client, 1.21.11 LTS)                                  |

> The client version must match the server's **MC 1.21.11**. The client is the "receiving end" of these features; every protocol field semantic and packet-reassembly behavior is verified against the client source (see `OriginImpl/` for litematica / malilib / syncmatica).

---

## 4. Installation

1. Get `VeryMcProto-1.21.11-b1.jar` from the project Releases page, or build it with `./gradlew build` (the reobf artifact loads directly on standard Paper).
2. Drop it into the server's `plugins/` directory.
3. **(Optional, only for EasyPlace)** Install the PacketEvents plugin.
4. With PacketEvents installed, EasyPlace is enabled automatically; without it, it is skipped automatically.
5. Restart / `/restart` the server.
6. Players join and install the corresponding client Fabric mods — handshake is automatic.

On startup the console shows:

```
[VeryMcProto] 启动中 (MC 1.21.11, paper)...
[VeryMcProto] 已注册协议 mod: servux
[VeryMcProto] 已注册协议 mod: jei_recipe_bridge
[VeryMcProto] 已注册协议 mod: syncmatica
[VeryMcProto] 服务端启动完成，已捕获 RegistryAccess 并加载 servux.json。
[VeryMcProto] 框架就绪。
```

> **Defensive design**: the three mods are assembled each inside its own try-catch; any one failing only logs and degrades gracefully — **it never blocks server startup** and never affects the other mods.

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
│   ├── permission/        Permission utility (replaces fabric-permissions-api)
│   ├── reflect/           NMS reflection utility (cached + defensive; degrades to defaults on version drift)
│   ├── nms/               Bukkit ↔ NMS conversion
│   ├── settings/          Servux config-option system (Bool/Int/String/StringList/List)
│   └── util/              JSON / string utilities (Gson pretty + atomic tmp/move write)
└── mod/                   Protocol-mod layer (each ported Fabric protocol mod occupies one directory unit)
    ├── servux/            Servux (app/command/dataproviders/network/easyplace/loggers/schematic/util)
    ├── jeirecipebridge/   JEI Recipe Bridge
    └── syncmatica/        Syncmatica (communication/exchange/data/litematica/service/...)
```

**Assembly order** (`VeryMcProto.onEnable`): initialize the framework (`ChannelManager` / `DataProviderManager` / `LifecycleBridge`) → register `servux` → `jeirecipebridge` → `syncmatica` in turn → register the `/servux` `/jei` `/syncmatica` commands.

**Key replacements (Fabric → Paper), one-liners**:

- `ModInitializer.onInitialize()` → `JavaPlugin.onEnable()`
- Mixin lifecycle hooks → **Bukkit events** (`ServerLoadEvent` / `PlayerJoinEvent` / `PlayerQuitEvent` / `PlayerRespawnEvent` / `PlayerRegisterChannelEvent`) + BukkitRunnable tick scheduling
- Mixin / AccessWidener (none on Paper) → **reflection / Bukkit events / PacketEvents / degraded omission**
- Fabric `ServerPlayNetworking` → **Paper `Messenger` (plugin messaging) + NMS `ClientboundCustomPayloadPacket` direct send**
- `fabric-permissions-api` → `framework.permission.Perms` (op-level mapping)

> **Original implementation archive**: `OriginImpl/` holds the original Fabric sources of every ported mod (servux / litematica / malilib / syncmatica / JEIRecipeBridge / tweakeroo / minihud / itemscroller / packetevents) for line-by-line comparison — **in case of divergence, the real source wins**.

---

## 6. Commands

### `/servux`

**Permission**: `servux.command` (default: op)

```
/servux                                          Show usage
/servux list                                     List all providers and their enabled state
/servux info <provider:setting|setting>          Show a setting's current value + default
/servux set <provider:setting|setting> <value>   Modify a setting and persist immediately
/servux enable <provider>                        Enable a provider (e.g. hud_data)
/servux disable <provider>                       Disable a provider
/servux search <keyword>                         Fuzzy-search setting names
/servux reload                                   Reload config from servux.json
/servux save                                     Write current config to servux.json
/servux debug ...                                Debug toggles (see below)
/servux litematic ...                            Litematica schematic management (see below)
```

- A **setting's qualified name** is `<provider-logical-name>:<setting-name>`, e.g. `hud_data:share_seed`, `servux_main:permission_level`; the provider prefix can be omitted when unambiguous.
- Provider logical names are in the [channel table in §2](#1-servux): `servux_main` / `hud_data` / `entity_data` / `tweaks_data` / `structure_bounding_boxes` / `litematic_data`.
- `servux_main` (the config main channel) **can never be disabled**; the other 5 can be `enable`d/`disable`d.

**`/servux debug`** — debug logging hot-toggle (runtime-immediate, persisted to `servux.json` immediately):

```
/servux debug                  Show current debug state
/servux debug on|off           Master switch on/off (master only; does not touch categories)
/servux debug status           Show state
/servux debug cat all|none     Enable all / clear all categories
/servux debug cat <name>       Toggle a single category
```

> **Master and categories are two orthogonal dimensions; both must be on for output.** Category values: `lifecycle` `handshake` `network` `packet` `tick` `permission` `provider` `config` `easyplace`.

**`/servux litematic`** — server-side schematic file management (requires the `litematic_data` provider enabled):

```
/servux litematic list                          List .litematic files under schematics/
/servux litematic transmit <file> [player]      Load a schematic and deliver it via servux:litematics
```

> Running `transmit` from the console requires a target player. Files live in `plugins/VeryMcProto/schematics/`.

---

### `/syncmatica`

**Permission**: `syncmatica.command` (default: **true** — all players can use the base command)

```
/syncmatica                                     Show usage
/syncmatica status                              Module state (protocol on/off + debug + config file)   [admin]
/syncmatica save                                Save config to syncmatica-config.json                  [admin]
/syncmatica reload                              Reload from syncmatica-config.json                     [admin]
/syncmatica enable                              Enable the protocol (online players re-handshake)      [admin]
/syncmatica disable                             Soft-disable the protocol (channel kept, no kicks)     [admin]
/syncmatica load                                Register all unloaded .litematic under syncmatics/ as placements  [load]
/syncmatica load <file>                         Register a single .litematic as a placement             [load + load_each]
/syncmatica debug ...                           Debug toggles (see below)                               [debug]
```

> Upload / download / modify / delete of placements **all go through protocol exchanges** (client-side actions); the command only registers a local file as a placement and broadcasts it. `[admin]` requires `syncmatica.command.admin`, `[load]` requires `syncmatica.command.load`, `[load_each]` requires `syncmatica.command.load_each`, `[debug]` requires `syncmatica.command.debug`.

**`/syncmatica debug`** (uses syncmatica's own `SyncmaticaDebug`; independent from `/servux debug`):

```
/syncmatica debug                  Show state
/syncmatica debug on|off           Master switch
/syncmatica debug status           Show state
/syncmatica debug cat all|none     Enable all / clear all categories
/syncmatica debug cat <name>       Toggle a single category (lifecycle handshake network packet exchange)
/syncmatica debug s2c              Show current S2C send path
/syncmatica debug s2c nms|msg      Switch S2C path (NMS direct / plugin messaging) — diagnostic toggle, not persisted
```

> S2C defaults to **NMS `DiscardedPayload` direct send** (the pure Fabric syncmatica client is only reachable via NMS direct send; the plugin-messaging wire is unreachable, verified by testing). `/syncmatica debug s2c msg` temporarily switches to plugin messaging for comparison/troubleshooting.

---

### `/jei`

**Permission**: `jei.command` (default: op)

```
/jei                Show current state + usage
/jei enable         Enable join-time recipe sync (subsequent joiners get synced)
/jei disable        Stop join-time push (channel kept, no kicks; already-online players are unaffected)
```

> **Scope of effect**: only affects players who join **afterward**. Recipe sync is a one-shot join push with no persistent connection to tear down (unlike syncmatica's "interrupt in-progress transfers").

---

## 7. Permissions

### 7.1 Command Permissions

|  Permission node                 |  default   |  Purpose                                              |
| -------------------------------- | ---------- | ----------------------------------------------------- |
|  `servux.command`                |  op        |  All `/servux` subcommands                            |
|  `jei.command`                   |  op        |  `/jei enable\|disable`                               |
|  `syncmatica.command`            |  **true**  |  Base `/syncmatica` command (incl. `load`)            |
|  `syncmatica.command.admin`      |  op        |  `/syncmatica save\|reload\|enable\|disable\|status`  |
|  `syncmatica.command.load`       |  **true**  |  `/syncmatica load` (bulk load)                       |
|  `syncmatica.command.load_each`  |  **true**  |  `/syncmatica load <file>` (single load)              |
|  `syncmatica.command.debug`      |  op        |  `/syncmatica debug`                                  |

### 7.2 Provider Permissions (Runtime)

Servux provider permissions do not use the Bukkit permission `default`; instead they are decided at runtime by `framework.permission.Perms` based on the **`permission_level` setting + op level**. **`Perms.check(player, node, level)` semantics**:

1. If the player is **explicitly granted / denied** the Bukkit permission node (`isPermissionSet`, e.g. set by LuckPerms / a permission attachment), **that result wins**;
2. Otherwise, `level <= 0` → **allow everyone**;
3. Otherwise, it falls back to the **op binary** (`isOp()` true passes, satisfying all `level >= 1` management-class settings).

> So `permission_level` effectively only distinguishes "0 = everyone / ≥1 = op only". **To differentiate levels (e.g. grant level 2 but not level 3), use LuckPerms to explicitly grant the corresponding permission node.**

**Base nodes** look like `servux.provider.<provider-logical-name>`; refined nodes append a suffix to the base:

|  Permission node                                             |  Governing setting (level source)                               |  Controls                                                       |
| ------------------------------------------------------------ | --------------------------------------------------------------- | --------------------------------------------------------------- |
|  `servux.main.admin`                                         |  `servux_main:permission_level_admin` (default 3)               |  ConfigProvider admin operations                                |
|  `servux.main.easy_place`                                    |  `servux_main:permission_level_easy_place` (default 0)          |  EasyPlace placement                                            |
|  `servux.provider.hud_data`                                  |  `hud_data:permission_level` (default 0)                        |  HUD metadata delivery                                          |
|  `servux.provider.hud_data.weather`                          |  `hud_data:weather_permission_level` (default 0)                |  Weather data                                                   |
|  `servux.provider.hud_data.seed`                             |  `hud_data:seed_permission_level` (default 2)                   |  Seed data                                                      |
|  `servux.provider.hud_data.logger`                           |  `hud_data:logger_permission_level` (default 0)                 |  Loggers master toggle                                          |
|  `servux.provider.hud_data.logger.tps`                       |  `hud_data:logger_permission_level`                             |  TPS logger                                                     |
|  `servux.provider.hud_data.logger.mob_caps`                  |  `hud_data:logger_permission_level`                             |  MobCap logger                                                  |
|  `servux.provider.entity_data`                               |  `entity_data:permission_level` (default 0)                     |  Entities base                                                  |
|  `servux.provider.entity_data.nbt_query_override`            |  `entity_data:nbt_query_permission_level` (default 2)           |  NBT query override permission                                  |
|  `servux.provider.entity_data.nbt_allow_player_inventory`    |  `entity_data:player_inventory_permission_level` (default 2)    |  Query a player's inventory                                     |
|  `servux.provider.entity_data.nbt_allow_player_ender_items`  |  `entity_data:player_ender_items_permission_level` (default 2)  |  Query a player's ender chest                                   |
|  `servux.provider.tweaks_data`                               |  `tweaks_data:permission_level` (default 0)                     |  Tweaks base                                                    |
|  `servux.provider.structure_bounding_boxes`                  |  `structure_bounding_boxes:permission_level` (default 0)        |  Structures base                                                |
|  `servux.provider.litematic_data`                            |  `litematic_data:permission_level` (default 0)                  |  Litematics base (transmit/receive)                             |
|  `servux.provider.litematic_data.paste`                      |  `litematic_data:permission_level_paste` (default 0)            |  **Paste** a schematic into the world (requires creative mode)  |

> When `nbt_query_override` is off, the Entities NBT query **falls back to the vanilla permission `minecraft.command.data`** (level 2), aligning with the vanilla `/data` command permission.

### 7.3 LuckPerms Examples

Grant seed access (default is op-only):

```yaml
commands:
  - "lp user <player> permission set servux.provider.hud_data.seed true"
```

Grant paste access to a non-op:

```yaml
commands:
  - "lp user <player> permission set servux.provider.litematic_data.paste true"
```

---

## 8. Configuration

All config is **JSON** (Gson pretty + atomic tmp/move write), located under `plugins/VeryMcProto/`.

### 8.1 `servux.json`

Segmented by provider; each segment holds that provider's settings. **Every key can also be changed via `/servux set <provider:key> <value>`** (no need to hand-edit). Type notation: `int` ranges are written `[min..max] default`.

#### `servux_main` (`servux:main`)

The config main channel — permanently enabled, and sends no network packets (only carries global settings).

|  key                             |  type        |  default  |  description                                           |
| -------------------------------- | ------------ | --------- | ------------------------------------------------------ |
|  `permission_level`              |  int [0..4]  |  0        |  Base permission level (0 = everyone)                  |
|  `permission_level_admin`        |  int [0..4]  |  3        |  Admin-operation permission level                      |
|  `permission_level_easy_place`   |  int [0..4]  |  0        |  EasyPlace permission level                            |
|  `easy_place_validator_enabled`  |  bool        |  true     |  EasyPlace placement validator                         |
|  `default_language`              |  string      |  `en_us`  |  Default language                                      |
|  `debug_log`                     |  bool        |  false    |  Debug master switch                                   |
|  `debug_categories`              |  string[]    |  `[]`     |  Enabled debug categories (orthogonal to `debug_log`)  |

#### `hud_data` (`servux:hud_metadata`)

|  key                         |  type           |  default               |  description                                             |
| ---------------------------- | --------------- | ---------------------- | -------------------------------------------------------- |
|  `permission_level`          |  int [0..4]     |  0                     |  HUD base permission                                     |
|  `update_interval`           |  int [20..300]  |  40                    |  HUD push interval (ticks)                               |
|  `share_weather_status`      |  bool           |  false                 |  Whether to deliver weather                              |
|  `weather_permission_level`  |  int [0..4]     |  0                     |  Weather-data permission                                 |
|  `share_seed`                |  bool           |  false                 |  Whether to deliver the world seed                       |
|  `seed_permission_level`     |  int [0..4]     |  2                     |  Seed-data permission                                    |
|  `loggers_enabled`           |  bool           |  false                 |  Whether loggers are enabled (TPS/MobCap periodic data)  |
|  `loggers_enable_list`       |  string[]       |  `["tps","mob_caps"]`  |  Enabled logger types                                    |
|  `logger_permission_level`   |  int [0..4]     |  0                     |  Loggers-data permission                                 |

#### `entity_data` (`servux:entity_data`)

|  key                                    |  type        |  default  |  description                                                                                |
| --------------------------------------- | ------------ | --------- | ------------------------------------------------------------------------------------------- |
|  `permission_level`                     |  int [0..4]  |  0        |  Base permission                                                                            |
|  `nbt_query_override`                   |  bool        |  false    |  Enable standalone NBT-query permission (otherwise falls back to `minecraft.command.data`)  |
|  `nbt_query_permission_level`           |  int [0..4]  |  2        |  NBT-query permission                                                                       |
|  `fix_allay_gathering`                  |  bool        |  true     |  Fix Allay gathering NBT                                                                    |
|  `nbt_allow_player_inventory`           |  bool        |  true     |  Allow querying a player's inventory                                                        |
|  `nbt_allow_player_ender_items`         |  bool        |  true     |  Allow querying a player's ender chest                                                      |
|  `player_inventory_permission_level`    |  int [0..4]  |  2        |  Inventory-query permission                                                                 |
|  `player_ender_items_permission_level`  |  int [0..4]  |  2        |  Ender-chest-query permission                                                               |

#### `tweaks_data` (`servux:tweaks`)

|  key                 |  type            |  default  |  description            |
| -------------------- | ---------------- | --------- | ----------------------- |
|  `permission_level`  |  int [0..4]      |  0        |  Base permission        |
|  `update_interval`   |  int [40..1200]  |  120      |  Push interval (ticks)  |

> ⛔ The original `stackable_shulkers` / `stackable_shulkers_count` / `stackable_shulkers_fix` **have been removed** — shulker-box stacking is impossible without Mixin on Paper (see the [fallback matrix](#10-feature-matrix)); keeping it would make the client's Tweakeroo enable stacking rendering while the server doesn't cooperate → inconsistency.

#### `structure_bounding_boxes` (`servux:structures`)

|  key                             |  type            |  default                          |  description                     |
| -------------------------------- | ---------------- | --------------------------------- | -------------------------------- |
|  `permission_level`              |  int [0..4]      |  0                                |  Base permission                 |
|  `structures_blacklist_enabled`  |  bool            |  false                            |  Enable structure blacklist      |
|  `structures_whitelist_enabled`  |  bool            |  false                            |  Enable structure whitelist      |
|  `structures_blacklist`          |  string[]        |  `["minecraft:buried_treasure"]`  |  Blacklisted structure IDs       |
|  `structures_whitelist`          |  string[]        |  `[]`                             |  Whitelisted structure IDs       |
|  `update_interval`               |  int [1..1200]   |  40                               |  Scan interval (ticks)           |
|  `timeout`                       |  int [40..1200]  |  600                              |  Structure-scan timeout (ticks)  |

#### `litematic_data` (`servux:litematics`)

|  key                       |  type        |  default  |  description                         |
| -------------------------- | ------------ | --------- | ------------------------------------ |
|  `permission_level`        |  int [0..4]  |  0        |  Base permission (transmit/receive)  |
|  `permission_level_paste`  |  int [0..4]  |  0        |  Paste-into-world permission         |
|  `fix_rail_rotations`      |  bool        |  true     |  Fix rail orientation on paste       |
|  `fix_stairs_mirror`       |  bool        |  true     |  Fix stairs mirroring on paste       |
|  `fix_chest_mirror`        |  bool        |  true     |  Fix chest mirroring on paste        |

### 8.2 `jei-recipe-bridge.json`

|  key        |  type  |  default  |  description                                                                     |
| ----------- | ------ | --------- | -------------------------------------------------------------------------------- |
|  `enabled`  |  bool  |  true     |  Whether to sync recipes to joining players (toggled by `/jei enable\|disable`)  |

A missing / corrupt file is rebuilt from defaults and persisted.

### 8.3 `syncmatica-config.json`

Segmented by service (each service is a sub-object); **prefer managing via `/syncmatica` commands** over hand-editing:

```jsonc
{
  "quota": {                       // upload-quota service
    "enabled": false,              // whether upload byte-quota is enabled (default off)
    "limit": 40000000              // per-player upload byte cap (default ~40MB; progress is not persisted, resets on restart)
  },
  "debug": {
    "doPacketLogging": false       // send/receive packet logging (default off)
  },
  "debugLog": {                    // SyncmaticaDebug runtime snapshot (master + categories; /syncmatica debug persists immediately)
    "master": false,
    "categories": []
  }
}
```

> Quota constrains only `DownloadExchange` (player uploads); `UploadExchange` (player downloads) is not checked.

---

## 9. Data Layout

```
plugins/VeryMcProto/
├── servux.json                 Servux global config (6 provider segments)
├── jei-recipe-bridge.json      JEI config (enabled toggle)
├── syncmatica-config.json      Syncmatica config (quota / debug / debugLog segments)
├── placements.json             Syncmatica placement-metadata persistence (+ .bak / .new atomic write)
├── syncmatics/                 Syncmatica .litematic central repository (player upload / download / share)
│   └── <hash-uuid>.litematic   Filename = hash UUID (/syncmatica load identifies files by this)
└── schematics/                 Servux schematic transmit directory
    └── *.litematic             Loaded by /servux litematic transmit; written by receiveFileTransmit
```

> `schematics/` and `syncmatics/` are created automatically on first access. On server shutdown (`onDisable`), `placements.json` is atomically saved by `SyncmaticManager` (backup → current ← incoming); on startup it is read, and corrupt entries are skipped one-by-one via try/catch and rewritten with corrections.

---

## 10. Feature Matrix

The original Servux has **26 Mixins + 2 AccessWideners**; Syncmatica has **5 server-side Mixins**. Paper has no Mixin runtime, so each is handled per the table below:

|  Feature                                            |  Original impl                                   |  This project's handling                                                                                 |  Status                                          |
| --------------------------------------------------- | ------------------------------------------------ | -------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
|  Protocol data collection (reading private fields)  |  Mixin `@Accessor` / AccessWidener               |  **Reflection / direct NMS access**                                                                      |  ✅                                               |
|  Collection triggers / lifecycle                    |  Mixin `@Inject` hooks                           |  **Bukkit events + tick scheduling**                                                                     |  ✅                                               |
|  **EasyPlace** (Tweakeroo server cooperation)       |  Mixin altering placement logic                  |  **PacketEvents intercepts `PLAYER_BLOCK_PLACEMENT` + manually replays `BlockItem.place` side effects**  |  ✅ needs PacketEvents                            |
|  **Mirror fixes** (chest/rail/stairs)               |  Mixin                                           |  **Inlined fixes on paste** (`fix_chest_mirror` / `fix_rail_rotations` / `fix_stairs_mirror`)            |  ✅ (rail/stairs may be less perfect than Mixin)  |
|  **UpdateSuppression**                              |  Mixin adding interface to `Level`/`LevelChunk`  |  ⛔ **Omitted** (no Paper equivalent)                                                                     |  ❌                                               |
|  **Shulker-box stacking**                           |  Mixin altering a global NMS method              |  ⛔ **Impossible + all code removed**                                                                     |  ❌                                               |
|  Debug (`SharedConstants.IS_RUNNING_IN_IDE`)        |  Mixin                                           |  Omitted                                                                                                 |  —                                               |

### EasyPlace Details

`EasyPlaceListener` intercepts the vanilla `PLAYER_BLOCK_PLACEMENT`, cancels the packet, then calls `PlacementHandler.applyPlacementProtocolV3` to decode the precise state and manually replays `BlockItem.place` side effects (`setBlock` / `setPlacedBy` / placement sound / item shrink / ack). **PacketEvents class references are isolated in `EasyPlaceBootstrap`** (reflective load + `catch(Throwable)` fallback) — without the packetevents plugin installed, EasyPlace is gracefully skipped and the other channels are completely unaffected.

> ⚠️ **Why shulker-box stacking is impossible**: it alters a global NMS method's behavior; Paper has no Mixin and no equivalent (reflection can't change a method's return value; Bukkit events always fail given `maxStackSize=1`; setting the `MAX_STACK_SIZE` component pollutes serialization). Therefore `TweaksDataProvider` **does not deliver** `stackingShulkers` metadata — otherwise Tweakeroo on the client would enable stacking rendering while the server doesn't cooperate → inconsistency.

---

## 11. Network Protocol

> This is the cornerstone of the entire port. See [`docs/02-network-protocol.md`](docs/02-network-protocol.md) and [`docs/09-DELIVERY.md`](docs/09-DELIVERY.md) for details.

### S2C Paths

|  Mod                    |  S2C path                                                                                                              |  Notes                                                                    |
| ----------------------- | ---------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
|  **Servux**             |  plugin messaging (`ProtocolChannel.send` → `player.sendPluginMessage`)                                                |  Large packets split by `PacketSplitter`                                  |
|  **JEI Recipe Bridge**  |  **NMS `ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes))` direct send**                                 |  Recipe packs routinely exceed 32KiB; plugin messaging would reject them  |
|  **Syncmatica**         |  defaults to **NMS `DiscardedPayload` direct send** (`S2C_VIA_NMS=true`; builds a `[Identifier][body]` compound body)  |  `/syncmatica debug s2c msg` switches to plugin messaging for comparison  |

### Byte Limits

- Bukkit `Messenger.MAX_MESSAGE_SIZE` = 1,048,576 (~1MiB) — the plugin-messaging API no longer rejects at 32KiB (old docs claiming 32768 are outdated).
- **The real S2C bottleneck is the vanilla client's 32,767-byte decode limit on `ClientboundCustomPayload`** — exceeding it disconnects the client.
- `PacketSplitter` split constants: **S2C `MAX_PAYLOAD_PER_PACKET_S2C = 31,995`** (leaves headroom for the VarInt header, defending the client's 32,767 limit); receive cap `DEFAULT_MAX_RECEIVE_SIZE_S2C = 64MB`.
- Large packets (Recipe / Litematic schematics / Structures / bulk entities) must be split by `PacketSplitter`. **Syncmatica file-splitting does not reuse `PacketSplitter`**; it implements its own stop-and-wait (`BUFFER_SIZE=16384`, per-chunk ack).

### C2S & Handshake

- The vanilla Paper server **kicks the player** on an unregistered custom payload ("Invalid payload"). This plugin receives C2S via `Messenger.registerIncomingPluginChannel` — registered channels are routed by Paper internally and do not kick.
- **Handshake pitfall**: during the configuration phase, `sendPluginMessage` is silently dropped. The framework uses **`PlayerRegisterChannelEvent`** (the client declaring a channel = it has the corresponding mod = configuration phase complete) as a reliable signal to resend metadata / initiate the handshake in `IDataProvider.onPlayerRegisterChannel` / syncmatica `onPlayerRegisterChannel`.

---

## 12. Debugging

Each mod has an **independent debug engine** (master switch + orthogonal categories; both must be on for output); toggles persist immediately and fully survive restart.

### Servux

```
/servux debug on                 Master on
/servux debug cat all            All categories on (or individually lifecycle/handshake/network/...)
```

Categories: `lifecycle` `handshake` `network` `packet` `tick` `permission` `provider` `config` `easyplace`.

### Syncmatica

```
/syncmatica debug on
/syncmatica debug cat all        Categories: lifecycle handshake network packet exchange
```

Diagnosing syncmatica not working: `on` then `cat all` (or individually `handshake`/`network`/`packet`), and watch the handshake chain: declare channel → `tryStartHandshake` → init pushes `REGISTER_VERSION` → client replies with version → `FeatureSet` → `CONFIRM_USER` → `broadcastTargets`.

S2C-path troubleshooting: `/syncmatica debug s2c` (inspect) → `/syncmatica debug s2c nms|msg` (switch).

### JEI

JEI has no independent debug engine; check state via `/jei` and watch server logs for join-time sync.

### Symptom Lookup

|  Symptom                                 |  Where to look                                                                                                                          |
| ---------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
|  Client connects but receives nothing    |  Check provider enabled (`/servux list`); check `permission_level`; enable the `handshake` category to see whether handshake succeeded  |
|  Large schematic transmit / paste fails  |  Check for the client 32,767 disconnect; enable `network`/`packet` to inspect splitting                                                 |
|  EasyPlace does nothing                  |  Confirm PacketEvents plugin is installed (`softdepend`); enable the `easyplace` category                                               |
|  Syncmatica client can't connect         |  Defaults to NMS direct send; confirm with `/syncmatica debug s2c`; enable `handshake` to inspect the chain                             |
|  JEI recipes don't sync                  |  Confirm `enabled` via `/jei`; note it only affects players joining **afterward**                                                       |

---

## 13. Compatibility Testing

See [`docs/10-testing-guide.md`](docs/10-testing-guide.md) (Servux) and [`docs/24-syncmatica-testing-guide.md`](docs/24-syncmatica-testing-guide.md) (Syncmatica).

### Channel–Mod Mapping

|  Channel                |  MiniHUD                     |  Litematica                  |  Tweakeroo                      |
| ----------------------- | ---------------------------- | ---------------------------- | ------------------------------- |
|  `servux:hud_metadata`  |  ✅ HUD info                  |  —                           |  —                              |
|  `servux:structures`    |  ✅ Structure bounding boxes  |  —                           |  —                              |
|  `servux:entity_data`   |  ✅ NBT query                 |  —                           |  —                              |
|  `servux:tweaks`        |  —                           |  —                           |  ✅ NBT query                    |
|  `servux:litematics`    |  —                           |  ✅ Schematic transmit/paste  |  —                              |
|  EasyPlace              |  —                           |  ✅ Precise placement         |  ✅ Triggers placement protocol  |

### Test Points

1. Install the matching masa mods on the client; after joining, enable their debug (e.g. MiniHUD's `debugMessages`) and observe the handshake.
2. HUD: check whether world info/TPS/MobCap refresh and whether structure bounding boxes render.
3. NBT query: with MiniHUD/Tweakeroo, select an entity/block-entity and check whether NBT is returned.
4. Schematic: `/servux litematic transmit` to deliver; paste-upload from within Litematica.
5. Syncmatica: handshake → upload → download → modify placement → restart to verify persistence → multi-player collaboration.

---

## 14. Developers

### Build

```bash
./gradlew build        # Produce the reobf jar (loads directly on standard Paper)
./gradlew runServer    # Start a local 1.21.11 test server (2G heap)
./gradlew test         # Pure-function unit tests (PacketSplitter/FeatureSet/LitematicaBitArray, etc.)
```

**Build chain**: paperweight `userdev` 2.0.0-beta.21 + `paperDevBundle("1.21.11-R0.1-SNAPSHOT")` (fully-deobfuscated Mojang NMS at dev time) → `reobfJar` converts to Spigot runtime mappings. Reflection uses **Mojang names** (reobf does not transform reflection strings, and Paper's runtime is the Mojang mapping → reflecting on Mojang names is naturally correct).

**Optional dependency**: PacketEvents `compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")` + `plugin.yml: softdepend: [packetevents]` (class references isolated in `EasyPlaceBootstrap`).

> Working on this repo with an AI assistant (or onboarding as a maintainer)? Read **[`AGENTS.md`](AGENTS.md)** first — it is the canonical guide covering the architecture, branch/version model, core design constraints, and working conventions.

### NMS Constraints

- **`CompoundTag`**: `getBoolean/getInt/...` return `Optional`/`OptionalInt`; use `getBooleanOr/getIntOr` or `.orElse()`; `putXxx` returns `void` (not chainable).
- **`FriendlyByteBuf`**: the core of protocol-body encoding (`writeVarInt`/`writeNbt`/`readNbt`).
- **`CustomPacketPayload`**: `record Payload(...) implements CustomPacketPayload` + `static Type<Payload> ID` + `static StreamCodec<FriendlyByteBuf, Payload> CODEC`.
- **`Identifier`**: `Identifier.fromNamespaceAndPath("servux","hud_metadata")` (= Mojang `ResourceLocation`).
- **`DiscardedPayload`**: the NMS direct-send custom-payload carrier (`new DiscardedPayload(Identifier, byte[])`).

### Add a Servux Provider

1. Add a class under `mod/servux/dataproviders/` (`extends DataProviderBase`), declaring its settings.
2. Add the corresponding Handler + Packet under `mod/servux/network/` (channel codec + byte layout).
3. Add the channel constant in `ServuxReference`.
4. Register it in `ServuxModule.onRegister`.
5. See [`docs/03-dataproviders-detail.md`](docs/03-dataproviders-detail.md).

### Add a Protocol Mod

1. Implement `ModModule` under `mod/<newmod>/` (or self-managed assembly like syncmatica).
2. Register it in `VeryMcProto.onEnable`.
3. Add commands / permissions in `plugin.yml`.

### Versioning & Branch Model

Plugin version = **`<MC version>-b<build number>`** — currently `1.21.11-b1`; when Mojang shifts to date-style names (e.g. `26.1`) it naturally becomes `26.1-b1`. The **single source of truth** is `gradle.properties` (`mcVersion` / `buildNumber`): Gradle derives `version` from it, injects it into `plugin.yml` / the jar name / `version.properties`, and `Reference.MC_VERSION` / `Reference.PLUGIN_VERSION` (hence every protocol handshake string such as `servux-paper-1.21.11-b1`) read it back from `version.properties`. Never hardcode a version anywhere else.

| Branch | Purpose |
| --- | --- |
| `dev` | **Daily development line for the latest MC version** — features and in-version fixes land here as plain commits. |
| `main` | **Stable release line for the latest MC version** — receives merges from `dev` only, no direct commits. |
| `ver/<X>-dev` (e.g. `ver/1.21.11-dev`) | **Maintenance dev line for an old MC version X** — bug fixes for the frozen version land here. |
| `ver/<X>` | **Stable release line for an old MC version X** — receives merges from `ver/<X>-dev` only. |

Lifecycle (example: `main` is on MC 26.2, upstream drops 26.3):

1. **Freeze**: cut `ver/26.2` + `ver/26.2-dev` from `main` (== the last 26.2 release — a clean freeze point; never cut from `dev`, which is about to carry 26.3 work).
2. **Follow upstream**: on `dev`, bump `mcVersion=26.3` + the dev bundle and adapt to NMS drift; from then on `dev → main` carries all 26.3 development.
3. **Maintain the old line**: fix 26.2 bugs on `ver/26.2-dev`, merge into `ver/26.2`, bump `buildNumber` **on that branch** (its build counter increments independently).
4. **Forward-port**: a bug fixed on an old line that also exists in the new version gets cherry-picked / ported back to `dev`.

While a version is current (not yet frozen), its fixes go straight through `dev → main` — no `ver/*` pair exists for the current version; the pair is created only at the moment a newer MC version arrives.

Release flow (in-version): on the active dev line (`dev` or `ver/<X>-dev`), bump `buildNumber` → commit → merge into the matching release line (`main` or `ver/<X>`) → `./gradlew build` → tag `v<version>` (e.g. `v1.21.11-b1`).

### Upgrade Minecraft

1. Freeze the old version first: cut the `ver/<old MC version>` + `ver/<old MC version>-dev` pair from `main` (the last release of the old version).
2. On `dev`, bump `mcVersion` in `gradle.properties` and align the paperweight dev bundle (`paperweight.paperDevBundle("<new>-R0.1-SNAPSHOT")`).
3. Re-run `./gradlew build` so paperweight re-applies the new bundle.
4. Re-verify every reflection point in [`docs/04-mixin-analysis.md`](docs/04-mixin-analysis.md) against NMS field / method-signature drift (especially `FriendlyByteBuf`, `CustomPacketPayload`, the `CompoundTag` Optional migration, the `StructureStart.createTag` signature, and the `DiscardedPayload` constructor).

---

## 15. Docs Index

**Strongly recommended to start with [`docs/00-INDEX.md`](docs/00-INDEX.md)** for the full doc map and suggested reading order.

|  Doc                                                                         |  Content                                                                                                |
| ---------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
|  [`docs/01-servux-architecture.md`](docs/01-servux-architecture.md)          |  Original architecture overview: startup flow, `DataProviderManager`, lifecycle, config system          |
|  [`docs/02-network-protocol.md`](docs/02-network-protocol.md) ⭐              |  **Core network protocol**: `CustomPacketPayload`, `PacketSplitter` splitting, 6 channels, byte layout  |
|  [`docs/03-dataproviders-detail.md`](docs/03-dataproviders-detail.md)        |  The 6 providers' data contents + collection (TPS/MobCap) + permission nodes                            |
|  [`docs/04-mixin-analysis.md`](docs/04-mixin-analysis.md)                    |  Itemized list of 26 Mixins + 2 AccessWideners and their migration destinations                         |
|  [`docs/05-schematic-system.md`](docs/05-schematic-system.md) ⭐              |  Litematica schematic system: BitArray/Palette/Selection/Placement/Transmit                             |
|  [`docs/06-fabric-vs-paper.md`](docs/06-fabric-vs-paper.md)                  |  Fabric ↔ Paper framework-diff comparison table                                                         |
|  [`docs/07-migration-architecture.md`](docs/07-migration-architecture.md) ⭐  |  **Complete migration plan**: architecture / network layer / data collection / fallback matrix          |
|  [`docs/09-DELIVERY.md`](docs/09-DELIVERY.md)                                |  Delivery / byte-limit deep dive (with the client 32767 limit evidence)                                 |
|  [`docs/10-testing-guide.md`](docs/10-testing-guide.md)                      |  Servux client compatibility testing                                                                    |
|  `docs/20–24`                                                                |  Complete Syncmatica implementation notes (architecture / protocol / Mixin migration / plan / testing)  |

---

## 16. FAQ

**Q: Why must we use paperweight / NMS instead of the pure Paper API?**
A: Data collection depends heavily on NMS internals (`NaturalSpawner.SpawnState`, `ServerTickRateManager`, `ChunkAccess.getAllReferences()`, `StructureStart.createTag()`, `Recipe.CODEC` + `NbtOps`, `BlockEntity.saveWithFullMetadata()`); the network layer reuses vanilla `FriendlyByteBuf` / `CompoundTag`; and JEI/Syncmatica large-packet S2C relies on NMS `ClientboundCustomPayloadPacket`. The pure Paper API cannot reach these.

**Q: Why does Syncmatica default to NMS direct send instead of plugin messaging?**
A: Testing showed the pure Fabric syncmatica client is unreachable over the plugin-messaging wire and only reachable via NMS `DiscardedPayload` direct send.

**Q: Do shulker-box stacking / UpdateSuppression work?**
A: ⛔ No. The former alters a global NMS method (no Mixin / no equivalent on Paper; all code removed); the latter needs a Mixin adding an interface to `Level`/`LevelChunk`, and is omitted.

**Q: What happens if PacketEvents isn't installed for EasyPlace?**
A: EasyPlace is skipped automatically (`EasyPlaceBootstrap` reflective load + `catch(Throwable)` fallback); all other Servux channels, JEI, and Syncmatica are completely unaffected.

**Q: What's the difference between `permission_level` 2 and 3?**
A: Under pure Bukkit op there is **no difference** (both go through the `isOp()` binary). To differentiate levels, use LuckPerms to explicitly grant the corresponding permission node (see [§7.2](#72-provider-permissions-runtime)).

**Q: What does pasting a schematic require?**
A: The player needs creative mode + the `servux.provider.litematic_data.paste` permission (governed by `litematic_data:permission_level_paste`, default 0 = everyone).

---

## 17. Credits

This project is a Paper protocol-layer port of the following Fabric protocol mods — full credit to the original authors and the maintainers who keep them alive:

- **Servux** — originally by **masa** ([`maruohon/servux`](https://github.com/maruohon/servux)); now maintained by **sakura-ryoko** ([`sakura-ryoko/servux`](https://github.com/sakura-ryoko/servux)). The server-side protocol implementation delivering data to MiniHUD / Litematica / Tweakeroo.
- **Litematica / malilib / MiniHUD / Tweakeroo / Item Scroller** — originally by **masa** (`maruohon/*`); now maintained by **sakura-ryoko** since masa retired from active development — the 1.21.11 LTS builds all live under sakura-ryoko:
  - [`sakura-ryoko/litematica`](https://github.com/sakura-ryoko/litematica) · [`sakura-ryoko/malilib`](https://github.com/sakura-ryoko/malilib) · [`sakura-ryoko/minihud`](https://github.com/sakura-ryoko/minihud) · [`sakura-ryoko/tweakeroo`](https://github.com/sakura-ryoko/tweakeroo) · [`sakura-ryoko/itemscroller`](https://github.com/sakura-ryoko/itemscroller)
  - These are the client-side receivers of the protocols.
- **Syncmatica** — originally by **endte** ([`End-Tech/syncmatica`](https://github.com/End-Tech/syncmatica)); now maintained by **sakura-ryoko** ([`sakura-ryoko/syncmatica`](https://github.com/sakura-ryoko/syncmatica)). The shared schematic central repository.
- **JEIRecipeBridge** — by **Mrbysco** ([`Mrbysco/JEIRecipeBridge`](https://github.com/Mrbysco/JEIRecipeBridge)). The JEI recipe bridge.

### References

- [Paper dev docs](https://docs.papermc.io/paper/dev/)
- [Paper plugin messaging](https://docs.papermc.io/paper/dev/plugin-messaging/)
- [PaperWeight guide](https://github.com/PaperMC/paperweight)
- [Minecraft Protocol Wiki](https://wiki.vg/Protocol) (`Custom Payload` packet structure)
- [Fabric networking docs](https://docs.fabricmc.net/develop/networking)
- [FabricMC Discussion #4430](https://github.com/orgs/FabricMC/discussions/4430) (Spigot/Paper ↔ Fabric custom-channel evidence)
- Sister project **VeryMcBot** (paperweight userdev + NMS reflection paradigm reference)

---

## License

This project is licensed under the **GNU Lesser General Public License v3.0 only** — SPDX identifier [`LGPL-3.0-only`](https://spdx.org/licenses/LGPL-3.0-only.html). See the [LICENSE](LICENSE) file.

> The reference archives under `OriginImpl/` belong to their respective authors and licenses: `servux` / `litematica` / `malilib` / `minihud` / `tweakeroo` / `itemscroller` (masa → sakura-ryoko) are LGPL-3.0; `syncmatica` (endte → sakura-ryoko) is CC0. VeryMcProto is an independent Paper re-implementation (a protocol-layer port), not a derivative of their source.

---

<sub>Built for **Paper 1.21.11** · Java 21 · No Mixin / No patch / No private fork</sub>

<sub>A protocol-layer port: client uses the original Fabric mods; server uses standard Paper + this plugin.</sub>
