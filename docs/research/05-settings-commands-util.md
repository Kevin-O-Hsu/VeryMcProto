# settings-commands-util 迁移笔记

> 原版源码根：`I:/Programming/VeryMcProto/OriginImpl/servux-LTS-1.21.11/src/main/java/fi/dy/masa/servux/`
> 目标包根：`verymc.top.veryMcProto`
> 平台：Paper 1.21.11 + paperweight userdev（Mojang 全映射 NMS，无 Mixin/AW 运行时）
>
> 本模块覆盖三大块：**配置项系统（`settings/`）**、**命令树（`commands/`）**、**工具集（`util/`）**。
> `util/data/tag/` 子树（约 20 个纯算法 NBT 包装类：`BaseData`/`ListData`/`CompoundData`/`Palette`/`BitArray` 等）属 schematic 模块范围，本笔记仅在文件清单中列出，不逐行展开。

---

## 文件清单表

### settings/（配置项系统）

| 原版文件（相对 servux 根） | 行数 | 建议目标包 | NMS依赖 | Mixin/AW依赖 | 迁移方式 |
|---|---|---|---|---|---|
| `settings/AbstractServuxSetting.java` | 145 | `config/` 或 `settings/` | `net.minecraft.network.chat.Component`（显示文本）、`com.mojang.brigadier.exceptions.CommandSyntaxException` | 无 | 适配（Component→Bukkit/Gson 双轨；见下） |
| `settings/IServuxSetting.java` | 67 | `config/` | `net.minecraft.network.chat.Component/ChatFormatting/HoverEvent`、`com.google.gson.JsonElement` | 无 | 适配 |
| `settings/IServuxSettingCallback.java` | 7 | `config/` | 无 | 无 | 照抄 |
| `settings/ServuxBoolSetting.java` | 69 | `config/` | `Component` | 无 | 适配（去 Component） |
| `settings/ServuxIntSetting.java` | 103 | `config/` | `Component` | 无 | 适配 |
| `settings/ServuxStringSetting.java` | 67 | `config/` | `Component` | 无 | 适配 |
| `settings/ServuxListSetting.java` | 90 | `config/` | `Component`、Gson | 无 | 适配 |
| `settings/ServuxStringListSetting.java` | 59 | `config/` | `Component` | 无 | 适配 |

### commands/（命令树）

| 原版文件 | 行数 | 建议目标包 | NMS依赖 | Mixin/AW依赖 | 迁移方式 |
|---|---|---|---|---|---|
| `commands/ICommandProvider.java` | 10 | `command/` | 无（依赖 `interfaces/IServerCommand`） | 无 | 照抄 |
| `commands/CommandProvider.java` | 41 | `command/` | `net.minecraft.commands.{CommandBuildContext,CommandSourceStack,Commands}`、`com.mojang.brigadier.CommandDispatcher` | 无 | **重新设计**（见命令迁移方案） |
| `commands/ServuxCommand.java` | 319 | `command/` | `net.minecraft.commands.*`、`Identifier`、`Component/ChatFormatting/ClickEvent/HoverEvent`、`me.lucko.fabric.api.permissions.v0.Permissions` | 无 | **重新设计**（Brigadier 保留 NMS 反射式注册 或 重写为 Bukkit 命令） |

### util/（工具集）

| 原版文件 | 行数 | 建议目标包 | NMS依赖 | Mixin/AW依赖 | 迁移方式 |
|---|---|---|---|---|---|
| `util/JsonUtils.java` | 481 | `util/` | `BlockPos/Vec3i/Vec3`、`fi.dy.masa.servux.Servux.LOGGER` | 无 | 适配（LOGGER 换 Plugin logger；BlockPos/Vec3 用 NMS） |
| `util/StringUtils.java` | 62 | `util/` | `MutableComponent/Identifier`、**`net.fabricmc.loader.api.FabricLoader`** | 无 | 适配（Fabric Loader→Paper PluginMeta；Component→Bukkit 或保留 NMS） |
| `util/MathUtils.java` | 656 | `util/` | `Vec3/Vec3i`、`org.apache.commons.lang3.math.Fraction` | 无 | 照抄 |
| `util/IntBoundingBox.java` | 183 | `util/` | `BlockPos/Direction/Vec3i/Level/BoundingBox/IntArrayTag/ByteBufCodecs/StreamCodec/Codec` | 无 | 照抄（**含协议字节布局，见下**） |
| `util/PlayerDimensionPosition.java` | 42 | `util/` | `BlockPos/Player/DimensionType` | 无 | 照抄 |
| `util/Timeout.java` | 22 | `util/` | 无 | 无 | 照抄 |
| `util/FileUtils.java` | 11 | `util/` | 无 | 无 | 照抄 |
| `util/BlockUtils.java` | 175 | `util/` | `BuiltInRegistries/Identifier/Block/ChestBlock/BlockState/Property/...` | 无 | 照抄 |
| `util/EntityUtils.java` | 193 | `util/` | `Entity/EntityType/Player/Level/...`、`NbtView` | 无 | 照抄（**依赖 NbtView，必先解决 NbtView 迁移**） |
| `util/InventoryUtils.java` | 28 | `util/` | `DataComponents/ItemStack/BlockItem/ShulkerBoxBlock/ItemContainerContents` | 无 | 照抄 |
| `util/PlacementHandler.java` | 350 | `util/` | `BlockState/Property/BlockPlaceContext/...`、`BlockUtils`、`ServuxConfigProvider` | 无（但调用方走 Mixin） | 照抄算法；**触发处降级**（EasyPlace Mixin 无法迁移） |
| `util/WorldUtils.java` | 17 | `util/` | `Level` | **`IWorldUpdateSuppressor`**（Mixin 接口注入 Level） | **降级/省略**（见下） |
| `util/IWorldUpdateSuppressor.java` | 9 | （废弃） | `Level`（通过 implements） | Mixin 接口注入 | **省略** |
| `util/LayerMode.java` | 60 | `util/` | `StringRepresentable/ByIdMap/ByteBufCodecs/StreamCodec` | 无 | 照抄 |
| `util/LayerRange.java` | 701 | `util/` | `BlockPos/Direction/Entity/Level/Codec/StreamCodec/JsonObject` | 无 | 照抄（**含协议字节布局**） |
| `util/ReplaceBehavior.java` | 44 | `util/` | `StringRepresentable` | 无 | 照抄 |
| `util/PasteLayerBehavior.java` | 43 | `util/` | `StringRepresentable` | 无 | 照抄 |
| `util/SchematicPlacingUtils.java` | 554 | `util/` 或 `schematic/` | 大量 NMS（`ServerLevel/BlockEntity/LevelTicks/ScheduledTick/Blocks/ChestBlock/...`）、`NbtView`/`NbtUtils`/`PositionUtils` | **`WorldUtils.setShouldPreventBlockUpdates`**（→`IWorldUpdateSuppressor` Mixin） | 适配（更新抑制部分降级） |
| `util/position/PositionUtils.java` | 1315 | `util/position/` | `BlockPos/Direction/Vec3i/Mth/Entity/ChunkPos/Level/Mirror/Rotation/WorldBorder/Vec3/AABB` | 无 | 照抄 |
| `util/i18n/i18nLang.java` | 103 | `util/i18n/` | Gson + `Servux.LOGGER` | 无 | 适配（资源路径从 jar assets→plugin jar resource） |
| `util/i18n/i18nManager.java` | 317 | `util/i18n/` | `Component/ChatFormatting/HoverEvent`、`Reference`、jar FileSystem 扫描 | 无 | 适配（jar 内 lang 扫描逻辑照抄；资源根目录改） |
| `util/i18n/i18nOption.java` | 187 | `util/i18n/` | 无 | 无 | 照抄 |
| `util/i18nLang.java` | 1（空） | — | — | — | 删除（旧遗留空文件） |
| `util/log/AnsiColors.java` | 91 | `util/log/` | 无 | 无 | 照抄 |
| `util/log/IAnsiLogger.java` | 30 | `util/log/` | 无 | 无 | 照抄 |
| `util/log/AnsiLogger.java` | 135 | `util/log/` | `Reference.DEV_DEBUG/ANSI_MODE` | 无 | 适配（Reference 字段换 Paper 来源；可选改用 Plugin logger） |
| `util/nbt/NbtKeys.java` | 197 | `util/nbt/` | 无 | 无 | 照抄 |
| `util/nbt/NbtUtils.java` | 360 | `util/nbt/` | `CompoundTag/Tag/ListTag/DoubleTag/NbtAccounter/NbtIo/UUIDUtil/BlockPos/Vec2/Vec3/Vec3i/NbtOps/Codec/MapCodec/DataResult` | 无 | 照抄（**核心 NBT 编解码工具**） |
| `util/nbt/NbtView.java` | 231 | `util/nbt/` | `CompoundTag/RegistryAccess/ProblemReporter/ValueInput/ValueOutput/TagValueInput/TagValueOutput/ValueInputContextHelper/Codec/MapCodec/DynamicOps` | **`IMixinNbtReadView`/`IMixinNbtWriteView`**（@Accessor） | **重写**（绕开 Mixin，见下详方案） |
| `util/data/Constants.java` | 31 | `util/data/` | 无 | 无 | 照抄 |
| `util/data/FileType.java` | 94 | `util/data/` | `StringRepresentable` | 无 | 照抄 |
| `util/data/Schema.java` | 250 | `util/data/` | `StringRepresentable/ByteBufCodecs/StreamCodec` | 无 | 照抄 |
| `util/data/tag/converter/DataConverterNbt.java` | 190 | `util/data/tag/` | `Tag/CompoundTag/ListTag/各具体 Tag` | 无 | 照抄 |
| `util/data/tag/DataView.java` | 230 | `util/data/tag/` | `Codec/DynamicOps/Tag` | 无 | 照抄 |
| `util/data/tag/{BaseData,ArrayData,ByteArrayData,ByteData,CompoundData,DoubleData,EmptyData,FloatData,IntArrayData,IntData,ListData,LongArrayData,LongData,NumberData,ShortData,StringData}.java` | — | `util/data/tag/` | 纯 Java + NMS Tag | 无 | 照抄（schematic 共享） |
| `util/data/tag/util/{DataByteBufUtils,DataFileUtils,DataOps,DataTypeUtils,SizeTracker}.java` | — | `util/data/tag/util/` | NMS Tag/ByteBuf | 无 | 照抄 |

