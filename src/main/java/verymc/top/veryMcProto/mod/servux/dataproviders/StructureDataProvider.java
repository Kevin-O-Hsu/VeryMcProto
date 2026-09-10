package verymc.top.veryMcProto.mod.servux.dataproviders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.dataproviders.DataProviderBase;
import verymc.top.veryMcProto.framework.network.PacketSplitter;
import verymc.top.veryMcProto.mod.servux.ServuxDebug;
import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.framework.network.ServerPlayHandler;
import verymc.top.veryMcProto.framework.permission.Perms;
import verymc.top.veryMcProto.framework.settings.IServuxSetting;
import verymc.top.veryMcProto.framework.settings.ServuxBoolSetting;
import verymc.top.veryMcProto.framework.settings.ServuxIntSetting;
import verymc.top.veryMcProto.framework.settings.ServuxStringListSetting;
import verymc.top.veryMcProto.mod.servux.ServuxReference;
import verymc.top.veryMcProto.mod.servux.network.ServuxStructuresHandler;
import verymc.top.veryMcProto.mod.servux.network.ServuxStructuresPacket;
import verymc.top.veryMcProto.mod.servux.util.PlayerDimensionPosition;

/**
 * Structures Provider（mod 层，配 MiniHUD 结构边界框）。移植自原版 {@code StructureDataProvider}
 * （通道 servux:structures，协议版本 3——26.1 真值，常量 ServuxStructuresPacket.PROTOCOL_VERSION）。
 *
 * <p><b>采集触发</b>：原版用 Mixin {@code MixinServerChunkLoadingManager.markChunkPendingToSend}
 * → {@code onStartedWatchingChunk(player, chunk)} 精确触发区块结构采集。Paper 无此 Mixin，
 * 改为<b>周期扫描</b>：每 {@code update_interval}（默认 100t）对每个 enabled 且 registered 的玩家，
 * 遍历其 view distance 范围区块，采集结构 NBT 并全量重发（去重由客户端 ListTag 合并处理）。
 *
 * <p><b>采集 API</b>（paperweight dev bundle 直连，不需反射，照原版调用）：
 * <ul>
 *   <li>{@code world.getChunk(x, z, ChunkStatus.STRUCTURE_REFERENCES, false)} → {@link ChunkAccess}；</li>
 *   <li>{@code chunk.getAllReferences()} → {@code Map<Structure, LongSet>}；</li>
 *   <li>{@code chunk.getStartForStructure(structure)} → {@link StructureStart}；</li>
 *   <li>{@link StructureStart#createTag(StructurePieceSerializationContext, ChunkPos)} → 边界框 NBT；</li>
 *   <li>{@link BuiltInRegistries#STRUCTURE_TYPE} {@code .getKey(structure.type())} → 结构类型 Identifier。</li>
 * </ul>
 *
 * <p><b>适配</b>：{@code Permissions.check} → {@link Perms#check}；{@code Reference} → {@link ServuxReference}；
 * {@code registerHandler} 去 registerPlayPayload/receiver；{@code tick} 去 ProfilerFiller 形参与 push/pop；
 * sendMetadata 走 plugin messaging；{@code getName().tryCollapseToString()} → {@code getName().getString()}。
 *
 * <p><b>黑白名单</b>（{@code structures_whitelist/blacklist} + enabled）保留；{@code ExpandBox} 标志保留。
 * timeout 机制简化为周期全量重发。
 */
public class StructureDataProvider extends DataProviderBase
{
    public static final StructureDataProvider INSTANCE = new StructureDataProvider();
    protected static final ServuxStructuresHandler HANDLER = ServuxStructuresHandler.getInstance();

