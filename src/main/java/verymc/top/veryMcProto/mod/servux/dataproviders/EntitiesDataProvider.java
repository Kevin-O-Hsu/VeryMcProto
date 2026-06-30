package verymc.top.veryMcProto.mod.servux.dataproviders;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;

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
import verymc.top.veryMcProto.mod.servux.network.ServuxEntitiesHandler;
import verymc.top.veryMcProto.mod.servux.network.ServuxEntitiesPacket;
import verymc.top.veryMcProto.mod.servux.util.nbt.NbtView;

/**
 * Entities Provider（mod 层）。移植自原版 {@code EntitiesDataProvider}（通道 servux:entity_data，协议版本 1）。
 *
 * <p>方块实体 NBT：{@code be.saveWithFullMetadata(registryAccess)}（NMS 公开）。
 * 实体 NBT：{@link NbtView#getWriter} + {@code entity.saveWithoutId}（绕开 Mixin）。
 *
 * <p><b>适配</b>：Permissions→{@link Perms}；hasNbtQueryPermission 的 COMMANDS_GAMEMASTER（原版 NMS Permissions）
 * 改为按 op level 2（等价 /data get 默认权限）；registerHandler 去 registerPlayPayload/receiver；sendMetadata 走 plugin messaging。
 *
 * <p><b>降级</b>：fixAllayGathering（原 Mixin 改行为）仅保留 setting，服务端不真生效（Paper 无 Mixin）。
 */
public class EntitiesDataProvider extends DataProviderBase
{
    public static final EntitiesDataProvider INSTANCE = new EntitiesDataProvider();
    protected static final ServuxEntitiesHandler HANDLER = ServuxEntitiesHandler.getInstance();

    protected final CompoundTag metadata = new CompoundTag();
    private final ServuxIntSetting permissionLevel = new ServuxIntSetting(this, "permission_level", 0, 4, 0);
    private final ServuxBoolSetting nbtQueryOverride = new ServuxBoolSetting(this, "nbt_query_override", false);
    private final ServuxIntSetting nbtQueryPermissionLevel = new ServuxIntSetting(this, "nbt_query_permission_level", 2, 4, 0);
    private final ServuxBoolSetting fixAllayGathering = new ServuxBoolSetting(this, "fix_allay_gathering", true);
    private final ServuxBoolSetting nbtAllowPlayerInventory = new ServuxBoolSetting(this, "nbt_allow_player_inventory", true);
    private final ServuxBoolSetting nbtAllowPlayerEnderItems = new ServuxBoolSetting(this, "nbt_allow_player_ender_items", true);
    private final ServuxIntSetting playerInventoryPermissionLevel = new ServuxIntSetting(this, "player_inventory_permission_level", 2, 4, 0);
    private final ServuxIntSetting playerEnderItemsPermissionLevel = new ServuxIntSetting(this, "player_ender_items_permission_level", 2, 4, 0);
    private final List<IServuxSetting<?>> settings = List.of(
            this.permissionLevel, this.nbtQueryOverride, this.nbtQueryPermissionLevel, this.fixAllayGathering,
            this.nbtAllowPlayerInventory, this.nbtAllowPlayerEnderItems,
            this.playerInventoryPermissionLevel, this.playerEnderItemsPermissionLevel
    );

    private final List<UUID> invalidPlayers = new ArrayList<>();