**Mixin 依赖（NbtView 必需，需替代）：**

| Mixin 文件 | 行数 | 作用 | 迁移方式 |
|---|---|---|---|
| `mixin/nbt/IMixinNbtReadView.java` | 18 | `@Accessor` 读 `TagValueInput.context`/`input(CompoundTag)` | 反射（见 NbtView 方案） |
| `mixin/nbt/IMixinNbtWriteView.java` | 18 | `@Accessor` 读 `TagValueOutput.ops`/`output(CompoundTag)` | 反射（见 NbtView 方案） |

---

## 逐文件详解

# 第一部分：settings/ 配置项系统

---

### IServuxSettingCallback.java（7 行）
- **职责**：配置项值变更回调接口。
- **关键方法**：`void onValueChanged(IServuxSetting<T> setting, T oldValue, T value)`
- **NMS依赖**：无。
- **迁移方式**：**照抄**。

---

### IServuxSetting.java（67 行）
- **职责**：单个配置项（Setting）的接口契约：名字/显示名/注释/示例值/当前值/默认值、字符串↔值互转、JSON 读写。
- **关键方法签名**：
  - `String name()` / `Component prettyName()` / `Component comment()` / `List<String> examples()`
  - `IDataProvider dataProvider()`
  - `T getDefaultValue()` / `T getValue()` / `void setValueNoCallback(T)` / `void setValue(T) throws CommandSyntaxException`
  - `void updateExamples(List<String>)`
  - `void setValueFromString(String) throws CommandSyntaxException`（命令 `set` 入口）
  - `boolean validateString(String)` / `String valueToString(Object)` / `T valueFromString(String)`
  - `void readFromJson(JsonElement)` / `JsonElement writeToJson()`
  - `default Component shortDisplayName()`（行 50-60）：`prettyName()` 加 hover（comment + qualifiedName）+ 黄色。
  - `default String qualifiedName()`（行 62）：`dataProvider().getName() + ":" + name()`。
- **NMS依赖**：`Component`、`ChatFormatting`、`HoverEvent.ShowText`、`JsonElement`、`IDataProvider`。
- **迁移方式**：**适配**。
  - Component 全部保留为 NMS `net.minecraft.network.chat.Component`（Paper 运行时即 Mojang 映射，可直接用）；命令交互需把 Component 经 `Bukkit.getServer().message`/`audience` 发给玩家。若想纯 Paper API，可换 `net.kyori.adventure.text.Component`（Paper 原生），但需改 `withStyle/withHoverEvent` 调用为 Adventure API。
  - `JsonElement` 用 Gson（已带）。
- **风险点**：`shortDisplayName()` 用 `Component.copy().withStyle(...)` 是 Mojang NMS 风格；Paper 上 Adventure 组件的 hover API 不同，若改 Adventure 需逐处改。

---

### AbstractServuxSetting.java（145 行）
- **职责**：`IServuxSetting<T>` 抽象基类，持有 name/prettyName/comment/defaultValue/value/examples/dataProvider/callback，提供 setValue 触发 callback 的统一流程。
- **关键字段**（类型 名 = 默认值）：
  - `String name`；`Component prettyName`；`Component comment`；`T defaultValue`；`T value = defaultValue`；`List<String> examples`；`IDataProvider dataProvider`；`@Nullable IServuxSettingCallback<T> callback`。
- **关键方法**：
  - 3 个构造器（行 24/37/42），分别带/不带 examples、带/不带 callback。
  - `T getDefaultValue()`/`T getValue()`（行 49/56）
  - `void setValueNoCallback(T value)`（行 66）：仅赋值，不触发回调（**配置文件读取时用，避免误触发**）。
  - `void setValue(T value) throws CommandSyntaxException`（行 72）：记录 oldValue → setValueNoCallback → `onValueChanged(oldValue,value)`。
  - `protected void onValueChanged(T,T)`（行 92）：转发给 callback。
  - `void setValueFromString(String)`（行 101）：validateString → valueFromString → setValue。
  - `Component prettyName()`/`comment()`（行 116/126）：若为 null 则走 `StringUtils.translate("servux.config."+dataProvider.getName()+"."+name+".{name|comment}")` i18n 回退。
- **NMS依赖**：`Component`、`CommandSyntaxException`、`IDataProvider`、`StringUtils`。
- **迁移方式**：**适配**（Component 处理同上；其余照抄）。
- **风险点**：i18n 回退依赖 `StringUtils.translate` → `ServuxConfigProvider.LANG`，需先建好 i18nManager 单例。

---

### ServuxBoolSetting.java（69 行）
- **职责**：布尔配置项。examples 固定 `["true","false"]`。
- **关键方法**：
  - `boolean validateString(String)`（行 32）：`equalsIgnoreCase("true"|"false")`。
  - `String valueToString(Object)`→`((Boolean)value).toString()`。
  - `Boolean valueFromString(String)`→`Boolean.parseBoolean`。
  - `readFromJson`（行 50）：`isJsonPrimitive && isBoolean` → `setValueNoCallback(getAsBoolean)`。
  - `writeToJson`（行 64）：`new JsonPrimitive(getValue())`。
- **迁移方式**：**适配**（去 Component 构造器参数的 prettyName/comment，或保留 NMS Component）。

---

### ServuxIntSetting.java（103 行）
- **职责**：整数配置项，带 min/max 边界校验。
- **额外字段**：`int maxValue`、`int minValue`。
- **关键方法**：
  - `validateString`（行 58）：`Integer.parseInt` + 范围检查（异常返回 false）。
  - `valueFromString`→`Integer.parseInt`。
  - `readFromJson`：number → getAsInt。
  - 8 个构造器（含/不含 min/max、含/不含 callback）。
- **迁移方式**：**适配**。

---

### ServuxStringSetting.java（67 行）
- **职责**：字符串配置项，支持 `strict`（仅允许 examples 内的值）。
- **额外字段**：`boolean strict`。
- **关键方法**：
  - `validateString`（行 35）：`strict ? examples().contains(value) : true`。
  - `readFromJson`（行 53）：`instanceof JsonPrimitive` → getAsString。
- **迁移方式**：**适配**。

---

