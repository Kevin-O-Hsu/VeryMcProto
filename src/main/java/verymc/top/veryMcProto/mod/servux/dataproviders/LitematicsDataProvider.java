package verymc.top.veryMcProto.mod.servux.dataproviders;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.dataproviders.DataProviderBase;
import verymc.top.veryMcProto.mod.servux.ServuxDebug;
import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.framework.network.ServerPlayHandler;
import verymc.top.veryMcProto.framework.permission.Perms;
import verymc.top.veryMcProto.framework.settings.IServuxSetting;
import verymc.top.veryMcProto.framework.settings.ServuxBoolSetting;
import verymc.top.veryMcProto.framework.settings.ServuxIntSetting;
import verymc.top.veryMcProto.mod.servux.ServuxReference;
import verymc.top.veryMcProto.mod.servux.network.ServuxLitematicaHandler;
import verymc.top.veryMcProto.mod.servux.network.ServuxLitematicaPacket;
import verymc.top.veryMcProto.mod.servux.scheduler.FillDeleteTask;
import verymc.top.veryMcProto.mod.servux.scheduler.PasteTask;
import verymc.top.veryMcProto.mod.servux.scheduler.TaskScheduler;
import verymc.top.veryMcProto.mod.servux.schematic.LitematicaSchematic;
import verymc.top.veryMcProto.mod.servux.schematic.placement.SchematicPlacement;
import verymc.top.veryMcProto.mod.servux.schematic.selection.Box;
import verymc.top.veryMcProto.mod.servux.util.ReplaceBehavior;
import verymc.top.veryMcProto.mod.servux.util.PasteLayerBehavior;
import verymc.top.veryMcProto.mod.servux.util.LayerRange;
import org.apache.commons.lang3.tuple.Pair;
import verymc.top.veryMcProto.mod.servux.schematic.transmit.SchematicBufferManager;
import java.nio.file.Files;
import java.nio.file.Path;
import verymc.top.veryMcProto.mod.servux.util.nbt.NbtView;

/**
 * Litematics Provider（mod 层，配 Litematica）。移植自原版 {@code LitematicsDataProvider}
 * （通道 servux:litematics，协议版本 2——26.1 真值，常量 ServuxLitematicaPacket.PROTOCOL_VERSION）。
 *
 * <p><b>已实现</b>：
 * <ul>
 *   <li>元数据握手 {@link #sendMetadata}（与 Entities 同模式）；</li>
 *   <li>{@link #onBlockEntityRequest} / {@link #onEntityRequest}：复用 Entities 模式
 *       （{@code be.saveWithFullMetadata} / {@link NbtView} + {@code entity.saveWithoutId}，
 *       玩家背包/末影箱权限过滤复用 {@link EntitiesDataProvider}）；</li>
 *   <li>{@link #onBulkEntityRequest}：区块内方块实体 + 区块 AABB 内实体（过滤玩家）拼 ListTag，走 PacketSplitter 分包；</li>
 *   <li>settings：permission_level / paste_permission_level（照抄原版）。</li>
 * </ul>
 *
 * <p><b>投影粘贴 / 投递</b>：客户端上传的 .litematic 经 ServuxLitematicaHandler 重组后，由
 * {@link #handleClientPasteRequest} / {@link #handleClientPasteRequestPair} 加载为 SchematicPlacement 并创建
 * {@link PasteTask}（上游 TaskPasteSchematicPerChunkDirect 形态）登记 TaskScheduler 分 tick 粘贴到世界
 * （含 ReplaceMode / PasteLayerBehavior / LayerRange / Interval / 三个忽略布尔，type 16 进度/完成帧随任务下发）；
 * 文件投递（Transmit*）走 LitematicaSchematic.receiveFileTransmit 落盘到 schematics/。详见 schematic 子系统。
 */
public class LitematicsDataProvider extends DataProviderBase
{
    public static final LitematicsDataProvider INSTANCE = new LitematicsDataProvider();
    protected static final ServuxLitematicaHandler HANDLER = ServuxLitematicaHandler.getInstance();

    /** 非玩家实体过滤器（替代原版 EntityUtils.NOT_PLAYER）。 */
    private static final Predicate<Entity> NOT_PLAYER = entity -> entity.getType() != EntityType.PLAYER;

    protected final CompoundTag metadata = new CompoundTag();