    protected final CompoundTag metadata = new CompoundTag();
    private final ServuxIntSetting permissionLevel = new ServuxIntSetting(this, "permission_level", 0, 4, 0);
    private final ServuxBoolSetting structureBlacklistEnabled = new ServuxBoolSetting(this, "structures_blacklist_enabled", false);
    private final ServuxBoolSetting structureWhitelistEnabled = new ServuxBoolSetting(this, "structures_whitelist_enabled", false);
    private final ServuxStringListSetting structureBlacklist = new ServuxStringListSetting(this, "structures_blacklist", List.of("minecraft:buried_treasure"));
    private final ServuxStringListSetting structureWhitelist = new ServuxStringListSetting(this, "structures_whitelist", List.of());
    private final ServuxIntSetting updateInterval = new ServuxIntSetting(this, "update_interval", 40, 1200, 1);
    private final ServuxIntSetting timeout = new ServuxIntSetting(this, "timeout", 600, 1200, 40);
    private final List<IServuxSetting<?>> settings = List.of(
            this.permissionLevel, this.structureBlacklistEnabled, this.structureWhitelistEnabled,
            this.structureBlacklist, this.structureWhitelist, this.updateInterval, this.timeout
    );

    private final Map<UUID, PlayerDimensionPosition> registeredPlayers = new HashMap<>();
    private int retainDistance;

    protected StructureDataProvider()
    {
        super("structure_bounding_boxes",
                ServuxStructuresHandler.CHANNEL_ID,
                ServuxStructuresPacket.PROTOCOL_VERSION,
                0, ServuxReference.MOD_ID + ".provider.structure_bounding_boxes",
                "Structure Bounding Boxes data for structures such as Witch Huts, Ocean Monuments, Nether Fortresses etc.");

        this.metadata.putString("name", this.getName());
        this.metadata.putString("id", this.getNetworkChannel().toString());
        this.metadata.putInt("version", this.getProtocolVersion());
        this.metadata.putString("servux", ServuxReference.MOD_STRING);
        this.metadata.putInt("timeout", this.timeout.getValue());

        this.setTickRate(40);
    }

    @Override public List<IServuxSetting<?>> getSettings() { return this.settings; }

    @Override
    public void registerHandler()
    {
        ServerPlayHandler.getInstance().registerServerPlayHandler(HANDLER);
        this.setRegistered(true);
    }

    @Override
    public void unregisterHandler()
    {
        ServerPlayHandler.getInstance().unregisterServerPlayHandler(HANDLER);
    }

    @Override
    public IPluginServerPlayHandler getPacketHandler() { return HANDLER; }

    /**
     * 名册语义（上游 StructureDataProvider:117-119 = contains && !invalid）：我方无独立 invalid 名册，
     * 由 {@link #onPacketFailure} → {@link #unregister}（移除 Map 项 + resetFailures）传递性承担
     * 上游 invalid 轴——onPacketFailure 后 containsKey 即 false，等价于上游 contains && !invalid。
     */
    @Override
    public boolean isPlayerRegistered(ServerPlayer player)
    {
        return this.registeredPlayers.containsKey(player.getUUID());
    }

    @Override
    public boolean shouldTick() { return this.enabled; }

    /**
     * 周期扫描：每 {@code update_interval} tick，对每个 registered 玩家遍历其 view distance 区块，
     * 采集结构 NBT 并全量重发（替代原版 Mixin {@code onStartedWatchingChunk} 精确触发）。
     */
    @Override
    public void tick(MinecraftServer server, int tickCounter)
    {
        if (!this.isEnabled()) { return; }

        if ((tickCounter % this.updateInterval.getValue()) == 0)
        {
            List<ServerPlayer> playerList = server.getPlayerList().getPlayers();
            this.retainDistance = server.getPlayerList().getViewDistance() + 2;

            for (ServerPlayer player : playerList)
            {
                UUID uuid = player.getUUID();

                if (this.registeredPlayers.containsKey(uuid))
                {
                    if (!this.hasPermission(player))
                    {
                        this.unregister(player);
                    }
                    else
                    {
                        // 周期全量重发观察区块内的结构数据
                        this.rescanAndSend(player);
                    }
                }
            }

            this.checkForInvalidPlayers(server);
        }
    }