### ServuxListSetting.java（90 行，抽象）
- **职责**：泛型列表配置项基类。用 Gson 把 List<T> 序列化为 JSON 数组字符串。
- **字段**：`static final Gson GSON = new Gson()`。
- **关键方法**：
  - `validateString`（行 31）：`GSON.fromJson(value, JsonArray.class)` 逐元素调 `validateJsonForElement`。
  - `valueToString(Object)`（行 48）：建 JsonArray，每元素 `writeElementToJson`。
  - `valueFromString`（行 59）：`GSON.fromJson(value,JsonArray.class).asList().stream().map(readElementFromJson).toList()`。
  - `readFromJson`/`writeToJson`：JsonArray 互转。
  - 抽象：`boolean validateJsonForElement(JsonElement)`、`T readElementFromJson(JsonElement)`、`JsonElement writeElementToJson(T)`。
- **迁移方式**：**适配**（Gson 照抄；Component 同上）。

---

### ServuxStringListSetting.java（59 行）
- **职责**：`List<String>` 配置项（ServuxListSetting 的 String 特化）。
- **关键方法**：`validateJsonForElement`→`instanceof JsonPrimitive && isString`；`readElementFromJson`→`getAsString`；`writeElementToJson`→`new JsonPrimitive(value)`。
- **迁移方式**：**适配**。

---

# 第二部分：commands/ 命令树

> **总览**：原版用 Fabric 注册 NMS Brigadier 命令（`CommandDispatcher<CommandSourceStack>`），权限走 `me.lucko.fabric-permissions-api`。Paper 上有两条路：
> 1. **保留 NMS Brigadier（推荐，命令树几乎照抄）**：通过 Paper 的 `LifecycleEventManager`（`io.papermc.paper.plugin.lifecycle.event`）的 `RegisterCommandsEvent`，反射/直接拿到 NMS Brigadier dispatcher 注册。或更稳妥：用 Paper 的 `CommandManager`（paper-api 提供 Brigadier 包装，`commandManager().register(...)`)。`CommandSourceStack` → Paper 仍可用 NMS 的，但发消息建议用 `Bukkit.getSender`。权限 `Permissions.require(...,4)` → `player.hasPermission(node)` 或 OP 判断。
> 2. **重写为 Bukkit `PluginCommand` + `CommandExecutor`/`TabCompleter`**：完全用 Paper API，但需自己实现 reload/save/set/info/list/search 的参数解析与 Tab 补全（工作量大）。
>
> 建议方案 1（Brigadier 照抄），权限节点命名原样保留 `servux.commands{,.reload,.save,.set,.info,.list}`。

---

### ICommandProvider.java（10 行）
- **职责**：命令注册器接口。`registerCommand(IServerCommand)` / `unregisterCommand(IServerCommand)`。
- **迁移方式**：**照抄**。`IServerCommand`（`fi.dy.masa.servux.interfaces`）签名 `register(CommandDispatcher<CommandSourceStack>, CommandBuildContext, Commands.CommandSelection)` 需保留（走 NMS Brigadier 路线）。

---

### CommandProvider.java（41 行）
- **职责**：单例 `ICommandProvider`，持 `List<IServerCommand>`，`registerCommands(dispatcher, registryAccess, environment)` 遍历调用每个命令的 register。
- **字段**：`static final CommandProvider INSTANCE`、`List<IServerCommand> commands`。
- **迁移方式**：**重新设计（轻度）**。
  - `registerCommands` 触发点：原版由 Mixin `ServerDevCommands`/命令注册事件触发；Paper 上改为在 `RegisterCommandsEvent`（Lifecycle）回调里调用 `INSTANCE.registerCommands(dispatcher, buildContext, CommandSelection.DEDICATED)`。`CommandBuildContext` 从事件或 `MinecraftServer.registryAccess()` 取。
  - 其余照抄。

---

### ServuxCommand.java（319 行）⭐ 核心命令
- **职责**：`/servux` 命令树，含 5 个子命令：`reload` / `save` / `set` / `info` / `list` / `search`。
- **字段**：`static final ServuxCommand INSTANCE`。
- **命令树结构**（行 38-105）：
  - 根 `literal("servux")` `.requires(Permissions.require("servux.commands",4))`
  - `reload`（行 40）→ `ServuxConfigProvider.INSTANCE.doReloadConfig(source)`
  - `save`（行 46）→ `doSaveConfig(source)`
  - `set <setting:Identifier> <value:greedyString>`（行 52）：`settingsNode()` 提供 setting 参数（带 Tab 补全，按 provider/setting 名建议）→ value 参数补全走 `setting.examples()` → `configModify`。
  - `info <setting:Identifier>`（行 67）→ `configInfo`。
  - `list [provider:string]`（行 70）：无参列出所有 provider 的所有 settings；带 provider 参则列该 provider 的 settings。
  - `search <query:greedyString>`（行 87）：跨所有 settings 按 name/comment/provider 名包含子串过滤。
- **关键方法**：
  - `register(dispatcher, registryAccess, environment)`（行 34）：构建并注册命令树。
  - `private List<IServuxSetting<?>> configSearch(ctx, query)`（行 108）：`query.split(" ")`，每 part 必须命中 name 或 comment.getString() 或 dataProvider.getName()。
  - `private int configList(ctx, list)`（行 135）：列出 settings，含 `appearedMultiTimes`（同名跨 provider 追加 `(provider)`）；每行 `shortDisplayName` + 可点击跳 `info`；value 长度<10 才内联显示。
  - `private ArgumentBuilder settingsNode()`（行 176）：`IdentifierArgument.id()` 参数，Tab 补全逻辑：含 `:` 时按 provider 名前缀建议 `provider:setting`；否则建议所有 setting 名 + 所有 provider 名。
  - `private static int configInfo(ctx)`（行 201）：显示 prettyName、qualifiedName（点击复制到剪贴板）、comment、当前值、默认/已修改标记、reset 建议、examples 列表（当前值高亮绿）。
  - `private static int configModify(ctx)`（行 276）：解析 setting 名 + value（缺省回退默认值）→ validateString → setValueFromString → 成功消息（可点击跳 info）。
- **NMS依赖**：`Permissions`(lucko,需替换)、`ChatFormatting/Component/ClickEvent/HoverEvent/Identifier/CommandSourceStack/CommandBuildContext/Commands/SharedSuggestionProvider/IdentifierArgument/MutableComponent`、`Reference`、`DataProviderManager`、`IDataProvider`、`ServuxConfigProvider`、`IServerCommand`、`IServuxSetting`、`StringUtils`。
- **迁移方式**：**重新设计（保留 NMS Brigadier + 换权限 + Component 处理）**。
  - `Permissions.require(node, level)` → `source.hasPermission(level)` 或自定义 `requires` 谓词。`level=4` 即 OP；可映射为 Bukkit 权限 `servux.commands.*`。
  - `IdentifierArgument.id()`：Brigadier 自带 `IdentifierArgument` 是 NMS；若用 Paper `CommandManager`，可用 `ResourceLocationArgument` 或自定义 string argument + Tab 补全。原版补全逻辑 `settingsNode()` 可照搬。
  - `Identifier`（= Mojang `ResourceLocation`）：直接用 NMS `net.minecraft.resources.ResourceLocation`，`StringUtils.removeDefaultMinecraftNamespace` 照搬。
  - `Component`/`ClickEvent`/`HoverEvent`：保留 NMS（发消息给玩家时 source.sendSuccess 已是 Component）。
  - `ctx.getSource().sendSuccess(() -> Component, broadcastToOps)`：NMS API，Paper 上 `CommandSourceStack` 仍可用（如走 NMS dispatcher）。
- **风险点**：
  1. `me.lucko.fabric-permissions-api` 在 Paper 不存在 → 必须替换为 Bukkit 权限或 OP 判断，6 处 `.requires(...)`。
  2. 若改用 Paper `CommandManager`（非直接 NMS dispatcher），`IdentifierArgument`/`SharedSuggestionProvider.suggest(...)` 需替换为 Paper Brigadier 等价 API。
  3. i18n key（`servux.command.*`）必须先在 lang 文件备齐。

---

# 第三部分：util/ 工具集

---

