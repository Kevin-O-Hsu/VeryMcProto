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
 * <p><b>投影粘贴 / 投递</b>：客户端上传的 .litematic 经 ServuxLitematicaHandler 重组后，由
 * {@link #handleClientPasteRequest} / {@link #handleClientPasteRequestPair} 加载为 SchematicPlacement
 * 并 pasteTo 放置到世界（含 ReplaceMode / PasteLayerBehavior / LayerRange）；文件投递（Transmit*）走
 * LitematicaSchematic.receiveFileTransmit 落盘到 schematics/。详见 schematic 子系统。
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
    private final List<IServuxSetting<?>> settings = List.of(
            this.permissionLevel, this.pastePermissionLevel,
            this.taskPermissionLevel, this.playerTaskFeedback,
            this.fixRailRotations, this.fixStairMirror, this.fixChestMirror
    );

    private final List<UUID> invalidPlayers = new ArrayList<>();
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

    @Override public boolean isPlayerRegistered(ServerPlayer player) { return !this.isPlayerInvalid(player); }

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

    public void onPacketFailure(ServerPlayer player) { this.setPlayerInvalid(player); }

    public void removePlayer(ServerPlayer player) { this.removeInvalidPlayer(player); }

    private void setPlayerInvalid(ServerPlayer player) { if (!this.invalidPlayers.contains(player.getUUID())) { this.invalidPlayers.add(player.getUUID()); } }
    private boolean isPlayerInvalid(ServerPlayer player) { return this.invalidPlayers.contains(player.getUUID()); }
    private void removeInvalidPlayer(ServerPlayer player) { this.invalidPlayers.remove(player.getUUID()); }

    public void onBlockEntityRequest(ServerPlayer player, BlockPos pos)
    {
        if (!this.hasPermission(player) || !this.isEnabled()) { return; }

        BlockEntity be = player.level().getBlockEntity(pos);
        CompoundTag nbt = be != null ? be.saveWithFullMetadata(player.registryAccess()) : new CompoundTag();
        HANDLER.encodeServerData(player, ServuxLitematicaPacket.SimpleBlockResponse(pos, nbt));
    }

    public void onEntityRequest(ServerPlayer player, int entityId)
    {
        if (!this.hasPermission(player) || !this.isEnabled()) { return; }

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

                if (entity.getType() == EntityType.PLAYER)
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
        if (!this.hasPermission(player) || !this.isEnabled())
        {
            Reference.logger().warning("litematic_data: 拒绝 onBulkEntityRequest from " + player.getName().getString() + "（权限不足）");
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cLitematics bulk request: insufficient permissions."));
            return;
        }
        if (req == null || req.isEmpty()) { return; }

        ServerLevel world = (ServerLevel) player.level();
        LevelChunk chunk = world.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());

        if (chunk == null)
        {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cLitematics bulk request: chunk not loaded " + chunkPos.toString()));
            return;
        }

        // 区分"批量实体请求"任务（原版兼容：无 Task 字段也走此分支）
        if ((req.contains("Task") && req.getStringOr("Task", "").equals("BulkEntityRequest")) || !req.contains("Task"))
        {
            ServuxDebug.log(ServuxDebug.Cat.PACKET, "litematic_data: 批量 NBT ChunkPos " + chunkPos.toString() + " → " + player.getName().getString());

            long timeStart = System.currentTimeMillis();
            ListTag tileList = new ListTag();
            ListTag entityList = new ListTag();
            int minY = req.getIntOr("minY", -64);
            int maxY = req.getIntOr("maxY", 319);
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
                CompoundTag beTag = be != null ? be.saveWithFullMetadata(player.registryAccess()) : new CompoundTag();
                tileList.add(beTag);
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
    public void handleClientPasteRequest(ServerPlayer player, CompoundTag tags)
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
            ServuxDebug.log(ServuxDebug.Cat.SCHEMATIC, "litematic paste 受理 ← " + player.getName().getString()
                    + " keys=" + tags.keySet()
                    + " ReplaceMode=" + tags.getStringOr("ReplaceMode", "?")
                    + " PasteLayerBehavior=" + tags.getStringOr("PasteLayerBehavior", "?"));
            long timeStart = System.currentTimeMillis();
            SchematicPlacement placement = SchematicPlacement.createFromNbt(tags);
            ReplaceBehavior replaceMode = ReplaceBehavior.fromStringStatic(tags.getStringOr("ReplaceMode", ReplaceBehavior.NONE.name()));
            PasteLayerBehavior layerBehavior = PasteLayerBehavior.fromStringStatic(tags.getStringOr("PasteLayerBehavior", PasteLayerBehavior.ALL.name()));
            LayerRange layerRange = tags.read("RenderLayerRange", LayerRange.CODEC).orElse(null);
            ServuxDebug.log(ServuxDebug.Cat.SCHEMATIC, "litematic paste 执行: placement=" + placement.getName()
                    + " origin=" + placement.getOrigin() + " dim=" + player.level().dimension().identifier());
            placement.pasteTo(player.level(), replaceMode, layerBehavior, layerRange);
            long timeElapsed = System.currentTimeMillis() - timeStart;
            ServuxDebug.log(ServuxDebug.Cat.SCHEMATIC, "litematic paste 完成: " + timeElapsed + "ms");
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§aPasted §b" + placement.getName() + "§r to §d" + player.level().dimension().identifier().toString() + "§r in §a" + timeElapsed + "§rms."));
        }
        else if (tags != null)
        {
            ServuxDebug.log(ServuxDebug.Cat.SCHEMATIC, "litematic paste 忽略: Task=" + tags.getStringOr("Task", "(无)") + "（非 LitematicaPaste）");
        }
    }

    public void handleClientPasteRequestPair(ServerPlayer player, Pair<LitematicaSchematic, CompoundTag> schemPair)
    {
        if (!this.isEnabled()) { return; }

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

        if (schemPair.getLeft() != null)
        {
            ServuxDebug.log(ServuxDebug.Cat.SCHEMATIC, "litematic_data: 执行粘贴(Pair) from " + player.getName().getString());
            long timeStart = System.currentTimeMillis();
            CompoundTag tags = schemPair.getRight();
            SchematicPlacement placement = SchematicPlacement.createFromNbt(schemPair.getLeft(), tags);
            ReplaceBehavior replaceMode = ReplaceBehavior.fromStringStatic(tags.getStringOr("ReplaceMode", ReplaceBehavior.NONE.name()));
            PasteLayerBehavior layerBehavior = PasteLayerBehavior.fromStringStatic(tags.getStringOr("PasteLayerBehavior", PasteLayerBehavior.ALL.name()));
            LayerRange layerRange = tags.read("RenderLayerRange", LayerRange.CODEC).orElse(null);
            placement.pasteTo(player.level(), replaceMode, layerBehavior, layerRange);
            long timeElapsed = System.currentTimeMillis() - timeStart;
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§aPasted §b" + placement.getName() + "§r to §d" + player.level().dimension().identifier().toString() + "§r in §a" + timeElapsed + "§rms."));
        }
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

    // ───── task 组（type 14-17，26.1 移植；对照上游 LitematicsDataProvider.onTaskRequest:272-437）─────

    /** task 组反馈文案（上游 servux en_us.json 原文）。 */
    private static final String MSG_TASK_INSUFFICIENT = "§cServux: Insufficient Permissions for Litematic task operations.§r";
    private static final String MSG_TASK_CREATIVE_REQUIRED = "§cServux: Creative Mode is required for this Litematic Task Request.§r";
    private static final String MSG_TASK_NO_FILL_STATE = "§cServux: No fill state provided.§r";
    private static final String MSG_TASK_NO_BOXES = "§cServux: No fill area boxes provided.§r";
    private static final String MSG_TASK_INVALID = "§cServux: Invalid task type provided.§r";

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
        // plugin messaging 握手需时间，直接 sendMetadata（与 Entities 一致；configuration phase 多半失败，由 onPlayerRegisterChannel 补救）
        ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "litematic onPlayerJoin: " + player.getName().getString()
                + " → 直推 sendMetadata（此时通道多半未声明，config phase 可能丢弃）");
        this.sendMetadata(player);
    }

    @Override
    public void onPlayerRegisterChannel(ServerPlayer player, String channel)
    {
        // ★ 修复 litematic sync not_enabled：onPlayerJoin 时通道未声明，sendMetadata 丢弃；
        // 客户端声明 servux:litematics（= 装了 Litematica）时立即重发。sendMetadata 幂等。
        if (this.getNetworkChannel().toString().equals(channel))
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
        //   保证世界方块结果一致性；发送路径在 FillDeleteTask 内按 UUID 解析，退出后自动跳过。
    }

    /** task 组调度驱动（对应上游 MixinMinecraftServer tickServer RETURN → TaskScheduler.runTasks）。 */
    @Override public void onTickEndPre() { TaskScheduler.getInstance().runTasks(); }
    @Override public void onTickEndPost() { /* NO-OP */ }
}