    protected EntitiesDataProvider()
    {
        super("entity_data",
                ServuxEntitiesHandler.CHANNEL_ID,
                ServuxEntitiesPacket.PROTOCOL_VERSION,
                0, ServuxReference.MOD_ID + ".provider.entity_data",
                "Entity Data provider for Client Side mods.");

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

    @Override public boolean isPlayerRegistered(ServerPlayer player) { return !this.isPlayerInvalid(player); }

    public void sendMetadata(ServerPlayer player)
    {
        if (!this.isEnabled())
        {
            Debug.log(Debug.Cat.HANDSHAKE, "entity sendMetadata 跳过: provider disabled");
            return;
        }
        if (!this.hasPermission(player))
        {
            Debug.log(Debug.Cat.HANDSHAKE, "entity sendMetadata 拒绝 " + player.getName().getString() + " (权限不足)");
            return;
        }
        boolean ok = HANDLER.sendPlayPayload(player, ServuxEntitiesPacket.MetadataResponse(this.metadata));
        Debug.log(Debug.Cat.HANDSHAKE, "entity sendMetadata → " + player.getName().getString()
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
        HANDLER.encodeServerData(player, ServuxEntitiesPacket.SimpleBlockResponse(pos, nbt));
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
                    if (!this.hasPlayerInventoryPermission(player)) { nbt.remove("Inventory"); nbt.put("Inventory", new ListTag()); }
                    if (!this.hasPlayerEnderItemsPermission(player)) { nbt.remove("EnderItems"); nbt.put("EnderItems", new ListTag()); }
                }

                if (id != null) { nbt.putString("id", id.toString()); }
                HANDLER.encodeServerData(player, ServuxEntitiesPacket.SimpleEntityResponse(entityId, nbt));
            }
        }
        catch (Exception e)
        {
            ServuxLog.debug("onEntityRequest 失败 entityId=" + entityId + ": " + e.getMessage());
        }
    }

    public boolean hasNbtQueryOverride() { return this.isEnabled() && this.nbtQueryOverride.getValue(); }
    public boolean hasFixAllayGathering() { return this.isEnabled() && this.fixAllayGathering.getValue(); }

    /**
     * NBT 查询权限。原版：override 时按节点，否则 COMMANDS_GAMEMASTER（op level 2，等价 /data get）。
     * Paper：override 时 Perms.check 节点；否则按 op level 2。
     * Tweaks Provider 复用此方法。
     */
    public boolean hasNbtQueryPermission(ServerPlayer player)
    {
        if (this.nbtQueryOverride.getValue())
        {
            return Perms.check(player, this.permNode + ".nbt_query_override", this.nbtQueryPermissionLevel.getValue());
        }
        return Perms.check(player, "minecraft.command.data", 2);
    }

    public boolean hasNbtAllowPlayerInventory() { return this.nbtAllowPlayerInventory.getValue(); }

    /** Tweaks Provider 复用。 */
    public boolean hasPlayerInventoryPermission(ServerPlayer player)
    {
        if (this.hasNbtAllowPlayerInventory())
        {
            return Perms.check(player, this.permNode + ".nbt_allow_player_inventory", this.playerInventoryPermissionLevel.getValue());
        }
        return false;
    }

    public boolean hasNbtAllowPlayerEnderItems() { return this.nbtAllowPlayerEnderItems.getValue(); }

    public boolean hasPlayerEnderItemsPermission(ServerPlayer player)
    {
        if (this.hasNbtAllowPlayerEnderItems())
        {
            return Perms.check(player, this.permNode + ".nbt_allow_player_ender_items", this.playerEnderItemsPermissionLevel.getValue());
        }
        return false;
    }

    @Override public boolean hasPermission(ServerPlayer player) { return Perms.check(player, this.permNode, this.permissionLevel.getValue()); }

    @Override public void onPlayerJoin(ServerPlayer player) { this.sendMetadata(player); }

    @Override
    public void onPlayerRegisterChannel(ServerPlayer player, String channel)
    {
        // ★ 修复 entity sync not_enabled 根因：onPlayerJoin 时客户端尚未声明 servux:entity_data（configuration phase），
        // sendMetadata 的 ProtocolChannel.send 会因 getListeningPluginChannels 不含该通道而失败丢弃 metadata。
        // 客户端声明该通道（= 装了实体查询 mod）时立即重发，确保 metadata 可达。sendMetadata 幂等，重复无害。
        if (this.getNetworkChannel().toString().equals(channel))
        {
            Debug.log(Debug.Cat.HANDSHAKE, "entity onPlayerRegisterChannel: 客户端声明 " + channel + " → 重发 metadata");
            this.sendMetadata(player);
        }
    }

    @Override public void onPlayerQuit(ServerPlayer player) { this.removePlayer(player); }

    @Override public void onTickEndPre() { /* NO-OP */ }
    @Override public void onTickEndPost() { /* NO-OP */ }
}