### JsonUtils.java（481 行）⭐
- **职责**：JsonObject 安全取值（hasXxx/getXxxOrDefault）、BlockPos/Vec3 ↔ JSON、deepCopy、文件读写。
- **字段**：`static final Gson GSON = new GsonBuilder().setPrettyPrinting().create()`。
- **方法清单**：
  - `JsonObject getNestedObject(JsonObject, key, create)`（行 27）
  - `hasBoolean/hasInteger/hasLong/hasFloat/hasDouble/hasString/hasObject/hasArray`（行 46-158）
  - `getBooleanOrDefault/getIntegerOrDefault/getLongOrDefault/getFloatOrDefault/getDoubleOrDefault/getStringOrDefault`（行 160-242）
  - `getBoolean/getInteger/getLong/getFloat/getDouble/getString`（行 244-273，默认 0/false/null）
  - `hasBlockPos(JsonObject,name)`（行 275）/ `JsonArray blockPosToJson(Vec3i)`（行 280）/ `BlockPos blockPosFromJson(obj,name)`（行 292）
  - `hasVec3d`/`vec3dToJson(Vec3)`/`vec3dFromJson`（行 311-345）
  - `JsonObject deepCopy(JsonObject/JsonArray/JsonElement)`（行 349-393）
  - `JsonElement parseJsonFromString(String)`（行 396）/ `parseJsonFileAsPath(Path)`（行 408）
  - `String jsonToString(JsonElement, boolean compact)`（行 431）
  - `boolean writeJsonToFileAsPath(JsonObject, Path)`（行 437）：写 `.tmp` → 删旧 → move，原子写。
- **NMS依赖**：`BlockPos/Vec3i/Vec3`、`Servux.LOGGER`。
- **迁移方式**：**适配**。
  - `Servux.LOGGER` → 插件 `JavaPlugin.getLogger()`（通过静态持有 plugin 实例）。
  - BlockPos/Vec3i/Vec3 直接用 NMS（Mojang 映射）。
  - 其余（Gson/文件 IO）照抄。
- **风险点**：`writeJsonToFileAsPath` 的 `.tmp`+`move` 原子写在 Windows 上 `Files.move` 跨卷可能失败，原版用 `Path.of` 同目录所以安全；照抄即可。

---

### StringUtils.java（62 行）
- **职责**：版本字符串获取、去掉 `minecraft:` 命名空间、i18n 翻译桥接。
- **方法**：
  - `String getModVersionString(String modId)`（行 12）：遍历 **`net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()`** 找 modId 的版本。
  - `String removeDefaultMinecraftNamespace(Identifier)`（行 25）：namespace=="minecraft" 则返回 path，否则 toString。
  - `String translateAsString(key, args)`（行 30）：`ServuxConfigProvider.LANG.translate(...)`。
  - `MutableComponent translate(key, args)`（行 46）：`ServuxConfigProvider.LANG.translateAsText(...)`。
  - `CommandSyntaxException translateError(key, args)`（行 57）：`new SimpleCommandExceptionType(translate(...)).create()`。
- **NMS依赖**：`MutableComponent/Identifier/CommandSyntaxException/SimpleCommandExceptionType`、**FabricLoader**。
- **迁移方式**：**适配**。
  - `getModVersionString`：FabricLoader → Paper `Bukkit.getPluginManager().getPlugin(modId)` + `pluginMeta.getVersion()`，或直接读本插件 `getDescription().getVersion()`。
  - `removeDefaultMinecraftNamespace`：`Identifier` 换 NMS `ResourceLocation`，逻辑照搬。
  - translate/translateError：依赖 `ServuxConfigProvider.LANG`（即 i18nManager），照搬。
- **风险点**：`translateAsText` 返回 NMS MutableComponent；若整体改 Adventure 需同步改。

---

### MathUtils.java（656 行）
- **职责**：纯数学工具（average/clamp/floor/round/roundUp/roundDown/wrapDegrees/wrapRadianAngle/log2/De Bruijn/scale/min/max 等）。
- **关键 NMS 用法**：`Vec3 getRotationVector(yaw,pitch)`（行 480）、`Vec3 scale(Vec3,factor)`（行 547）、`long getPositionRandom(Vec3i)`（行 496）；`org.apache.commons.lang3.math.Fraction`（min/max 重载）。
- **NMS依赖**：`Vec3/Vec3i`、`Fraction`。
- **迁移方式**：**照抄**。Fraction 依赖 `commons-lang3`（Paper 自带）。

---

### IntBoundingBox.java（183 行）⭐（含协议字节布局）
- **职责**：6 整数 min/max AABB record，含 NBT/Codec/StreamCodec 序列化。
- **字段**：`int minX,minY,minZ,maxX,maxY,maxZ`。
- **关键常量**：
  - `Codec<IntBoundingBox> CODEC`（行 21）：RecordCodecBuilder，6 个 `INT.fieldOf("minX"/.../"maxZ")`。
  - `StreamCodec<ByteBuf,IntBoundingBox> PACKET_CODEC`（行 31）：见协议布局。
- **关键方法**：`containsPos(Vec3i)`、`containsPos(long)`、`intersects(IntBoundingBox)`、`getMinValueForAxis/getMaxValueForAxis(Direction.Axis)`、`toVanillaBox()`→`net.minecraft.world.level.levelgen.structure.BoundingBox`、`toNBTIntArray()`→`IntArrayTag`、`fromVanillaBox`/`createProper`/`createForWorldBounds(Level)`/`fromArray(int[])`/`expand`/`shrink`。
- **协议字节布局（PACKET_CODEC）**：
  ```
  [minX:int32][minY:int32][minZ:int32][maxX:int32][maxY:int32][maxZ:int32]
  // 编码用 ByteBufCodecs.INT（= writeInt LE? 实为 writeInt big-endian 4 字节）
  // 解码逆序读 6 个 int32
  ```
- **NMS依赖**：`BlockPos/Direction/Vec3i/Level/BoundingBox/IntArrayTag/ByteBufCodecs/StreamCodec/Codec/PrimitiveCodec/RecordCodecBuilder/ByteBuf(io.netty)`。
- **迁移方式**：**照抄**（record + Codec + StreamCodec 在 paperweight userdev 下可直接编译；运行时协议层需在 friendly bytebuf 上手写同样 6×int32）。
- **风险点**：S2C 经 plugin messaging 时，需用 `FriendlyByteBuf.writeInt` 6 次（顺序一致），与 PACKET_CODEC 字节布局对齐。

---

### PlayerDimensionPosition.java（42 行）
- **职责**：记录玩家上次维度+坐标，用于判定维度变更/超出阈值需更新。
- **字段**：`DimensionType dimensionType`、`BlockPos pos`。
- **方法**：`dimensionChanged(Player)`、`needsUpdate(Player,int threshold)`、`setPosition(Player)`。
- **NMS依赖**：`BlockPos/Player/DimensionType`（`player.level().dimensionType()`、`player.blockPosition()`）。
- **迁移方式**：**照抄**（Paper NMS 同名）。

---

### Timeout.java（22 行）
- **职责**：基于 tick 计数器的简单超时判定。`needsUpdate(currentTick, timeout)`=`currentTick-lastSync>=timeout`。
- **迁移方式**：**照抄**。

---

### FileUtils.java（11 行）
- **职责**：`getNameWithoutExtension(String)`（lastIndexOf(".") 截断）。
- **迁移方式**：**照抄**。

---

### BlockUtils.java（175 行）
- **职责**：BlockState 属性工具。`getFirstDirectionProperty`、`fixMirrorDoubleChest`（**镜像修复**）、`getBlockStateFromString`（解析 `minecraft:stone[prop=val,...]`）。
- **关键方法**：
  - `Optional<EnumProperty<Direction>> getFirstDirectionProperty(BlockState)`（行 34）
  - `BlockState fixMirrorDoubleChest(BlockState, Mirror, ChestType)`（行 70）：镜像后修正双层箱子 facing/type。
  - `Optional<BlockState> getBlockStateFromString(String)`（行 102）：`Identifier.tryParse` + `BuiltInRegistries.BLOCK.get` + 逐属性 setValue。
- **NMS依赖**：`Direction/Holder/BuiltInRegistries/Identifier/Block/ChestBlock/Mirror/BlockState/StateDefinition/BlockStateProperties/ChestType/EnumProperty/Property`（Guava `Splitter`）。
- **迁移方式**：**照抄**。
- **风险点**：`fixMirrorDoubleChest` 是 CLAUDE.md §3 "镜像修复" 的核心之一，Litematica 投影粘贴时调用；Paper 无 Mixin，**在 SchematicPlacingUtils 内联调用即可**，无需 Mixin。