    /** 清理已下线的 registered 玩家。 */
    public void checkForInvalidPlayers(MinecraftServer server)
    {
        if (!this.registeredPlayers.isEmpty())
        {
            Iterator<UUID> iter = this.registeredPlayers.keySet().iterator();

            while (iter.hasNext())
            {
                UUID uuid = iter.next();

                if (server.getPlayerList().getPlayer(uuid) == null)
                {
                    iter.remove();
                }
            }
        }
    }

    /**
     * C2S 注册入口（type 3 STRUCTURES_REGISTER）。上游 StructureDataProvider.register（:199-241）字面移植：
     * isEnabled → 版本门禁（deny 四件套）→ 权限（不入册）→ 入册（含 max_receive_s2c 能力协商）→ sendMetadata + initialSync。
     *
     * <p>{@code tags.max_receive_s2c}（TAG_INT，上游 :221）= 客户端申报的单帧接收上限，存入名册 entry
     * 供 {@link #sendStructures} 条目级分批（上游 :565-604）。26.1 四客户端均无发送点（grep 实证零命中），
     * 恒走默认 16MB——机制层对齐上游，真实环境不可观测。
     */
    @Override
    public void register(ServerPlayer player, CompoundTag tags)
    {
        if (!this.isEnabled()) { return; }

        if (DataProviderBase.isVersionTooLow(tags, this.getProtocolVersion()))
        {
            Reference.logger().warning("structure_bounding_boxes: Denying access for player " + player.getName().getString()
                    + ", Insufficient Protocol Version; This Server Requires: Version " + this.getProtocolVersion());
            player.sendSystemMessage(Component.literal(ServuxReference.MSG_PROTOCOL_VERSION_TOO_LOW.formatted(this.getName())));
            HANDLER.tickFailures(player);
            return;
        }

        ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "structures register(): " + player.getName().getString() + " (C2S STRUCTURES_REGISTER)");

        MinecraftServer server = player.createCommandSourceStack().getServer();
        UUID uuid = player.getUUID();

