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
import verymc.top.veryMcProto.framework.debug.Debug;
import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.framework.network.ServerPlayHandler;
import verymc.top.veryMcProto.framework.permission.Perms;
import verymc.top.veryMcProto.framework.settings.IServuxSetting;
import verymc.top.veryMcProto.framework.settings.ServuxBoolSetting;
import verymc.top.veryMcProto.framework.settings.ServuxIntSetting;
import verymc.top.veryMcProto.mod.servux.ServuxLog;
import verymc.top.veryMcProto.mod.servux.ServuxReference;
import verymc.top.veryMcProto.mod.servux.network.ServuxLitematicaHandler;
import verymc.top.veryMcProto.mod.servux.network.ServuxLitematicaPacket;
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
 * <p><b>降级</b>：投影文件投递（C2S 上传）+ 粘贴（{@link #handleClientPasteRequest}）— schematic 投影系统未移植，
 * 粘贴请求返回「功能未实现」提示，上传在 Handler 层静默忽略。
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
    private final List<IServuxSetting<?>> settings = List.of(
            this.permissionLevel, this.pastePermissionLevel,
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
            Debug.log(Debug.Cat.HANDSHAKE, "litematic sendMetadata 跳过: provider disabled");
            return;
        }
        if (!this.hasPermission(player))
        {
            Debug.log(Debug.Cat.HANDSHAKE, "litematic sendMetadata 拒绝 " + player.getName().getString() + " (权限不足)");
            return;
        }
        boolean ok = HANDLER.sendPlayPayload(player, ServuxLitematicaPacket.MetadataResponse(this.metadata));
        Debug.log(Debug.Cat.HANDSHAKE, "litematic sendMetadata → " + player.getName().getString()
                + " ok=" + ok + " servux=" + this.metadata.getStringOr("servux", "?")
                + " ver=" + this.metadata.getIntOr("version", -1));
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
            ServuxLog.debug("onEntityRequest 失败 entityId=" + entityId + ": " + e.getMessage());
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
        LevelChunk chunk = world.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);

        if (chunk == null)
        {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cLitematics bulk request: chunk not loaded " + chunkPos.toString()));
            return;
        }

        // 区分"批量实体请求"任务（原版兼容：无 Task 字段也走此分支）
        if ((req.contains("Task") && req.getStringOr("Task", "").equals("BulkEntityRequest")) || !req.contains("Task"))
        {
            ServuxLog.debug("litematic_data: 批量 NBT ChunkPos " + chunkPos.toString() + " → " + player.getName().getString());

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
     * 降级：粘贴请求。原版加载投影 + placement.pasteTo；schematic 系统未移植，返回未实现提示。
     */
    public void handleClientPasteRequest(ServerPlayer player, int transactionId, CompoundTag tags)
    {
        if (!this.isEnabled()) { return; }

        if (!this.hasPermission(player) || !this.hasPermissionsForPaste(player))
        {
            ServuxLog.debug("litematic_data: 拒绝粘贴 from " + player.getName().getString() + "（权限不足）");
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cLitematics paste: insufficient permissions."));
            return;
        }
        if (!player.isCreative())
        {
            ServuxLog.debug("litematic_data: 拒绝粘贴 from " + player.getName().getString() + "（非创造模式）");
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cLitematics paste: creative mode required."));
            return;
        }

        if (tags != null && tags.getStringOr("Task", "").equals("LitematicaPaste"))
        {
            ServuxLog.debug("litematic_data: 收到粘贴请求 from " + player.getName().getString() + "，schematic 系统未移植，已降级");
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("§c粘贴功能未实现（schematic 系统未移植）"), false);
        }
    }

    @Override public boolean hasPermission(ServerPlayer player) { return Perms.check(player, this.permNode, this.permissionLevel.getValue()); }

    public boolean hasPermissionsForPaste(ServerPlayer player)
    {
        return this.hasPermission(player) && Perms.check(player, this.permNode + ".paste", this.pastePermissionLevel.getValue());
    }

    @Override
    public void onPlayerJoin(ServerPlayer player)
    {
        if (!this.isEnabled()) { return; }
        // plugin messaging 握手需时间，直接 sendMetadata（与 Entities 一致；configuration phase 多半失败，由 onPlayerRegisterChannel 补救）
        this.sendMetadata(player);
    }

    @Override
    public void onPlayerRegisterChannel(ServerPlayer player, String channel)
    {
        // ★ 修复 litematic sync not_enabled：onPlayerJoin 时通道未声明，sendMetadata 丢弃；
        // 客户端声明 servux:litematics（= 装了 Litematica）时立即重发。sendMetadata 幂等。
        if (this.getNetworkChannel().toString().equals(channel))
        {
            Debug.log(Debug.Cat.HANDSHAKE, "litematic onPlayerRegisterChannel: 客户端声明 " + channel + " → 重发 metadata");
            this.sendMetadata(player);
        }
    }

    @Override public void onPlayerQuit(ServerPlayer player) { this.removePlayer(player); }

    @Override public void onTickEndPre() { /* NO-OP */ }
    @Override public void onTickEndPost() { /* NO-OP */ }
}