---

### EntityUtils.java（193 行）
- **职责**：实体查找/朝向/从 NBT 创建实体+乘客并生成、区域内实体获取。
- **关键方法**：
  - `Predicate<Entity> NOT_PLAYER`（行 29）
  - `getHorizontalLookingDirection/getVerticalLookingDirection/getClosestLookingDirection`（行 36-58）
  - `findEntityByUUID(List,UUID)`（行 61）
  - `getEntityId(Entity)`（行 80）：`EntityType.getKey` → `Identifier.toString()`
  - `createEntityFromNBTSingle(CompoundTag,Level)`（行 88）：**`NbtView.getReader(nbt, world.registryAccess())` → `EntityType.create(view.getReader(), world, EntitySpawnReason.LOAD)`**
  - `createEntityAndPassengersFromNBT`（行 116）：递归处理 `Passengers` 列表。
  - `spawnEntityAndPassengersInWorld`（行 145）：`world.addFreshEntity` + 乘客 snapTo。
  - `setEntityRotations`（行 162）：yaw/pitch + LivingEntity 头身旋转。
  - `getEntitiesWithinSubRegion(world, origin, regionPos, regionSize, schematicPlacement, placement)`（行 181）。
- **NMS依赖**：`BlockPos/Direction/CompoundTag/ListTag/Identifier/Entity/EntitySpawnReason/EntityType/LivingEntity/Player/Level/AABB`、`NbtView`、`PositionUtils`。
- **迁移方式**：**照抄**（依赖 NbtView，必先解决）。
- **风险点**：`EntityType.create(ValueInput, ...)` 的签名与 NbtView 的 reader 绑定，NbtView 迁移后此链路必须通。

---

### InventoryUtils.java（28 行）
- **职责**：潜影盒检测（堆叠相关）。`isShulkerBox(ItemStack)`、`shulkerBoxHasItems`（读 `DataComponents.CONTAINER`）。
- **NMS依赖**：`DataComponents/ItemStack/BlockItem/ShulkerBoxBlock/ItemContainerContents`。
- **迁移方式**：**照抄**。

---

### PlacementHandler.java（350 行）⭐（EasyPlace 核心）
- **职责**：Litematica/Tweakeroo EasyPlace 协议 V2/V3，把玩家命中位置编码的 protocolValue 解码成 BlockState 朝向/属性。
- **字段**：`WHITELISTED_PROPERTIES`（ImmutableSet，约 20 个 Property）、`BLACKLISTED_PROPERTIES`（WATERLOGGED/POWERED 强制 false）。
- **关键方法**：
  - `applyPlacementProtocolV3(BlockState, UseContext)`（行 66）：从 `hitVec.x - pos.x - 2` 提 protocolValue，先处理方向属性（3 bits+1），再逐白名单属性按 `log2(ceilPow2(values.size()))` bits 解码。
  - `applyPlacementProtocolV2(...)`（行 272）：旧版协议（5 bits facing + axis/repeater/comparator/half）。
  - `applyDirectionProperty(...)`（行 230）：`decodedFacingIndex=(protocolValue&0xF)>>1`，6=反向，0-5=`Direction.from3DDataValue`。
  - record `UseContext(Level, BlockPos, Direction side, Vec3 hitVec, LivingEntity, InteractionHand, BlockPlaceContext)`（行 332）；`UseContext.from(BlockPlaceContext, hand)`。
- **NMS依赖**：`BlockPos/Direction/Mth/InteractionHand/LivingEntity/BlockPlaceContext/Level/BedBlock/Block/ComparatorBlock/RepeaterBlock/BlockState/properties.*/Fluids/Vec3`、`BlockUtils`、`ServuxConfigProvider`。
- **迁移方式**：**算法照抄；触发处降级**。
  - 算法本身（纯位运算 + BlockState）照抄。
  - **但原版通过 Mixin `BlockItem.getPlacementState` + `ServerGamePacketListenerImpl.handleUseItemOn`（去掉命中校验）触发**——Paper 无 Mixin。CLAUDE.md §3 指出需用 PacketEvents 拦截 `ServerboundUseItemOnPacket` 自行放置，或直接省略。
- **风险点**：这是 CLAUDE.md 明确的"降级最严重"功能之一；纯 Paper API 无法干净触发 `applyPlacementProtocolV3`，最务实是省略 EasyPlace，仅保留投影粘贴（不依赖此 handler 的运行时触发）。

---

### WorldUtils.java（17 行）+ IWorldUpdateSuppressor.java（9 行）
- **职责**：UpdateSuppression —— 在粘贴大区域时临时抑制世界方块更新（防止连锁更新）。
- **机制**：`IWorldUpdateSuppressor` 是 Mixin 注入 `Level` 的接口（`servux_getShouldPreventBlockUpdates`/`servux_setShouldPreventBlockUpdates`）；`WorldUtils` 强转 `Level` 为该接口读写标志。
- **NMS/Mixin依赖**：**强依赖 Mixin**（给 `Level` 加接口 + 改 `setBlockState` 副作用）。
- **迁移方式**：**降级/省略**。
  - Paper 无 Mixin。`SchematicPlacingUtils.placeToWorldWithinChunk` 调用 `WorldUtils.setShouldPreventBlockUpdates(world,true/false)` 在 try/finally 包裹——Paper 上需删掉这两行，改用 `world.setBlock(pos,state,flags)` 的 flags 控制是否 notify（原版已用 `0x12`/`0x14` 标志位），并在最后批量 `updateNeighborsAt`。即 **行为降级为"靠 flags 控制而非全局抑制"**，可能产生少量额外更新但可接受。
- **风险点**：删除全局抑制后，大区域粘贴可能触发额外方块更新/红石连锁，需测试。

---

### SchematicPlacingUtils.java（554 行）⭐（Litematica 投影粘贴）
- **职责**：把 Litematica 投影按区块逐块粘贴到世界（方块+方块实体+计划 tick+实体）。
- **关键方法**：
  - `placeToWorldWithinChunk(world, chunkPos, schematicPlacement, replace, layerBehavior, layerRange, notifyNeighbors)`（行 47）：外层 try/finally 调 `WorldUtils.setShouldPreventBlockUpdates`。
  - `placeBlocksWithinChunk(...)`（行 111）：核心方块遍历——读 container.get(x,y,z) → 跳过 STRUCTURE_VOID → shouldPasteBlock 过滤 → 替换策略（NONE/WITH_NON_AIR）→ 镜像修复 chest（`LitematicsDataProvider.INSTANCE.fixChestMirror`）→ `state.mirror/rotate` → 先设 BARRIER 清 Container → `setBlock(state,0x12)` → **`NbtView.getReader(teNBT, registryAccess)` → `te.loadWithComponents(view.getReader())`**。
  - `placeEntitiesToWorldWithinChunk(...)`（行 382）：实体粘贴（painting/itemframe 位置修正 + `EntityUtils.createEntityAndPassengersFromNBT` + rotateEntity + Display.tick）。
  - `rotateEntity(...)`（行 520）、`shouldPasteBlock/shouldPasteEntity(...)`（行 534/544）。
- **NMS依赖**：大量（`ServerLevel/BlockEntity/LevelTicks/ScheduledTick/Blocks/ChestBlock/Container/ChunkPos/Mirror/Rotation/Vec3/BlockPos/CompoundTag/ListTag/Direction.AxisDirection`、`NbtView`/`NbtUtils`/`PositionUtils`/`EntityUtils`/`IntBoundingBox`）。
- **Mixin依赖**：`WorldUtils.setShouldPreventBlockUpdates`（→ `IWorldUpdateSuppressor`）。
- **迁移方式**：**适配（更新抑制降级）**。
  - `setShouldPreventBlockUpdates` 调用删除（见 WorldUtils）。
  - 其余照抄，但注意 `te.loadWithComponents(ValueInput)` 走 NbtView reader。
- **风险点**：
  1. NbtView 必须先迁移通（行 285）。
  2. 更新抑制降级后大区域粘贴的红石/连锁更新行为变化。
  3. painting/itemframe 位置 hack 高度依赖 NMS 行为，1.21.11 需验证。

---