        if (!this.hasPermission(player))
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "structures register 拒绝 " + player.getName().getString() + " (权限不足)");
            return;
        }

        // 上游语义 = 每次 REGISTER 全量应答（metadata + initialSync）。唯一调用方 ServuxStructuresHandler
        // 恒先 unregister 再 register，故此处不做去重（客户端 %20 重试 / toggle 重开都会得到完整回复）。
        if (!this.registeredPlayers.containsKey(uuid))
        {
            PlayerDimensionPosition entry = new PlayerDimensionPosition(player);
            // 上游 :221/:226：max_receive_s2c 能力协商（TAG_INT）——内联承载于名册 entry
            entry.maxReceiveS2c = tags.getIntOr("max_receive_s2c", PacketSplitter.MAX_REASSEMBLY_SIZE_S2C);
            this.registeredPlayers.put(uuid, entry);
            int tickCounter = server != null ? server.getTickCount() : 0;

            this.sendMetadata(player);
            this.initialSyncStructuresToPlayerWithinRange(player, server != null ? server.getPlayerList().getViewDistance() + 2 : this.retainDistance, tickCounter);

            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "structures register OK: " + player.getName().getString() + " → 已加入订阅，推 metadata + initialSync");
        }
    }

    /** C2S 注销（UNREGISTER_REPLY）：resetFailures + 出名册（上游 :243-253 字面）。 */
    @Override
    public void unregister(ServerPlayer player)
    {
        HANDLER.resetFailures(this.getNetworkChannel(), player);
        this.registeredPlayers.remove(player.getUUID());
    }

    /**
     * 失败回调：出册 + resetFailures。上游 StructureDataProvider.onPacketFailure（:260-267）+
     * ServuxStructuresHandler.tickFailures 的失败后清零怪癖（:197-199，"you know ... design"）——
     * 以 unregister 一站式传递性复现（出册 + resetFailures 同做），周期 tick 自动停推。
     */
    public void onPacketFailure(ServerPlayer player)
    {
        this.unregister(player);
    }

    /** 发送 metadata（ PACKET_S2C_METADATA，NBT）。 */
    public void sendMetadata(ServerPlayer player)
    {
        if (!this.isEnabled())
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "structures sendMetadata 跳过: provider disabled");
            return;
        }
        if (!this.hasPermission(player))
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "structures sendMetadata 拒绝 " + player.getName().getString() + " (权限不足)");
            return;
        }

        CompoundTag nbt = new CompoundTag();
        nbt.merge(this.metadata);
        boolean ok = HANDLER.sendPlayPayload(player, new ServuxStructuresPacket(ServuxStructuresPacket.Type.PACKET_S2C_METADATA, nbt));
        ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "structures sendMetadata → " + player.getName().getString()
                + " ok=" + ok + " servux=" + nbt.getStringOr("servux", "?")
                + " ver=" + nbt.getIntOr("version", -1)
                + " timeout=" + nbt.getIntOr("timeout", -1));
    }

    /**
     * 周期扫描：玩家当前 chunk 视野内的结构引用 → 起点 → NBT，全量发送。
     *
     * <p>不按 {@code getListeningPluginChannels} 早退：能进这里的必是发过 C2S REGISTER 的玩家（= 装有 MiniHUD、
     * 能解码），其「未声明」只是 Paper 声明簿记滞后，投递由框架 {@code ProtocolChannel.send} 的 C2S 证明兜底保证；
     * 上游 servux 亦无此门。
     */
    protected void rescanAndSend(ServerPlayer player)
    {
        ServerLevel world = (ServerLevel) player.level();
        ChunkPos center = player.getLastSectionPos().chunk();

        // 检测维度切换，重置玩家位置快照
        UUID uuid = player.getUUID();
        PlayerDimensionPosition playerPos = this.registeredPlayers.get(uuid);
        if (playerPos == null || playerPos.dimensionChanged(player))
        {
            this.registeredPlayers.computeIfAbsent(uuid, (u) -> new PlayerDimensionPosition(player)).setPosition(player);
        }

        Map<Structure, LongSet> references = this.getStructureReferencesWithinRange(world, center, this.retainDistance);
        this.sendStructures(player, references);
    }

    protected void initialSyncStructuresToPlayerWithinRange(ServerPlayer player, int chunkRadius, int tickCounter)
    {
        UUID uuid = player.getUUID();
        ChunkPos center = player.getLastSectionPos().chunk();
        Map<Structure, LongSet> references = this.getStructureReferencesWithinRange((ServerLevel) player.level(), center, chunkRadius);

        this.registeredPlayers.computeIfAbsent(uuid, (u) -> new PlayerDimensionPosition(player)).setPosition(player);
        this.sendStructures(player, references);
    }

    /** 采集单区块内各结构的引用 startChunks（合并到 references）。 */
    protected void getStructureReferencesFromChunk(int chunkX, int chunkZ, Level world, Map<Structure, LongSet> references)
    {
        if (!world.hasChunk(chunkX, chunkZ))
        {
            return;
        }

        ChunkAccess chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.STRUCTURE_REFERENCES, false);

        if (chunk == null)
        {
            return;
        }

        for (Map.Entry<Structure, LongSet> entry : chunk.getAllReferences().entrySet())
        {
            Structure feature = entry.getKey();
            LongSet startChunks = entry.getValue();

            if (!startChunks.isEmpty())
            {
                references.merge(feature, startChunks, (oldSet, entrySet) -> {
                    LongOpenHashSet newSet = new LongOpenHashSet(oldSet);
                    newSet.addAll(entrySet);
                    return newSet;
                });
            }
        }
    }

    /** 由 references（每个 structure → 其 start chunk long set）解析出各 start chunk 的 StructureStart。 */
    protected Map<ChunkPos, StructureStart> getStructureStartsFromReferences(ServerLevel world, Map<Structure, LongSet> references)
    {
        Map<ChunkPos, StructureStart> starts = new HashMap<>();

        for (Map.Entry<Structure, LongSet> entry : references.entrySet())
        {
            Structure structure = entry.getKey();
            LongSet startChunks = entry.getValue();
            LongIterator iter = startChunks.iterator();

            while (iter.hasNext())
            {
                // 26.1：ChunkPos 转 record，new ChunkPos(long) → unpack(long)
                ChunkPos pos = ChunkPos.unpack(iter.nextLong());

                if (!world.hasChunk(pos.x(), pos.z()))
                {
                    continue;
                }

                ChunkAccess chunk = world.getChunk(pos.x(), pos.z(), ChunkStatus.STRUCTURE_REFERENCES, false);

                if (chunk == null)
                {
                    continue;
                }

                StructureStart start = chunk.getStartForStructure(structure);

                if (start != null)
                {
                    starts.put(pos, start);
                }
            }
        }

        return starts;
    }

    /** 分批 padding（上游 sendStructures :566 同值 4096：帧包裹开销余量）。 */
    static final int STRUCTURE_BATCH_PADDING = 4096;

    /** 遍历 chunkRadius 立方形范围内所有区块，合并结构引用。 */
    protected Map<Structure, LongSet> getStructureReferencesWithinRange(ServerLevel world, ChunkPos center, int chunkRadius)
    {
        Map<Structure, LongSet> references = new HashMap<>();

        for (int cx = center.x() - chunkRadius; cx <= center.x() + chunkRadius; ++cx)
        {
            for (int cz = center.z() - chunkRadius; cz <= center.z() + chunkRadius; ++cz)
            {
                this.getStructureReferencesFromChunk(cx, cz, world, references);
            }
        }

        return references;
    }

    /**
     * 解析 starts → ListTag（含 ExpandBox 标志），按客户端申报上限分批经 PacketSplitter 分片发送
     * PACKET_S2C_STRUCTURE_DATA_START。对齐上游 sendStructures :565-604：每业务帧仍走
     * encodeServerData → PacketSplitter 字节分片（分批在分片之上，两层叠加）；客户端按帧合并
     * 非替换（minihud ServuxStructuresHandler:113-121 每重组帧独立 addOrUpdateStructuresFromServer）。
     */
    protected void sendStructures(ServerPlayer player, Map<Structure, LongSet> references)
    {
        ServerLevel world = (ServerLevel) player.level();
        Map<ChunkPos, StructureStart> starts = this.getStructureStartsFromReferences(world, references);

        if (!starts.isEmpty() && this.registeredPlayers.containsKey(player.getUUID()))
        {
            ListTag structureList = this.getStructureList(starts, world);

            if (!structureList.isEmpty())
            {
                PlayerDimensionPosition entry = this.registeredPlayers.get(player.getUUID());
                final int maxSize = entry != null ? entry.maxReceiveS2c : PacketSplitter.MAX_REASSEMBLY_SIZE_S2C;

                for (ListTag batch : splitStructuresBySize(structureList, maxSize, STRUCTURE_BATCH_PADDING))
                {
                    CompoundTag nbt = new CompoundTag();
                    nbt.put("Structures", batch);
                    HANDLER.encodeServerData(player, new ServuxStructuresPacket(ServuxStructuresPacket.Type.PACKET_S2C_STRUCTURE_DATA_START, nbt));
                }
            }
        }
    }

    /**
     * 条目级分批（上游 sendStructures :568-604 批量循环泛化为纯函数，配单测）。语义四要素照上游：
     * ①总量 + padding ≤ maxSize 单批直通（= 上游单帧分支 :568-572）；②逐条累计，(sendList + padding
     * + entry) ≥ maxSize 即 flush（:586，{@code >=} 判超）；③首条无条件入列（:586 的 !isEmpty() 前置）；
     * ④空条目跳过（:582）+ 收尾 flush（:598-603）。批内条目 copy（上游 entry.copy() 同款防御）。
     */
    static List<ListTag> splitStructuresBySize(ListTag structureList, int maxSize, int padding)
    {
        if ((structureList.sizeInBytes() + padding) <= maxSize)
        {
            return List.of(structureList);
        }

        List<ListTag> batches = new ArrayList<>();
        ListTag sendList = new ListTag();
        final int total = structureList.size();

        for (int i = 0; i < total; i++)
        {
            CompoundTag entry = structureList.getCompoundOrEmpty(i);
            if (entry.isEmpty()) { continue; }
            int currentSize = sendList.sizeInBytes() + padding;

            // Check size
            if (!sendList.isEmpty() && (currentSize + entry.sizeInBytes()) >= maxSize)
            {
                // Release.
                batches.add(sendList);
                sendList = new ListTag();
            }

            sendList.add(entry.copy());
        }

        if (!sendList.isEmpty())
        {
            // Release.
            batches.add(sendList);
        }

        return batches;
    }

    /** starts → ListTag：每项 = StructureStart.createTag + ExpandBox 标志（黑白名单过滤）。 */
    protected ListTag getStructureList(Map<ChunkPos, StructureStart> structures, ServerLevel world)
    {
        ListTag list = new ListTag();
        StructurePieceSerializationContext ctx = StructurePieceSerializationContext.fromLevel(world);

        for (Map.Entry<ChunkPos, StructureStart> entry : structures.entrySet())
        {
            StructureStart start = entry.getValue();
            Structure structure = start.getStructure();
            if (structure == null) { continue; } // C2ME 等可能返回 NULL

            Identifier structureType = BuiltInRegistries.STRUCTURE_TYPE.getKey(structure.type());
            boolean expandBox = structure.terrainAdaptation() != TerrainAdjustment.NONE;

            if (structureType != null && this.shouldSendStructure(structureType))
            {
                ChunkPos pos = entry.getKey();
                CompoundTag nbt = start.createTag(ctx, pos);
                // 需要扩大边界框 12（Pillager Outpost 等地形适配需要）
                nbt.putBoolean("ExpandBox", expandBox);
                list.add(nbt);
            }
        }

        return list;
    }

    /** 结构黑白名单过滤。 */
    protected boolean shouldSendStructure(Identifier identifier)
    {
        if (this.structureWhitelistEnabled.getValue())
        {
            return this.structureWhitelist.getValue().contains(identifier.toString());
        }
        if (this.structureBlacklistEnabled.getValue())
        {
            return !this.structureBlacklist.getValue().contains(identifier.toString());
        }

        return true;
    }

    @Override
    public boolean hasPermission(ServerPlayer player)
    {
        return Perms.check(player, this.permNode, this.permissionLevel.getValue());
    }

    @Override
    public void onPlayerJoin(ServerPlayer player)
    {
        // NO-OP：Structures 由客户端主动 PACKET_C2S_STRUCTURES_REGISTER 触发 register，无需 join 推送。
    }

    @Override
    public void onPlayerRegisterChannel(ServerPlayer player, String channel)
    {
        // Structures 握手模式与 HUD/Entity 不同：由客户端主动 C2S STRUCTURES_REGISTER 触发 register→sendMetadata。
        // 此处仅记录客户端声明了该通道（= 装了 MiniHUD），不主动推 metadata（避免与 REGISTER 流程重复 / 大数据提前推送）。
        if (this.getNetworkChannel().toString().equals(channel))
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "structures onPlayerRegisterChannel: 客户端声明 " + channel
                    + " → 等待 C2S STRUCTURES_REGISTER（由客户端主动触发 register）");
        }
    }

    @Override
    public void onPlayerQuit(ServerPlayer player)
    {
        this.unregister(player);
    }

    @Override public void onTickEndPre() { /* NO-OP */ }
    @Override public void onTickEndPost() { /* NO-OP */ }
}
