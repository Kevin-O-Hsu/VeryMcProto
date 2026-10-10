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
import verymc.top.veryMcProto.mod.servux.schematic.placement.SchematicPlacement;
import verymc.top.veryMcProto.mod.servux.util.ReplaceBehavior;
import verymc.top.veryMcProto.mod.servux.util.PasteLayerBehavior;
import verymc.top.veryMcProto.mod.servux.util.LayerRange;
import java.nio.file.Files;
import java.nio.file.Path;
import verymc.top.veryMcProto.mod.servux.util.nbt.NbtView;

/**
 * Litematics Provider（mod 层，配 Litematica）。移植自原版 {@code LitematicsDataProvider}
 * （通道 servux:litematics，协议版本 1）。
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
 * <p><b>投影粘贴</b>：客户端上传的投影 NBT 经 ServuxLitematicaHandler 重组后由
 * {@link #handleClientPasteRequest} 加载为 SchematicPlacement 并 pasteTo 放置到世界
 * （含 ReplaceMode / PasteLayerBehavior / LayerRange）。
 * <b>C2S 文件接收链（Transmit* → receiveFileTransmit 落盘）已于 2026-10 随安全修复整链移除</b>
 *（路径穿越任意写/删/读回，上游公告漏洞同源；上游 0.9.5 同判禁用）。详见 schematic 子系统。
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
    public ServuxBoolSetting fixRailRotations = new ServuxBoolSetting(this, "fix_rail_rotations", true);
    public ServuxBoolSetting fixStairMirror = new ServuxBoolSetting(this, "fix_stairs_mirror", true);
    public ServuxBoolSetting fixChestMirror = new ServuxBoolSetting(this, "fix_chest_mirror", true);
    /** 粘贴实体去重（上游键名 deduplicate_schematic_entities，默认 false，LitematicsDataProvider.java:71）：
     *  false = 撞车重排开（id/UUID 与世界撞车时改派新值）；true = 跳过重排，依赖原版 UUID 唯一性拒绝重复实体。 */
    public final ServuxBoolSetting deDuplicateSchematicEntities = new ServuxBoolSetting(this, "deduplicate_schematic_entities", false);

    /** bulk/task 完成反馈（上游 26.x 键名 player_task_feedback，默认 false，LitematicsDataProvider.java:67——
     *  1.21.11 上游无此 setting，随 bulk 反馈门一并移植）。 */
    public final ServuxBoolSetting playerTaskFeedback = new ServuxBoolSetting(this, "player_task_feedback", false);
    private final List<IServuxSetting<?>> settings = List.of(
            this.permissionLevel, this.pastePermissionLevel,
            this.fixRailRotations, this.fixStairMirror, this.fixChestMirror,
            this.playerTaskFeedback, this.deDuplicateSchematicEntities
    );

    private final List<UUID> invalidPlayers = new ArrayList<>();
    /** 注册名册（上游 LitematicsDataProvider:55 同构）：registerPlayer() 入册、onPacketFailure/removePlayer 出册。 */
    private final List<UUID> registeredPlayers = new ArrayList<>();

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

    /** Schematic 文件目录（plugins/VeryMcProto/schematics/），首次自动创建。移植自原版 getTransmitDir。
     * C2S 接收链移除后仅服务命令层（list 只读枚举 / transmit 只读发送）。 */
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

    @Override public boolean isPlayerRegistered(ServerPlayer player) { return this.registeredPlayers.contains(player.getUUID()) && !this.isPlayerInvalid(player); }

    /**
     * 名册注册（上游 :152-175 同构，方法名保持 registerPlayer）：isEnabled/hasPermission 拒绝即拒发且不入册。
     */
    public void registerPlayer(ServerPlayer player)
    {
        if (!this.isEnabled()) { return; }

        if (!this.hasPermission(player))
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "litematic register 拒绝 " + player.getName().getString() + " (权限不足)");
            return;
        }

        this.registeredPlayers.add(player.getUUID());

        this.sendMetadata(player);
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
        ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "litematic sendMetadata → " + player.getName().getString()
                + " ok=" + ok + " servux=" + this.metadata.getStringOr("servux", "?")
                + " ver=" + this.metadata.getIntOr("version", -1));
    }

    public void onPacketFailure(ServerPlayer player)
    {
        this.setPlayerInvalid(player);
        this.registeredPlayers.remove(player.getUUID());
    }

    public void removePlayer(ServerPlayer player)
    {
        this.removeInvalidPlayer(player);
        this.registeredPlayers.remove(player.getUUID());
    }

    private void setPlayerInvalid(ServerPlayer player) { if (!this.invalidPlayers.contains(player.getUUID())) { this.invalidPlayers.add(player.getUUID()); } }
    private boolean isPlayerInvalid(ServerPlayer player) { return this.invalidPlayers.contains(player.getUUID()); }
    private void removeInvalidPlayer(ServerPlayer player) { this.invalidPlayers.remove(player.getUUID()); }

    public void onBlockEntityRequest(ServerPlayer player, BlockPos pos)
    {
        // 对齐 26.x 上游 :475-484：名册门前置且静默（未注册不回任何消息），权限门在后
        if (!this.isPlayerRegistered(player) || !this.isEnabled()) { return; }
        if (!this.hasPermission(player)) { return; }

        BlockEntity be = player.level().getBlockEntity(pos);
        CompoundTag nbt = be != null ? be.saveWithFullMetadata(player.registryAccess()) : new CompoundTag();
        HANDLER.encodeServerData(player, ServuxLitematicaPacket.SimpleBlockResponse(pos, nbt));
    }

    public void onEntityRequest(ServerPlayer player, int entityId)
    {
        // 对齐 26.x 上游 :498-507：名册门前置且静默，权限门在后
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

                // 对齐 26.x 上游 :522：查询者查自己时保留背包/末影箱（!uuid.equals 才进入剥离判断；1.21.11 上游无此门——我方加固）
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
        // 对齐 26.x 上游 :544：名册门 + enabled + null/isEmpty 首查（未注册静默，先于权限消息）
        if (!this.isPlayerRegistered(player) || !this.isEnabled() || req == null || req.isEmpty()) { return; }

        if (!this.hasPermission(player))
        {
            Reference.logger().warning("litematic_data: Denying onBulkEntityRequest from " + player.getName().getString() + "（权限不足）");
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(MSG_BULK_INSUFFICIENT));
            return;
        }

        ServerLevel world = (ServerLevel) player.level();
        LevelChunk chunk = world.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);

        if (chunk == null)
        {
            // 对齐 26.x 上游 :562-565：chunk 未加载消息受 player_task_feedback 门控
            if (this.shouldSendPlayerTaskFeedback())
            {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(MSG_BULK_CHUNK_NOT_LOADED.formatted(chunkPos.toString())));
            }
            return;
        }

        // 对齐 26.x 上游 :570-571：Task 字段须存在 + 值相等（litematica 1.21.11 客户端恒带此字段——EntityDataManager:692 实证，
        // 无 Task 的旧形态包不再受理）。vanilla CompoundTag 无 contains(String,int) 重载（上游系 malilib API）——
        // getStringOr 对非 String 类型恒回退默认 ""，故「contains && getStringOr().equals」与上游 TAG_STRING 类型校验语义等价
        if (req.contains("Task") && req.getStringOr("Task", "").equals("BulkEntityRequest"))
        {
            ServuxDebug.log(ServuxDebug.Cat.PACKET, "litematic_data: 批量 NBT ChunkPos " + chunkPos.toString() + " → " + player.getName().getString());

            long timeStart = System.currentTimeMillis();
            ListTag tileList = new ListTag();
            ListTag entityList = new ListTag();
            // 对齐 26.x 上游 :577-578：回退维度实际上下界（自定义高度维度不再错位切片；客户端恒发 minY/maxY，回退仅兜底）
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

                // 对齐 26.x 上游 :594-600：BE 不存在的条目直接跳过（有意行为变更——1.21.11 上游发空 tag，但空帧会被客户端
                // EntityDataManager:872-884 以 loadWithComponents(空) 清空活 BE，跳过才是正确行为）
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
            output.putInt("chunkX", chunkPos.x);
            output.putInt("chunkZ", chunkPos.z);
            long timeElapsed = System.currentTimeMillis() - timeStart;

            HANDLER.encodeServerData(player, ServuxLitematicaPacket.ResponseS2CStart(output));

            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§aLitematics bulk reply: §r" + world.dimension().identifier().toString()
                            + " " + chunkPos.toString() + " §bTE=" + tileList.size()
                            + " §bE=" + entityList.size() + " §7(" + timeElapsed + "ms)"), false);
        }
    }

    /**
     * 粘贴请求：从客户端上传的 NBT 加载 SchematicPlacement，按 ReplaceMode / PasteLayerBehavior /
     * LayerRange 调 SchematicPlacement.pasteTo 放置到玩家所在世界。需创造模式 + paste 权限。
     */
    public void handleClientPasteRequest(ServerPlayer player, int transactionId, CompoundTag tags)
    {
        if (!this.isEnabled()) { return; }

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

        if (tags != null && tags.getStringOr("Task", "").equals("LitematicaPaste"))
        {
            ServuxDebug.log(ServuxDebug.Cat.SCHEMATIC, "litematic_data: 执行粘贴 from " + player.getName().getString());
            long timeStart = System.currentTimeMillis();
            SchematicPlacement placement = SchematicPlacement.createFromNbt(tags);
            ReplaceBehavior replaceMode = ReplaceBehavior.fromStringStatic(tags.getStringOr("ReplaceMode", ReplaceBehavior.NONE.name()));
            PasteLayerBehavior layerBehavior = PasteLayerBehavior.fromStringStatic(tags.getStringOr("PasteLayerBehavior", PasteLayerBehavior.ALL.name()));
            LayerRange layerRange = tags.read("RenderLayerRange", LayerRange.CODEC).orElse(null);
            placement.pasteTo(player.level(), replaceMode, layerBehavior, layerRange);
            long timeElapsed = System.currentTimeMillis() - timeStart;
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§aPasted §b" + placement.getName() + "§r to §d" + player.level().dimension().identifier().toString() + "§r in §a" + timeElapsed + "§rms."), false);
        }
    }

    // handleClientPasteRequestPair（Transmit 文件上传路径的粘贴受理）已随 C2S 接收链安全修复移除
    //（2026-10）：其唯一调用方为 handler 的 Transmit 分流；移除后所有粘贴统一走 handleClientPasteRequest。
    // 恢复走 git revert 该修复 commit。


    @Override public boolean hasPermission(ServerPlayer player) { return Perms.check(player, this.permNode, this.permissionLevel.getValue()); }

    public boolean hasPermissionsForPaste(ServerPlayer player)
    {
        return this.hasPermission(player) && Perms.check(player, this.permNode + ".paste", this.pastePermissionLevel.getValue());
    }

    @Override
    public void onPlayerJoin(ServerPlayer player)
    {
        if (!this.isEnabled()) { return; }
        // 上游 PlayerListener join→register 同构（Paper 适配：configuration phase 多半失败，
        // 由 onPlayerRegisterChannel 名册白名单重发与 C2S METADATA_REQUEST→registerPlayer 兜底）
        this.registerPlayer(player);
    }

    @Override
    public void onPlayerRegisterChannel(ServerPlayer player, String channel)
    {
        // ★ 修复 litematic sync not_enabled：onPlayerJoin 时通道未声明，sendMetadata 丢弃；
        // 客户端声明 servux:litematics（= 装了 Litematica）时立即重发。sendMetadata 幂等。
        if (this.getNetworkChannel().toString().equals(channel))
        {
            // 名册白名单：仅已注册玩家重发（上游无此补偿路径；deny 语义不被旁路）
            if (!this.isPlayerRegistered(player)) { return; }
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "litematic onPlayerRegisterChannel: 客户端声明 " + channel + " → 重发 metadata");
            this.sendMetadata(player);
        }
    }

    /** 粘贴实体去重开关（上游 shouldDeDuplicateEntities:777-780）。 */
    public boolean shouldDeDuplicateEntities() { return this.deDuplicateSchematicEntities.getValue(); }

    /** bulk/task 反馈门（上游 26.x shouldSendPlayerTaskFeedback）。 */
    public boolean shouldSendPlayerTaskFeedback() { return this.playerTaskFeedback.getValue(); }

    // bulk 反馈文案（26.x 上游 en_us.json 原文，5b00967 同款常量化）
    private static final String MSG_BULK_INSUFFICIENT = "§cServux: Insufficient Permissions for the Litematic Bulk NBT Data Request operation.§r";
    private static final String MSG_BULK_CHUNK_NOT_LOADED = "§cServux: Bulk NBT Data Request Error loading Chunk located at %s§r";
    private static final String MSG_BULK_ACKNOWLEDGE = "Servux: Bulk NBT Data from world §d%s§r for chunk §e%s§r, [TE: §a%d§r, E: §a%d§r] delivered in §b%d §fms.";

    @Override public void onPlayerQuit(ServerPlayer player)
    {
        this.removePlayer(player);
        HANDLER.onPlayerQuit(player.getUUID());
    }

    @Override public void onTickEndPre() { /* NO-OP */ }
    @Override public void onTickEndPost() { /* NO-OP */ }
}