### LayerMode.java（60 行）
- **职责**：层级模式枚举（ALL/SINGLE_LAYER/LAYER_RANGE/ALL_BELOW/ALL_ABOVE），实现 `StringRepresentable`，含 `PACKET_CODEC`（idMapper）、`INDEX_TO_VALUE`（ByIdMap.continuous）、`CODEC`。
- **协议字节布局（PACKET_CODEC）**：`ByteBufCodecs.idMapper(INDEX_TO_VALUE, getIndex)` = 1 个 VarInt id（0-4）。
- **NMS依赖**：`StringRepresentable/ByIdMap/ByteBufCodecs/StreamCodec`。
- **迁移方式**：**照抄**。

---

### LayerRange.java（701 行）⭐（含协议字节布局）
- **职责**：层级范围（mode/axis/single/above/below/rangeMin/rangeMax/hotkeyMin/hotkeyMax），含 Codec/StreamCodec/JSON。
- **字段**：`LayerMode layerMode=ALL`、`Axis axis=Y`、`int layerSingle/Above/Below/RangeMin/RangeMax`、`boolean hotkeyRangeMin/Max`。
- **关键常量**：`CODEC`（行 23）、`PACKET_CODEC`（行 36）。
- **协议字节布局（PACKET_CODEC）**：
  ```
  [mode: LayerMode PACKET_CODEC = VarInt id]
  [axis: STRING_UTF8 (SerializedName)]
  [layerSingle:int32][layerAbove:int32][layerBelow:int32]
  [layerRangeMin:int32][layerRangeMax:int32]
  [hotkeyRangeMin:bool(1 byte)][hotkeyRangeMax:bool(1 byte)]
  ```
- **关键方法**：`getLayerMin/getLayerMax`（按 mode 返回边界）、`isPositionWithinRange(x,y,z)`、`intersects(IntBoundingBox)`、`getClampedRenderBoundingBox/getClampedArea`、`toJson/fromJson/createFromJson`。
- **NMS依赖**：`BlockPos/Direction/Axis/Mth/Entity/Level/Codec/PrimitiveCodec/RecordCodecBuilder/ByteBufCodecs/StreamCodec/JsonObject/JsonPrimitive`、`JsonUtils`、`IntBoundingBox`。
- **迁移方式**：**照抄**（需 LayerMode + JsonUtils 先就位）。
- **风险点**：`Axis.byName` 返回 nullable，fromJson 有 null→Y 兜底，照抄。

---

### ReplaceBehavior.java（44 行）
- **职责**：替换策略枚举（NONE/ALL/WITH_NON_AIR）。`StringRepresentable`、`fromStringStatic`。
- **迁移方式**：**照抄**。

---

### PasteLayerBehavior.java（43 行）
- **职责**：粘贴层行为枚举（ALL/RENDERED_ONLY）。`StringRepresentable`、`fromStringStatic`。
- **迁移方式**：**照抄**。

---

### PositionUtils.java（util/position/，1315 行）⭐
- **职责**：坐标/朝向/镜像/旋转/区块/包围盒 工具集（Litematica 投影系统的基础）。
- **常量**：`ALL_DIRECTIONS/HORIZONTAL_DIRECTIONS/VERTICAL_DIRECTIONS/AXES_ALL/ADJACENT_SIDES_*/EDGE_NEIGHBOR_OFFSETS_*`（边缘邻居偏移表，行 373-389）、`BLOCK_POS_COMPARATOR/CHUNK_POS_COMPARATOR`。
- **关键方法分组**：
  - 坐标修改：`modifyValue/setValue(CoordinateType, Vec3/BlockPos, ...)`、`getModifiedPosition`、`getCoordinate`（行 36-94,788-817）
  - 朝向：`getEntityBlockPos`、`getClosestLookingDirection(entity[,threshold=60])`、`getPositionInfrontOfEntity`（行 96-183）
  - 命中：`getHitVecCenter(BlockPos,Direction)`、`getHitPart/getTargetedDirection`（行 191-347）+ 枚举 `HitPart`（CENTER/LEFT/RIGHT/BOTTOM/TOP）
  - 区块：`getChunkPosLong`、`getBoxesWithinChunk`（2 重载）、`getTouchedChunks/getTouchedChunksForBoxes`、`getBoundsWithinChunkForBox`、`getPerChunkBoxes`（行 403-733）
  - 包围盒/角：`getMinCorner/getMaxCorner`、`isPositionInsideArea`、`arePositionsWithinWorld/isBoxWithinWorld`、`getAreaSizeFromRelativeEndPosition[Abs]`、`getRelativeEndPositionFromAreaSize`、`getValidBoxes/isBoxValid`、`getEnclosingAreaSize/getEnclosingAreaCorners`、`clampBoxToWorldHeightRange`、`getTotalVolume`（行 408-606）
  - 镜像/旋转：`getTransformedPlacementPosition`、`getTransformedBlockPos(BlockPos,Mirror,Rotation)`、`getReverseTransformedBlockPos`、`getOriginalPositionFromTransformed`、`getTransformedPosition(Vec3,...)`、`getReverseRotation`、`cycleRotation/cycleMirror`、`getRotationNameShort/getMirrorName`、`getRotatedYaw/getMirroredYaw`（行 425-1214）
  - 锁定/面：`getModifiedPartiallyLockedPosition(lockMask)`、`getFacingFromPositions`（行 1058-1120）
  - AABB：`createEnclosingAABB/createAABBFrom/createAABBForPosition/createAABB`（行 739-778）
  - 比较器内部类：`BlockPosComparator/ChunkPosComparator/ChunkPosDistanceComparator`（行 1216-1306）
  - 枚举：`HitPart`（行 349）、`CoordinateType`(X/Y/Z)（行 358）、`Corner`(NONE/CORNER_1/CORNER_2)（行 1308）
- **NMS依赖**：`BlockPos/Direction/AxisDirection/Vec3i/Mth/Entity/ChunkPos/Level/Mirror/Rotation/WorldBorder/Vec3/AABB`、Guava `ImmutableList/ImmutableMap`、`commons-lang3 Pair`、`IntBoundingBox`、schematic 包（`SchematicPlacement/SubRegionPlacement/AreaSelection/Box`）。
- **迁移方式**：**照抄**（纯坐标算法 + NMS，无 Mixin）。
- **风险点**：依赖 schematic 模块的 `SchematicPlacement/SubRegionPlacement/AreaSelection/Box`，需与 schematic 模块同步迁移。

---

### i18n 子系统（util/i18n/）⭐

#### i18nOption.java（187 行）
- **职责**：语言选项枚举（120+ 语言码 + 本地名 + 译者 credits）。`fromString(key)` 支持 `.json` 后缀剥离与 UNKNOWN 兜底。
- **迁移方式**：**照抄**（纯枚举，无 NMS）。

#### i18nLang.java（util/i18n/，103 行）
- **职责**：单语言映射（ImmutableMap<String,String>）。
- **关键方法**：`protected static i18nLang load(dir, langCode)`（行 29）：从 **jar 资源** `"/"+dir+"/"+langCode+".json"` 读 InputStream → Gson 解析 → 建 map。`hasTranslation/get/getOrDefault/getLangCode/toOption`。
- **NMS依赖**：无；仅 Gson + `Servux.LOGGER`。
- **迁移方式**：**适配**。
  - `i18nLang.class.getResourceAsStream` 在 Paper plugin jar 里同样可用（lang 文件放 `src/main/resources/assets/servux/lang/*.json`，打包进 jar）。
  - LOGGER 换插件 logger。
- **风险点**：`load` 是 protected static，被 i18nManager 调用；资源路径需与 `baseString="assets/servux/lang"` 对齐。

#### i18nManager.java（util/i18n/，317 行）⭐
- **职责**：i18n 管理器单例。维护 defaultLang(en_us)/当前 lang，扫描 jar 内所有 lang 文件建 keys/options，提供 `translate`/`translateAsText`/`translateOrFallback`。
- **关键字段**：`i18nFileFilter FILE_FILTER`、`DEFAULT_LANG="en_us"`、`keys`、`options`、`defaultLang`、`modId`、`baseString="assets/"+modId+"/lang"`、`lang`。
- **关键方法**：
  - `static i18nManager create(modId)`（行 42）：构造，加载 defaultLang，readKeys。
  - `readKeys()`（行 63）：**jar 内 FileSystem 扫描** `assets/<modId>/lang` 目录，支持 `jar:` scheme（临时 FileSystems.newFileSystem）与 file scheme，用 `i18nFileFilter`（正则 `^[a-z]{2,4}_[a-z]{2,4}$` 或 `^[a-z]{3,4}$`）过滤 → 收集语言文件名。
  - `buildLanguageOptions()`（行 135）：keys → i18nOption。
  - `setLang(langCode)`（行 193）、`resetLangToDefault`、`ensureLang`。
  - `translate(key, args...)`（行 253）：`String.format(Locale.ROOT, getOrDefault(key,key), args)`，异常返回 "Format Error:"。
  - `translateAsText(key, args...)`（行 269）：有翻译 → `Component.literal(translate(...))`；无 → 红色 + hover "Missing translation: key"。
  - `i18nFileFilter`（行 288）：内部 DirectoryStream.Filter。