    private final ServuxIntSetting permissionLevel = new ServuxIntSetting(this, "permission_level", 0, 4, 0);
    private final ServuxIntSetting pastePermissionLevel = new ServuxIntSetting(this, "permission_level_paste", 0, 4, 0);
    /** task 组权限等级（上游键名 permission_level_tasks，LitematicsDataProvider.java:66）。 */
    private final ServuxIntSetting taskPermissionLevel = new ServuxIntSetting(this, "permission_level_tasks", 0, 4, 0);
    /** task 组完成/中断聊天反馈（上游键名 player_task_feedback，默认 false，LitematicsDataProvider.java:67）。 */
    private final ServuxBoolSetting playerTaskFeedback = new ServuxBoolSetting(this, "player_task_feedback", false);
    public ServuxBoolSetting fixRailRotations = new ServuxBoolSetting(this, "fix_rail_rotations", true);
    public ServuxBoolSetting fixStairMirror = new ServuxBoolSetting(this, "fix_stairs_mirror", true);
    public ServuxBoolSetting fixChestMirror = new ServuxBoolSetting(this, "fix_chest_mirror", true);
    /** 粘贴实体去重（上游键名 deduplicate_schematic_entities，默认 false，LitematicsDataProvider.java:71）：
     *  false = 撞车重排开（id/UUID 与世界撞车时改派新值）；true = 跳过重排，依赖原版 UUID 唯一性拒绝重复实体。 */
    public final ServuxBoolSetting deDuplicateSchematicEntities = new ServuxBoolSetting(this, "deduplicate_schematic_entities", false);
    private final List<IServuxSetting<?>> settings = List.of(
            this.permissionLevel, this.pastePermissionLevel,
            this.taskPermissionLevel, this.playerTaskFeedback,
            this.fixRailRotations, this.fixStairMirror, this.fixChestMirror,
            this.deDuplicateSchematicEntities
    );

    private final List<UUID> invalidPlayers = new ArrayList<>();
    /** 注册名册（上游 registeredPlayers）：C2S REGISTER 版本门禁 + 权限双门通过后入册。 */
    private final List<UUID> registeredPlayers = new ArrayList<>();
    private final SchematicBufferManager bufferManager = new SchematicBufferManager();

    protected LitematicsDataProvider()
    {
        super("litematic_data",
                ServuxLitematicaHandler.CHANNEL_ID,
                ServuxLitematicaPacket.PROTOCOL_VERSION,
                0, ServuxReference.MOD_ID + ".provider.litematic_data",
                "Litematics Data provider.");

        this.metadata.putString("name", this.getName());
        this.metadata.putString("id", this.getNetworkChannel().toString());
        this.metadata.putInt("version", this.getProtocolVersion());
        this.metadata.putString("servux", ServuxReference.MOD_STRING);
    }

    @Override public List<IServuxSetting<?>> getSettings() { return this.settings; }

    @Override
    public void registerHandler()
    {
        ServerPlayHandler.getInstance().registerServerPlayHandler(HANDLER);
        this.setRegistered(true);
    }

    @Override
    public void unregisterHandler() { ServerPlayHandler.getInstance().unregisterServerPlayHandler(HANDLER); }

    @Override public IPluginServerPlayHandler getPacketHandler() { return HANDLER; }

    public SchematicBufferManager getBufferManager() { return this.bufferManager; }

    /** Schematic 文件传输目录（plugins/VeryMcProto/schematics/），首次自动创建。移植自原版 getTransmitDir。 */
    public Path getTransmitDir()
    {
        Path dir = verymc.top.veryMcProto.Reference.plugin().getDataFolder().toPath().resolve("schematics").normalize();
        try
        {
            if (!Files.isDirectory(dir))
            {
                Files.createDirectories(dir);
                verymc.top.veryMcProto.Reference.logger().warning("getTransmitDir(): created schematic dir " + dir.toAbsolutePath());
            }
        }
        catch (java.io.IOException err)
        {
            verymc.top.veryMcProto.Reference.logger().severe("getTransmitDir(): failed: " + err.getMessage());
        }
        return dir;
    }

    @Override
    public boolean isPlayerRegistered(ServerPlayer player)
    {
        return this.registeredPlayers.contains(player.getUUID()) && !this.isPlayerInvalid(player);
    }

    /**
     * C2S 注册入口（type 2 METADATA_REQUEST）。上游 LitematicsDataProvider.register（:183-218）字面移植：
     * isEnabled → 版本门禁（deny 四件套）→ 权限（不入册）→ 入册 → sendMetadata。
     * （上游 Litematics register 不调 removeInvalidPlayer，从上游。）
     */
    @Override
    public void register(ServerPlayer player, CompoundTag tags)
    {
        if (!this.isEnabled()) { return; }

        if (DataProviderBase.isVersionTooLow(tags, this.getProtocolVersion()))
        {
            Reference.logger().warning("litematic_data: Denying access for player " + player.getName().getString()
                    + ", Insufficient Protocol Version; This Server Requires: Version " + this.getProtocolVersion());
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    ServuxReference.MSG_PROTOCOL_VERSION_TOO_LOW.formatted(this.getName())));
            HANDLER.tickFailures(player);
            return;
        }

        if (!this.hasPermission(player))
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "litematic_data: Denying access for player "
                    + player.getName().getString() + ", Insufficient Permissions");
            return;
        }

        this.registeredPlayers.add(player.getUUID());
        this.sendMetadata(player);
    }

    /**
     * C2S 注销（UNREGISTER_REPLY）。上游 LitematicsDataProvider.unregister（:230-232）字面移植：
     * resetFailures + 清传输缓冲（getBufferManager().removePlayer——UNREGISTER_REPLY 是唯一上游
     * 缓冲清理路径，我方此前零调用，本次随名册恢复闭合）+ 出注册名册；不清 invalid。
     */
    @Override
    public void unregister(ServerPlayer player)
    {
        if (this.registeredPlayers.contains(player.getUUID()))
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "litematic_data: Unregistered player " + player.getName().getString());
        }

        HANDLER.resetFailures(this.getNetworkChannel(), player);
        this.getBufferManager().removePlayer(player);
        this.registeredPlayers.remove(player.getUUID());
    }

    public void sendMetadata(ServerPlayer player)
    {
        if (!this.isEnabled())
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "litematic sendMetadata 跳过: provider disabled");
            return;
        }
        if (!this.hasPermission(player))
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "litematic sendMetadata 拒绝 " + player.getName().getString() + " (权限不足)");
            return;
        }
        boolean ok = HANDLER.sendPlayPayload(player, ServuxLitematicaPacket.MetadataResponse(this.metadata));
        boolean listening = player.getBukkitEntity().getListeningPluginChannels().contains(this.getNetworkChannel().toString());
        ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "litematic sendMetadata → " + player.getName().getString()
                + " ok=" + ok + " listening=" + listening
                + " servux=" + this.metadata.getStringOr("servux", "?")
                + " ver=" + this.metadata.getIntOr("version", -1)
                + " keys=" + this.metadata.keySet());
    }

    public void onPacketFailure(ServerPlayer player)
    {
        this.setPlayerInvalid(player);
        this.registeredPlayers.remove(player.getUUID());
    }

    /** 玩家退出（quit）全清理：invalid + 注册名册 + resetFailures（上游 removePlayer 字面；分片会话另经 HANDLER.onPlayerQuit 清）。 */
    public void removePlayer(ServerPlayer player)
    {
        this.removeInvalidPlayer(player);
        this.registeredPlayers.remove(player.getUUID());
        HANDLER.resetFailures(this.getNetworkChannel(), player);
    }

    private void setPlayerInvalid(ServerPlayer player) { if (!this.invalidPlayers.contains(player.getUUID())) { this.invalidPlayers.add(player.getUUID()); } }
    private boolean isPlayerInvalid(ServerPlayer player) { return this.invalidPlayers.contains(player.getUUID()); }
    private void removeInvalidPlayer(ServerPlayer player) { this.invalidPlayers.remove(player.getUUID()); }

    public void onBlockEntityRequest(ServerPlayer player, BlockPos pos)
    {
        // 对齐上游 :475-484：名册门前置且静默（未注册不回任何消息），权限门在后
        if (!this.isPlayerRegistered(player) || !this.isEnabled()) { return; }
        if (!this.hasPermission(player)) { return; }

        BlockEntity be = player.level().getBlockEntity(pos);

        // 对齐上游 :489-493：BE 不存在时不回复（回空帧会污染客户端缓存，见 EntitiesDataProvider 同点位注释）
        if (be != null)
        {
            CompoundTag nbt = be.saveWithFullMetadata(player.registryAccess());
            HANDLER.encodeServerData(player, ServuxLitematicaPacket.SimpleBlockResponse(pos, nbt));
        }
    }

    public void onEntityRequest(ServerPlayer player, int entityId)
    {
        // 对齐上游 :498-507：名册门前置且静默，权限门在后
        if (!this.isPlayerRegistered(player) || !this.isEnabled()) { return; }
        if (!this.hasPermission(player)) { return; }

        Entity entity = player.level().getEntity(entityId);
        if (entity == null) { return; }

        try
        {
            NbtView view = NbtView.getWriter(player.level().registryAccess());
            entity.saveWithoutId(view.getWriter());
            CompoundTag nbt = view.readNbt();

            if (nbt != null)
            {
                Identifier id = EntityType.getKey(entity.getType());

                // 对齐上游 :522：查询者查自己时保留背包/末影箱（!uuid.equals 才进入剥离判断）
                if (entity.getType() == EntityType.PLAYER && !entity.getUUID().equals(player.getUUID()))
                {
                    // 复用 Entities Provider 的玩家背包/末影箱权限过滤
                    if (!EntitiesDataProvider.INSTANCE.hasPlayerInventoryPermission(player)) { nbt.remove("Inventory"); nbt.put("Inventory", new ListTag()); }
                    if (!EntitiesDataProvider.INSTANCE.hasPlayerEnderItemsPermission(player)) { nbt.remove("EnderItems"); nbt.put("EnderItems", new ListTag()); }
                }

                if (id != null) { nbt.putString("id", id.toString()); }
                HANDLER.encodeServerData(player, ServuxLitematicaPacket.SimpleEntityResponse(entityId, nbt));
            }
        }
        catch (Exception e)
        {
            ServuxDebug.log(ServuxDebug.Cat.PACKET, "onEntityRequest 失败 entityId=" + entityId + ": " + e.getMessage());
        }
    }

    /**
     * 区块内批量方块实体 + 实体 NBT（minY/maxY 切片）。
     *
     * <p>字节布局与原版一致：输出 CompoundTag（Task=BulkEntityReply / TileEntities / Entities / chunkX / chunkZ），
     * 经 {@link ServuxLitematicaPacket.Type#PACKET_S2C_NBT_RESPONSE_START} 走 PacketSplitter 分包。
     * 实体位置写为相对 pos1 的偏移（原版 NbtUtils.writeEntityPositionToTag 等价内联）。
     */
    public void onBulkEntityRequest(ServerPlayer player, ChunkPos chunkPos, CompoundTag req)
    {
        // 对齐上游 :544：名册门 + enabled + null/isEmpty 首查（未注册静默，先于权限消息）
        if (!this.isPlayerRegistered(player) || !this.isEnabled() || req == null || req.isEmpty()) { return; }

        if (!this.hasPermission(player))
        {
            Reference.logger().warning("litematic_data: Denying onBulkEntityRequest from " + player.getName().getString() + "（权限不足）");
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(MSG_BULK_INSUFFICIENT));
            return;
        }

        ServerLevel world = (ServerLevel) player.level();
        LevelChunk chunk = world.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());

        if (chunk == null)
        {
            // 对齐上游 :562-565：chunk 未加载消息受 player_task_feedback 门控
            if (this.shouldSendPlayerTaskFeedback())
            {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(MSG_BULK_CHUNK_NOT_LOADED.formatted(chunkPos.toString())));
            }
            return;
        }

        // 对齐上游 :570-571：Task 字段须存在 + TAG_STRING + 值相等（26.1 客户端恒带此字段，无 Task 的旧形态包不再受理）。
        // vanilla CompoundTag 无 contains(String,int) 重载（上游系 malilib API）——getStringOr 对非 String 类型
        // 恒回退默认 ""，故「contains && getStringOr().equals」与上游 TAG_STRING 类型校验语义等价
        if (req.contains("Task") && req.getStringOr("Task", "").equals("BulkEntityRequest"))
        {
            ServuxDebug.log(ServuxDebug.Cat.PACKET, "litematic_data: 批量 NBT ChunkPos " + chunkPos.toString() + " → " + player.getName().getString());

            long timeStart = System.currentTimeMillis();
            ListTag tileList = new ListTag();
            ListTag entityList = new ListTag();
            // 对齐上游 :577-578：回退维度实际上下界（自定义高度维度不再错位切片；26.1 客户端恒发 minY/maxY，回退仅兜底）
            final int minY = req.getIntOr("minY", world.getMinY());
            final int maxY = req.getIntOr("maxY", world.getMaxY());
            BlockPos pos1 = new BlockPos(chunkPos.getMinBlockX(), minY, chunkPos.getMinBlockZ());
            BlockPos pos2 = new BlockPos(chunkPos.getMaxBlockX(), maxY, chunkPos.getMaxBlockZ());

            // 区块 AABB（替代原版 PositionUtils.createEnclosingAABB）
            AABB bb = new AABB(pos1.getX(), pos1.getY(), pos1.getZ(), pos2.getX() + 1, pos2.getY() + 1, pos2.getZ() + 1);
            Iterable<BlockPos> teSet = chunk.getBlockEntitiesPos();
            List<Entity> entities = world.getEntities((Entity) null, bb, NOT_PLAYER);

            for (BlockPos tePos : teSet)
            {
                if ((tePos.getX() < chunkPos.getMinBlockX() || tePos.getX() > chunkPos.getMaxBlockX()) ||
                    (tePos.getZ() < chunkPos.getMinBlockZ() || tePos.getZ() > chunkPos.getMaxBlockZ()) ||
                    (tePos.getY() < minY || tePos.getY() > maxY))
                {
                    continue;
                }

                BlockEntity be = world.getBlockEntity(tePos);

                // 对齐上游 :594-600：BE 不存在的条目直接跳过（不塞空 tag 进批量回复）
                if (be != null)
                {
                    CompoundTag beTag = be.saveWithFullMetadata(player.registryAccess());
                    tileList.add(beTag);
                }
            }

            for (Entity entity : entities)
            {
                NbtView view = NbtView.getWriter(player.level().registryAccess());
                Identifier id = EntityType.getKey(entity.getType());

                entity.saveWithoutId(view.getWriter());
                CompoundTag entTag = view.readNbt();

                if (entTag != null && id != null)
                {
                    Vec3 posVec = new Vec3(entity.getX() - pos1.getX(), entity.getY() - pos1.getY(), entity.getZ() - pos1.getZ());
                    entTag.putString("id", id.toString());

                    // 内联原版 NbtUtils.writeEntityPositionToTag（"Pos" ListTag，double）
                    ListTag posList = new ListTag();
                    posList.add(net.minecraft.nbt.DoubleTag.valueOf(posVec.x));
                    posList.add(net.minecraft.nbt.DoubleTag.valueOf(posVec.y));
                    posList.add(net.minecraft.nbt.DoubleTag.valueOf(posVec.z));
                    entTag.put("Pos", posList);

                    entTag.putInt("entityId", entity.getId());
                    entityList.add(entTag);
                }
            }

            CompoundTag output = new CompoundTag();
            output.putString("Task", "BulkEntityReply");
            output.put("TileEntities", tileList);
            output.put("Entities", entityList);
            output.putInt("chunkX", chunkPos.x());
            output.putInt("chunkZ", chunkPos.z());

            HANDLER.encodeServerData(player, ServuxLitematicaPacket.ResponseS2CStart(output));

            // 对齐上游 :633-641：acknowledge 反馈受 player_task_feedback 门控；文案 = 上游 en_us.json 原文
            if (this.shouldSendPlayerTaskFeedback())
            {
                long timeElapsed = System.currentTimeMillis() - timeStart;
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        MSG_BULK_ACKNOWLEDGE.formatted(world.dimension().identifier().toString(), chunkPos.toString(),
                                tileList.size(), entityList.size(), timeElapsed)), false);
            }
        }
    }

    /**
     * 粘贴请求受理（26.1 任务化，对齐上游 LitematicsDataProvider.handleClientPasteRequest:646-692）：
     * 从客户端上传的 NBT 加载 SchematicPlacement，创建 {@link PasteTask}（上游 TaskPasteSchematicPerChunkDirect
     * 形态）登记 TaskScheduler 分 tick 粘贴到玩家所在世界——同步 pasteTo 直放已被上游注释停用（:684），
     * 受理处即时完成消息上游 :686-690 亦注释停用（完成反馈走任务 stop 链，受 player_task_feedback 门控）。
     * 需创造模式 + paste 权限。四行为字段（ChangedBlocksOnly/IgnoreBlocks/IgnoreEntities/Interval）随任务透传
     * （上游 :674-678 同解析；三布尔在任务内存而不用，上游 Direct:107 同源 TODO）。
     */
    public void handleClientPasteRequest(ServerPlayer player, CompoundTag tags)
    {
        if (!this.isPlayerRegistered(player) || !this.isEnabled() || tags == null || tags.isEmpty())
        {
            return;
        }

        if (!this.hasPermission(player) || !this.hasPermissionsForPaste(player))
        {
            ServuxDebug.log(ServuxDebug.Cat.PERMISSION, "litematic_data: 拒绝粘贴 from " + player.getName().getString() + "（权限不足）");
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cLitematics paste: insufficient permissions."));
            return;
        }
        if (!player.isCreative())
        {
            ServuxDebug.log(ServuxDebug.Cat.PERMISSION, "litematic_data: 拒绝粘贴 from " + player.getName().getString() + "（非创造模式）");
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cLitematics paste: creative mode required."));
            return;
        }

        if (tags.getStringOr("Task", "").equals("LitematicaPaste"))
        {
            ServuxDebug.log(ServuxDebug.Cat.SCHEMATIC, "litematic paste 受理 ← " + player.getName().getString()
                    + " keys=" + tags.keySet()
                    + " ReplaceMode=" + tags.getStringOr("ReplaceMode", "?")
                    + " PasteLayerBehavior=" + tags.getStringOr("PasteLayerBehavior", "?"));
            final long timeStart = System.currentTimeMillis();
            SchematicPlacement placement = SchematicPlacement.createFromNbt(tags);
            ReplaceBehavior replaceMode = ReplaceBehavior.fromStringStatic(tags.getStringOr("ReplaceMode", ReplaceBehavior.NONE.name()));
            PasteLayerBehavior layerBehavior = PasteLayerBehavior.fromStringStatic(tags.getStringOr("PasteLayerBehavior", PasteLayerBehavior.ALL.name()));
            LayerRange layerRange = tags.read("RenderLayerRange", LayerRange.CODEC).orElse(null);
            final boolean changedBlocksOnly = tags.getBooleanOr("ChangedBlocksOnly", false);
            final boolean ignoreBlocks = tags.getBooleanOr("IgnoreBlocks", false);
            final boolean ignoreEntities = tags.getBooleanOr("IgnoreEntities", false);
            final int interval = tags.getIntOr("Interval", 1);
            ServerLevel level = player.level();

            ServuxDebug.log(ServuxDebug.Cat.SCHEMATIC, "litematic paste 任务受理: placement=" + placement.getName()
                    + " origin=" + placement.getOrigin() + " dim=" + level.dimension().identifier()
                    + " interval=" + interval + " changedOnly=" + changedBlocksOnly
                    + " ignoreBlocks=" + ignoreBlocks + " ignoreEntities=" + ignoreEntities);

            // 上游 :681-683 同构：PasteTask（Direct 形态）入 TaskScheduler 分 tick 执行；
            // startTime = 解析前捕获（上游 TaskContext 同序：timeStart 先于 createFromNbt）
            PasteTask task = new PasteTask(level.getServer(), level, player, placement, timeStart, layerRange,
                    replaceMode, layerBehavior, changedBlocksOnly, ignoreBlocks, ignoreEntities);
            TaskScheduler.getInstance().scheduleTask(task, interval);
        }
        else
        {
            ServuxDebug.log(ServuxDebug.Cat.SCHEMATIC, "litematic paste 忽略: Task=" + tags.getStringOr("Task", "(无)") + "（非 LitematicaPaste）");
        }
    }

    /**
     * Transmit 文件上传路径的粘贴受理（26.1 任务化，对齐上游 handleClientPasteRequestPair:694-754）。
     *
     * <p><b>活/死错位声明</b>：26.1 客户端的 Transmit 上传分支整块注释（客户端 SchematicPlacementManager:1178-1183），
     * 本入口当前实际不可达；但上游保留了同名 Pair 受理（其调用点同为死代码），我方按协议面完整同改保留
     * ——勿据"上游死代码"裁此分支，亦勿据"不可达"跳过对齐。
     */
    public void handleClientPasteRequestPair(ServerPlayer player, Pair<LitematicaSchematic, CompoundTag> schemPair)
    {
        if (!this.isPlayerRegistered(player) || !this.isEnabled() ||
            schemPair == null || schemPair.getLeft() == null || schemPair.getRight() == null || schemPair.getRight().isEmpty())
        {
            return;
        }

        if (!this.hasPermission(player) || !this.hasPermissionsForPaste(player))
        {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cLitematics paste: insufficient permissions."));
            return;
        }
        if (!player.isCreative())
        {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cLitematics paste: creative mode required."));
            return;
        }

        ServuxDebug.log(ServuxDebug.Cat.SCHEMATIC, "litematic_data: 执行粘贴(Pair) from " + player.getName().getString());
        final long timeStart = System.currentTimeMillis();
        CompoundTag tags = schemPair.getRight();
        SchematicPlacement placement = SchematicPlacement.createFromNbt(schemPair.getLeft(), tags);
        ReplaceBehavior replaceMode = ReplaceBehavior.fromStringStatic(tags.getStringOr("ReplaceMode", ReplaceBehavior.NONE.name()));
        PasteLayerBehavior layerBehavior = PasteLayerBehavior.fromStringStatic(tags.getStringOr("PasteLayerBehavior", PasteLayerBehavior.ALL.name()));
        LayerRange layerRange = tags.read("RenderLayerRange", LayerRange.CODEC).orElse(null);
        final boolean changedBlocksOnly = tags.getBooleanOr("ChangedBlocksOnly", false);
        final boolean ignoreBlocks = tags.getBooleanOr("IgnoreBlocks", false);
        final boolean ignoreEntities = tags.getBooleanOr("IgnoreEntities", false);
        final int interval = tags.getIntOr("Interval", 1);
        ServerLevel level = player.level();

        PasteTask task = new PasteTask(level.getServer(), level, player, placement, timeStart, layerRange,
                replaceMode, layerBehavior, changedBlocksOnly, ignoreBlocks, ignoreEntities);
        TaskScheduler.getInstance().scheduleTask(task, interval);
    }

    @Override public boolean hasPermission(ServerPlayer player) { return Perms.check(player, this.permNode, this.permissionLevel.getValue()); }

    public boolean hasPermissionsForPaste(ServerPlayer player)
    {
        return this.hasPermission(player) && Perms.check(player, this.permNode + ".paste", this.pastePermissionLevel.getValue());
    }

    /** task 组权限（上游 hasPermissionsForTask 同构：permNode + ".task.fill/.delete" @ permission_level_tasks）。 */
    public boolean hasPermissionsForTask(ServerPlayer player, String task)
    {
        return this.hasPermission(player) && Perms.check(player, this.permNode + ".task." + task, this.taskPermissionLevel.getValue());
    }

    public boolean shouldSendPlayerTaskFeedback() { return this.playerTaskFeedback.getValue(); }

    /** 粘贴实体去重开关（上游 shouldDeDuplicateEntities:777-780）。 */
    public boolean shouldDeDuplicateEntities() { return this.deDuplicateSchematicEntities.getValue(); }

    // ───── task 组（type 14-17，26.1 移植；对照上游 LitematicsDataProvider.onTaskRequest:272-437）─────

    /** task 组反馈文案（上游 servux en_us.json 原文）。 */
    private static final String MSG_TASK_INSUFFICIENT = "§cServux: Insufficient Permissions for Litematic task operations.§r";
    private static final String MSG_TASK_CREATIVE_REQUIRED = "§cServux: Creative Mode is required for this Litematic Task Request.§r";
    private static final String MSG_TASK_NO_FILL_STATE = "§cServux: No fill state provided.§r";
    private static final String MSG_TASK_NO_BOXES = "§cServux: No fill area boxes provided.§r";
    private static final String MSG_TASK_INVALID = "§cServux: Invalid task type provided.§r";

    /** bulk 组文案（上游 en_us.json 原文：error.bulk_request.* / feedback.bulk_request.acknowledge）。 */
    private static final String MSG_BULK_INSUFFICIENT = "§cServux: Insufficient Permissions for the Litematic Bulk NBT Data Request operation.§r";
    private static final String MSG_BULK_CHUNK_NOT_LOADED = "§cServux: Bulk NBT Data Request Error loading Chunk located at %s§r";
    private static final String MSG_BULK_ACKNOWLEDGE = "Servux: Bulk NBT Data from world §d%s§r for chunk §e%s§r, [TE: §a%d§r, E: §a%d§r] delivered in §b%d §fms.";

    /**
     * TASK_REQUEST（type 14）受理：权限 → 创造模式 → Boxes/FillState 解析 → 登记 TaskScheduler。
     * 检查顺序与消息门控照抄上游（insufficient/creative 无条件、no_fill_state/no_boxes/invalid 受
     * player_task_feedback 门控）。Box 线格式 = 客户端 Box.CODEC 产物 {pos1:int[3], pos2:int[3], name}
     * （malilib DataOps INT_STREAM → IntArrayTag，B 轮实证），手工解 IntArrayTag。
     */
    public void onTaskRequest(ServerPlayer player, CompoundTag tags)
    {
        if (!this.isPlayerRegistered(player) || !this.isEnabled() || tags == null || tags.isEmpty())
        {
            return;
        }

        if (!this.hasPermission(player))
        {
            ServuxDebug.log(ServuxDebug.Cat.PERMISSION, "litematic_data: 拒绝 onTaskRequest from " + player.getName().getString() + "（权限不足）");
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(MSG_TASK_INSUFFICIENT));
            return;
        }

        final String taskType = tags.getStringOr("Task", "");
        final long timeStart = System.currentTimeMillis();
        ServerLevel level = player.level();
        ServuxDebug.log(ServuxDebug.Cat.PACKET, "litematic_data: 收到 TaskRequest from " + player.getName().getString() + " type=[" + taskType + "]");

        switch (taskType)
        {
            case "Fill", "Delete" ->
            {
                final boolean fill = taskType.equals("Fill");

                if (!this.hasPermissionsForTask(player, taskType.toLowerCase(java.util.Locale.ROOT)))
                {
                    ServuxDebug.log(ServuxDebug.Cat.PERMISSION, "litematic_data: 拒绝 " + taskType + " Task from " + player.getName().getString() + "（task 权限不足）");
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(MSG_TASK_INSUFFICIENT));
                    return;
                }

                if (!player.isCreative())
                {
                    ServuxDebug.log(ServuxDebug.Cat.PERMISSION, "litematic_data: 拒绝 " + taskType + " Task from " + player.getName().getString() + "（非创造模式）");
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(MSG_TASK_CREATIVE_REQUIRED));
                    return;
                }

                List<Box> boxes = this.decodeBoxes(tags);

                if (fill)
                {
                    net.minecraft.world.level.block.state.BlockState fillState = tags.read("FillState", net.minecraft.world.level.block.state.BlockState.CODEC).orElse(null);

                    if (fillState == null)
                    {
                        if (this.shouldSendPlayerTaskFeedback())
                        {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(MSG_TASK_NO_FILL_STATE));
                        }
                        return;
                    }

                    if (boxes.isEmpty())
                    {
                        if (this.shouldSendPlayerTaskFeedback())
                        {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(MSG_TASK_NO_BOXES));
                        }
                        return;
                    }

                    net.minecraft.world.level.block.state.BlockState replaceState = tags.read("ReplaceState", net.minecraft.world.level.block.state.BlockState.CODEC).orElse(null);
                    boolean removeEntities = tags.getBooleanOr("RemoveEntities", false);
                    int interval = tags.getIntOr("Interval", 1);
                    FillDeleteTask task = new FillDeleteTask("Fill", level.getServer(), level, player, boxes, fillState, replaceState, removeEntities);
                    TaskScheduler.getInstance().scheduleTask(task, interval);
                }
                else
                {
                    if (boxes.isEmpty())
                    {
                        if (this.shouldSendPlayerTaskFeedback())
                        {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(MSG_TASK_NO_BOXES));
                        }
                        return;
                    }

                    boolean removeEntities = tags.getBooleanOr("RemoveEntities", false);
                    int interval = tags.getIntOr("Interval", 1);
                    // Delete = fillState=AIR 的 Fill（上游 TaskDeleteArea 同构）
                    FillDeleteTask task = new FillDeleteTask("Delete", level.getServer(), level, player, boxes,
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), null, removeEntities);
                    TaskScheduler.getInstance().scheduleTask(task, interval);
                }
            }
            // Save：上游整段注释（LitematicsDataProvider.java:400-428 "TODO (Ensure Safe Transmit)"）——同源忽略
            default ->
            {
                if (this.shouldSendPlayerTaskFeedback())
                {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(MSG_TASK_INVALID));
                }
            }
        }
    }

    /** 解析 "Boxes" 列表（客户端 Box.CODEC 产物：pos1/pos2 = IntArrayTag[x,y,z]，name = string）。 */
    private List<Box> decodeBoxes(CompoundTag tags)
    {
        ListTag list = tags.getListOrEmpty("Boxes");
        List<Box> boxes = new ArrayList<>();

        for (int i = 0; i < list.size(); ++i)
        {
            CompoundTag entry = list.getCompoundOrEmpty(i);

            if (entry != null && !entry.isEmpty())
            {
                Box box = decodeBox(entry);

                if (box != null)
                {
                    boxes.add(box);
                }
            }
        }

        return boxes;
    }

    /** 单个 Box 解码（形状黄金样本见 TaskGroupTest；public 供跨包单测）。 */
    public static Box decodeBox(CompoundTag entry)
    {
        // 26.1：getIntArray 返回 Optional<int[]>
        int[] p1 = entry.getIntArray("pos1").orElse(null);
        int[] p2 = entry.getIntArray("pos2").orElse(null);

        if (p1 == null || p2 == null || p1.length != 3 || p2.length != 3)
        {
            return null;
        }

        return new Box(new BlockPos(p1[0], p1[1], p1[2]), new BlockPos(p2[0], p2[1], p2[2]), entry.getStringOr("name", ""));
    }

    /**
     * TASK_STATUS_SYNC（type 16）下行：任务进度/完成帧的唯一出口（上游 onTaskStatusSync:439-454 四道门照抄）。
     */
    public void onTaskStatusSync(ServerPlayer player, CompoundTag tags)
    {
        if (!this.isPlayerRegistered(player) || !this.isEnabled() || tags == null || tags.isEmpty())
        {
            return;
        }

        if (!this.hasPermission(player))
        {
            ServuxDebug.log(ServuxDebug.Cat.PERMISSION, "litematic_data: 拒绝 onTaskStatusSync to " + player.getName().getString() + "（权限不足）");
            return;
        }

        HANDLER.encodeServerData(player, ServuxLitematicaPacket.TaskPacket(ServuxLitematicaPacket.Type.PACKET_S2C_TASK_STATUS_SYNC, tags));
    }

    @Override
    public void onPlayerJoin(ServerPlayer player)
    {
        if (!this.isEnabled())
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "litematic onPlayerJoin 跳过: provider disabled");
            return;
        }
        // 白名单：仅已注册玩家推送（旧客户端被版本门禁拒后永不入册 → 永不收 metadata）。
        if (this.isPlayerRegistered(player))
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "litematic onPlayerJoin: " + player.getName().getString()
                    + " → 直推 sendMetadata（已注册）");
            this.sendMetadata(player);
        }
    }

    @Override
    public void onPlayerRegisterChannel(ServerPlayer player, String channel)
    {
        // ★ 修复 litematic sync not_enabled：onPlayerJoin 时通道未声明，sendMetadata 丢弃；
        // 客户端声明 servux:litematics（= 装了 Litematica）时立即重发。sendMetadata 幂等。
        // 白名单：声明通常先于客户端首个 REGISTER 到达，此时重发被挡——metadata 首达由 REGISTER 应答链保证。
        if (this.getNetworkChannel().toString().equals(channel) && this.isPlayerRegistered(player))
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "litematic onPlayerRegisterChannel: 客户端声明 " + channel + " → 重发 metadata");
            this.sendMetadata(player);
        }
        else if (channel.startsWith("servux:"))
        {
            ServuxDebug.log(ServuxDebug.Cat.NETWORK, "litematic onPlayerRegisterChannel: 收到 servux 声明 " + channel
                    + "（非本通道 servux:" + this.getNetworkChannel().getPath() + "，忽略）");
        }
    }

    @Override public void onPlayerQuit(ServerPlayer player)
    {
        this.removePlayer(player);
        HANDLER.onPlayerQuit(player.getUUID());
        // ★ 有意不取消该玩家的进行中任务（上游语义：任务跑完、帧/消息发死连接被静默丢弃）——
        //   保证世界方块结果一致性；发送路径在任务基类（LitematicaTask）内按 UUID 解析，退出后自动跳过。
    }

    /** task 组调度驱动（对应上游 MixinMinecraftServer tickServer RETURN → TaskScheduler.runTasks）。 */
    @Override public void onTickEndPre() { TaskScheduler.getInstance().runTasks(); }
    @Override public void onTickEndPost() { /* NO-OP */ }
}