- **NMS依赖**：`Component/ChatFormatting/HoverEvent.ShowText/MutableComponent`、`Reference`、`Servux.LOGGER`、jar FileSystem API。
- **迁移方式**：**适配**。
  - jar 扫描逻辑（FileSystem/newFileSystem）**照抄**——Paper plugin jar 同样是 jar/zip，逻辑通用。
  - Component 处理：保留 NMS Component（发消息时用），或改 Adventure（`Component.text(...).hoverEvent(...)` API 不同）。
  - LOGGER/Reference 字段替换。
- **风险点**：
  1. `Reference.DEV_DEBUG`/`Reference.ANSI_MODE`/`Reference.MOD_ID` 需重建为插件常量。
  2. translateAsText 返回类型决定上层（StringUtils/Setting 显示）是否改 Adventure。
  3. dev 环境下 `assets/servux/lang` 路径在 Paper plugin 里要确保打包正确（paperweight 下资源在 `src/main/resources/`）。

#### i18nLang.java（util/i18nLang.java，根，1 行空文件）
- **迁移方式**：**删除**（旧遗留，已迁入 util/i18n/ 包）。

---

### log 子系统（util/log/）

#### AnsiColors.java（91 行）：ANSI 转义码常量表。**照抄**。
#### IAnsiLogger.java（30 行）：日志接口，`format(fmt,args)` 用 `{}` 占位 + `replaceFirst`。**照抄**。
#### AnsiLogger.java（135 行）：`System.out.printf` 实现，读 `Reference.DEV_DEBUG/ANSI_MODE`。
- **迁移方式**：**适配**（Reference 字段换；可选改用 Plugin logger，但 ANSI 控制台着色用 System.out 更合适，保留）。

---

### nbt 子系统（util/nbt/）

#### NbtKeys.java（197 行）
- **职责**：NBT 键名常量集合（实体/方块实体/物品/旧版组件迁移键，约 160 个 `public static final String`）。
- **迁移方式**：**照抄**（纯常量）。注意已含 1.21.11 新键（`NEXT_WEATHER_AGE`/`WEATHER_STATE` 行 158-159）。

#### NbtUtils.java（360 行）⭐
- **职责**：核心 NBT 编解码工具。UUID/Vec2/Vec3i/Vec3/BlockPos 用 Codec 读写、int[]↔BlockPos、文件 IO、FlatMap Codec。
- **关键方法清单**：
  - UUID：`getUUIDCodec(CompoundTag[,key])`（行 32-52，用 `nbt.read(key, UUIDUtil.CODEC)`）、`putUUIDCodec(nbt,uuid,key)`（`nbt.store`）。
  - Vec/Pos Codec：`putVec2fCodec/putVec3iCodec/putVec3dCodec/putPosCodec`（`nbt.store(key, X.CODEC, val)`，行 68-90）、对应 `getXxxCodec`（`nbt.read(key, X.CODEC).orElse(X.ZERO)`，行 92-110）。
  - 数组互转：`writeBlockPosToArrayTag(Vec3i,tag,tagName)`（int[]，行 125）、`readBlockPosFromArrayTag`（行 139，`tag.getIntArray(tagName).orElse(new int[0])`）、`readVec3iFromIntArrayTag`。
  - 显式键：`writeBlockPosToTag`/`readBlockPos`（行 181-201，x/y/z int）、`writeVec3dToTag`/`readVec3d`（dx/dy/dz，行 203-235）、`writeEntityPositionToTag`/`readEntityPositionFromTag`（Pos ListTag of Double，行 211-251）、`readVec3iFromTag`（行 254）。
  - 文件：`readNbtFromFileAsPath(Path[,NbtAccounter])`（行 268，`NbtIo.readCompressed`）、`writeCompressed(tag, OutputStream/Path)`（`NbtIo.writeCompressed`，行 296-318）。
  - FlatMap Codec：`<T> Optional<T> readFlatMap(CompoundTag, MapCodec<T>)`（行 327，`NbtOps.INSTANCE.getMap` + `mapCodec.decode`，switch DataResult）、`CompoundTag writeFlatMap(MapCodec<T>, T)`（行 346，`encoder().encodeStart` + `nbt.merge`）。
- **NMS依赖**：`CompoundTag/Tag/ListTag/DoubleTag/NbtAccounter/NbtIo/UUIDUtil/BlockPos/Vec2/Vec3/Vec3i/NbtOps/Codec/MapCodec/DataResult/DynamicOps`、`Constants.NBT`、`Servux.LOGGER`。
- **迁移方式**：**照抄**。
  - 1.21.11 关键：`CompoundTag.getBoolean/getInt/getString` 已 Optional 化——本类已用 `getIntOr/getDoubleOr/getListOrEmpty/getFloatOr/getStringOr`（见行 143/197/231/246/261），符合 CLAUDE.md §4；`putXxx` 返回 void，本类链式均以 `return nbt/tag` 收尾，正确。
  - LOGGER 换插件 logger。
- **风险点**：`readFlatMap`/`writeFlatMap` 用 DataResult 的 pattern switch（Java 21 + sealed DataResult），需 JDK 21 + paperdev bundle 提供的 DataResult；编译期注意 switch 完整性。

#### NbtView.java（231 行）⭐⭐（**最关键迁移难点**）
- **职责**：包装 Mojang 新版 `ValueInput`/`ValueOutput`（`TagValueInput`/`TagValueOutput`），提供 CompoundTag ↔ View 互转 + 自动管理 ProblemReporter。
- **关键字段**：`Logger LOGGER`、`ProblemReporter log = new ProblemReporter.ScopedCollector(LOGGER)`、`ValueInput reader`、`ValueOutput writer`。
- **关键方法**：
  - `static NbtView getReader(CompoundTag nbt, RegistryAccess registry)`（行 41）：`reader = TagValueInput.create(log, registry, nbt)`。
  - `static NbtView getWriter(RegistryAccess registry)`（行 54）：`writer = TagValueOutput.createWithContext(log, registry)`。
  - `isReader/isWriter/getReader/getWriter/asNbtReader/asNbtWriter`。
  - `getReaderContext()`（行 79）：**`((IMixinNbtReadView)reader).servux_getContext()`**。
  - `getWriterOps()`（行 90）：**`((IMixinNbtWriteView)writer).servux_getOps()`**。
  - `CompoundTag readNbt()`（行 105）：reader→`((IMixinNbtReadView)reader).servux_getNbt()`；writer→`((IMixinNbtWriteView)writer).servux_getNbt()`。
  - `NbtView writeNbt(CompoundTag nbtIn)`（行 125）：遍历 nbtIn.keySet() → `readNbt().put(key, nbtIn.get(key))`（reader 只读，调用报错）。
  - `<T> Optional<T> readFlatMap(MapCodec<T>)`（行 147）→ `NbtUtils.readFlatMap`。
  - `<T> Optional<T> readCodec(key, Codec<T>)`（行 165）→ `reader.read(key, codec)`。
  - `<T> CompoundTag writeFlatMap(MapCodec<T>, T)`（行 191）/ `<T> CompoundTag writeCodec(key, Codec<T>, T)`（行 211）→ `writer.store(key, codec, value)`。
- **NMS依赖**：`CompoundTag/RegistryAccess/ProblemReporter/ProblemReporter.ScopedCollector/ValueInput/ValueOutput/TagValueInput/TagValueOutput/ValueInputContextHelper/Codec/MapCodec/DynamicOps`。
- **Mixin依赖（必替代）**：`IMixinNbtReadView`（@Accessor 读 `TagValueInput.context`、`input:CompoundTag`）、`IMixinNbtWriteView`（@Accessor 读 `TagValueOutput.ops`、`output:CompoundTag`）。
- **迁移方式**：**重写（绕开 IMixin，用反射 + NMS 直接 API）**。

**Paper 版 NbtView 迁移方案（重要）：**

原版用 `@Accessor` Mixin 把 `TagValueInput`/`TagValueOutput` 的私有字段（`input`/`output` CompoundTag、`context`、`ops`）暴露出来。Paper 无 Mixin，三条替代路径：

**路径 A（推荐）：反射读 TagValueInput/TagValueOutput 私有字段。**
- `TagValueInput` 有私有字段 `CompoundTag input`、`ValueInputContextHelper context`（paperweight 提供全映射名，反射用 Mojang 名）。
- `TagValueOutput` 有私有字段 `CompoundTag output`、`DynamicOps<?> ops`。
- 用 `MethodHandles.privateLookupIn` 或 `java.lang.reflect.Field.setAccessible(true)` 缓存 `Field` 句柄（仿 VeryMcBot `reflect/` 包范式），实现等价的 `servux_getNbt()`/`servux_getContext()`/`servux_getOps()`。
- 字段名漂移风险：reobf 不转换反射字符串，Paper 运行时即 Mojang 名 → 直接用 `input`/`output`/`context`/`ops`。

**路径 B（更稳）：不再缓存 View，改用 `Entity.saveWithoutId` / `BlockEntity.saveWithElements` / 直接 Codec。**
- NbtView 的真实消费点（grep 后）：
  - `EntityUtils.createEntityFromNBTSingle`：`NbtView.getReader(nbt, registryAccess())` → `EntityType.create(ValueInput, ...)`。
  - `SchematicPlacingUtils.placeBlocksWithinChunk`：`NbtView.getReader(teNBT, registryAccess())` → `te.loadWithComponents(ValueInput)`。
- 这些消费点**只需要一个 `ValueInput`**（reader），不需要回读 CompoundTag。因此可：
  - reader 侧：直接 `TagValueInput.create(problemReporter, registryAccess, nbt)` 返回的 `ValueInput` 即可用，**不必再 cast 回取内部 CompoundTag**——除非要 writeNbt/readNbt。
  - writer 侧：`TagValueOutput.createWithContext(problemReporter, registryAccess)` 返回 `ValueOutput`；写出后取结果 CompoundTag 用**反射读 `output` 字段**（路径 A），或用 `TagValueOutput` 若有 `result()`/`build()` 公共方法（1.21.11 待核实，若 Mojang 提供则免反射）。
- **结论**：reader 完全免 Mixin/反射（直接用 create 返回值）；writer 仅在需要导出 CompoundTag 时反射一个字段（或找公共 API）。

**路径 C（最简/降级）**：用 NMS `Entity.saveAsPassengerNBT`/`saveWithoutId(TagValueOutput)` 等高阶 API，绕过 NbtView 包装。若 masa 协议层只要 CompoundTag 字节，可全程用 `NbtUtils` 的 Codec 读写（如 `EntityType.CODEC`/`BlockEntity.saveWithFullMetadata()`），不引入 ValueInput/Output。

**推荐落地**：保留 `NbtView` 类骨架，内部 reader 用 `TagValueInput.create(...)` 直接返回（去掉 `getReaderContext`/`readNbt` 对 reader 的 Mixin cast），writer 用 `TagValueOutput.createWithContext(...)` + 反射取 `output` 字段。`writeNbt`/`readFlatMap`/`readCodec`/`writeCodec` 逻辑不变。删除对 `IMixinNbtReadView`/`IMixinNbtWriteView` 的依赖，删掉这两个 Mixin 类。

- **风险点**：
  1. `TagValueOutput` 导出 CompoundTag 的公共 API（是否 `result()`/`toNbt()`/`build()`）需在 1.21.11 dev bundle 中核实；若无公共 API 才反射。
  2. `TagValueInput.create`/`TagValueOutput.createWithContext` 的参数签名（ProblemReporter + RegistryAccess + nbt）1.21.11 可能微调，需对照。
  3. `ProblemReporter.ScopedCollector` 构造与清理需正确（避免内存泄漏）。

---

### data 子系统（util/data/）

#### Constants.java（31 行）：`NBT` 内部类（TAG_* id 0-12, 99）+ `isValidChar(char)`（替代 SharedConstants）。**照抄**。
#### FileType.java（94 行）：文件类型枚举（LITEMATICA/SCHEMATICA/SPONGE/VANILLA_STRUCTURE/JSON/...），`fromName`/`fromFile`/`getFileExt`/`getString`，实现 `StringRepresentable`。**照抄**。
#### Schema.java（250 行）：MC 版本↔DataVersion 映射表（含 `SCHEMA_1_21_11(4671,"1.21.11")` 行 36），`PACKET_CODEC`（int dataVersion）、`CODEC`、`getSchemaByDataVersion`/`getSchemaByString`。**照抄**。

#### data/tag/ 子树（schematic 共享，约 25 文件）
- `DataView`（230 行）：数据视图接口（contains/getXxx/getCodec 默认方法）。
- `DataConverterNbt`（190 行）：vanilla `Tag` ↔ `BaseData` 互转（switch on `getId()`）。
- `BaseData/ArrayData/ByteArrayData/ByteData/CompoundData/DoubleData/EmptyData/FloatData/IntArrayData/IntData/ListData/LongArrayData/LongData/NumberData/ShortData/StringData`：NBT 包装类（纯算法）。
- `util/DataByteBufUtils/DataFileUtils/DataOps/DataTypeUtils/SizeTracker`：字节/文件/Codec/大小追踪工具。
- **迁移方式**：**全部照抄**（纯 Java + NMS Tag，无 Mixin；属 schematic 模块，本笔记不展开）。

---

## 跨模块依赖与迁移顺序

1. **基础设施先行**：`Reference`（插件常量替换）→ `i18n*`（i18nManager 单例 = `ServuxConfigProvider.LANG`）→ `StringUtils`。
2. **工具层**：`Constants/JsonUtils/MathUtils/FileUtils/NbtKeys` → `NbtUtils` → **`NbtView`（反射重写，最难点）** → `BlockUtils/EntityUtils/InventoryUtils/PositionUtils/IntBoundingBox/LayerMode/LayerRange`。
3. **配置系统**：`IServuxSettingCallback/IServuxSetting/AbstractServuxSetting` → `ServuxBool/Int/String/StringList/ListSetting`。
4. **命令**：`ICommandProvider/CommandProvider/IServerCommand` → `ServuxCommand`（依赖 DataProviderManager + ServuxConfigProvider + 各 settings）。
5. **降级项**：`WorldUtils`/`IWorldUpdateSuppressor`/`PlacementHandler` 运行时触发——删除 Mixin 接口，粘贴改 flags，EasyPlace 省略或 PacketEvents。

---

## 关键风险点汇总（实现时优先排查）

1. **NbtView 的 Mixin 替代**（最高优先级）：`TagValueInput/TagValueOutput` 私有字段反射 vs 公共导出 API，决定 `EntityUtils.createEntityFromNBTSingle` 与 `SchematicPlacingUtils` 的 BlockEntity 载入是否可用。
2. **EasyPlace / UpdateSuppression / 镜像修复触发**：`PlacementHandler` 运行时触发需 Mixin（无解，降级）；`WorldUtils` 全局抑制需 Mixin（改 flags）；`BlockUtils.fixMirrorDoubleChest` 可在粘贴代码内联（无 Mixin 依赖，安全）。
3. **命令权限 + Brigadier**：`fabric-permissions-api` 6 处 `.requires` 全替换；Brigadier 命令树用 NMS 反射注册或 Paper `CommandManager`；`IdentifierArgument`/`SharedSuggestionProvider` API 差异。
4. **Component 体系**：NMS `net.minecraft.network.chat.Component` 与 Paper Adventure `net.kyori.adventure.text.Component` 二选一，贯穿 settings/i18n/commands，中途切换成本高，**建议统一保留 NMS Component**（Paper 运行时即 Mojang 映射，发消息用 NMS source.sendSuccess）。
5. **i18n jar 资源路径**：`assets/servux/lang/*.json` 需正确打包进 plugin jar；dev 环境 FileSystem 扫描逻辑保留。
6. **1.21.11 CompoundTag Optional 化**：`NbtUtils` 已用 `getXxxOr`；新增 NBT 代码务必避免裸 `getInt`。
