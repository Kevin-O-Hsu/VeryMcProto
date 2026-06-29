package verymc.top.veryMcProto.mod.servux.dataproviders;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import verymc.top.veryMcProto.framework.dataproviders.DataProviderBase;
import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.framework.network.ServerPlayHandler;
import verymc.top.veryMcProto.framework.permission.Perms;
import verymc.top.veryMcProto.framework.settings.IServuxSetting;
import verymc.top.veryMcProto.framework.settings.IServuxSettingCallback;
import verymc.top.veryMcProto.framework.settings.ServuxBoolSetting;
import verymc.top.veryMcProto.framework.settings.ServuxIntSetting;
import verymc.top.veryMcProto.mod.servux.ServuxLog;
import verymc.top.veryMcProto.mod.servux.ServuxReference;
import verymc.top.veryMcProto.mod.servux.network.ServuxTweaksHandler;
import verymc.top.veryMcProto.mod.servux.network.ServuxTweaksPacket;
import verymc.top.veryMcProto.mod.servux.util.nbt.NbtView;

/**
 * Tweaks Provider（mod 层）。移植自原版 {@code TweaksDataProvider}（通道 servux:tweaks，协议版本 1）。
 *
 * <p>方块实体 NBT：{@code be.saveWithFullMetadata(registryAccess)}（NMS 公开，与 Entities 一致）。
 * 实体 NBT：{@link NbtView#getWriter} + {@code entity.saveWithoutId}；NBT 查询权限 / 玩家背包权限
 * 直接复用 {@link EntitiesDataProvider}（与原版一致）。
 *
 * <p><b>适配</b>：Permissions→{@link Perms}；registerHandler 去 registerPlayPayload/receiver；
 * sendMetadata 走 plugin messaging（去 networkHandler 重载分支）；tick 去 ProfilerFiller 形参与 push/pop。
 *
 * <p><b>降级</b>：潜影盒堆叠的服务端行为（原 Mixin 改 {@code ItemStack}/{@code Hopper} 逻辑）
 * <b>省略</b>——仅保留 setting 字段 + 下发 {@code stackingShulkers/stackingShulkersMax} 元数据
 * 告知客户端（Tweakeroo 等客户端侧自行处理）。{@code getEmptyShulkersMaxCount} 保留只读语义。
 */
public class TweaksDataProvider extends DataProviderBase
{
    public static final TweaksDataProvider INSTANCE = new TweaksDataProvider();
    protected static final ServuxTweaksHandler HANDLER = ServuxTweaksHandler.getInstance();

    protected final CompoundTag metadata = new CompoundTag();
    private final BoolCallbacks boolCallback = new BoolCallbacks();
    private final IntCallbacks intCallback = new IntCallbacks();

    private final ServuxIntSetting permissionLevel = new ServuxIntSetting(this, "permission_level", 0, 4, 0, this.intCallback);
    private final ServuxIntSetting updateInterval = new ServuxIntSetting(this, "update_interval", 120, 1200, 40, this.intCallback);
    private final ServuxBoolSetting stackableShulkers = new ServuxBoolSetting(this, "stackable_shulkers", false, this.boolCallback);
    private final ServuxIntSetting stackableShulkersSize = new ServuxIntSetting(this, "stackable_shulkers_count", 64, 99, 1, this.intCallback);
    private final ServuxBoolSetting stackableShulkersFix = new ServuxBoolSetting(this, "stackable_shulkers_fix", true, this.boolCallback);
    private final List<IServuxSetting<?>> settings = List.of(
            this.permissionLevel,
            this.updateInterval,
            this.stackableShulkers,
            this.stackableShulkersSize,
            this.stackableShulkersFix
    );

    private final List<UUID> invalidPlayers = new ArrayList<>();
    private boolean configDirty = false;

    protected TweaksDataProvider()
    {
        super("tweaks_data",
                ServuxTweaksHandler.CHANNEL_ID,
                ServuxTweaksPacket.PROTOCOL_VERSION,
                0, ServuxReference.MOD_ID + ".provider.tweaks_data",
                "Tweaks Data provider for Client Side mods.");

        this.metadata.putString("name", this.getName());
        this.metadata.putString("id", this.getNetworkChannel().toString());
        this.metadata.putInt("version", this.getProtocolVersion());
        this.metadata.putString("servux", ServuxReference.MOD_STRING);

        this.setTickRate(40);
        this.checkTweaksMetadata();
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

    @Override public boolean shouldTick() { return this.isEnabled(); }

    @Override
    public void tick(MinecraftServer server, int tickCounter)
    {
        if (!this.isEnabled()) { return; }

        if ((tickCounter % this.updateInterval.getValue()) == 0)
        {
            if (this.configDirty)
            {
                this.updateAllTweaks(server);
                this.configDirty = false;
            }
        }
    }

    @Override public IPluginServerPlayHandler getPacketHandler() { return HANDLER; }

    @Override public boolean isPlayerRegistered(ServerPlayer player) { return !this.isPlayerInvalid(player); }

    private void checkTweaksMetadata()
    {
        // Only send the config when the Tweak is enabled;
        // (ie; don't turn it off in case they are using Carpet)
        if (this.shouldEmptyShulkersStack())
        {
            this.metadata.putBoolean("stackingShulkers", this.shouldEmptyShulkersStack());
            this.metadata.putInt("stackingShulkersMax", this.stackableShulkersSize.getValue());
        }
        else
        {
            if (this.metadata.contains("stackingShulkers")) { this.metadata.remove("stackingShulkers"); }
            if (this.metadata.contains("stackingShulkersMax")) { this.metadata.remove("stackingShulkersMax"); }
        }
    }

    public void updateAllTweaks(MinecraftServer server)
    {
        ServuxLog.debug("tweaksData: Invoke updateAllTweaks()");
        List<ServerPlayer> players = server.getPlayerList().getPlayers();

        this.checkTweaksMetadata();

        for (ServerPlayer player : players)
        {
            if (this.isPlayerRegistered(player)) { this.sendMetadata(player); }
        }
    }

    public void sendMetadata(ServerPlayer player)
    {
        if (!this.isEnabled()) { return; }
        if (!this.hasPermission(player))
        {
            ServuxLog.debug("tweaks_service: 拒绝 " + player.getName().getString() + "（权限不足）");
            return;
        }

        ServuxLog.debug("tweaksDataChannel: sendMetadata → " + player.getName().getString());
        this.checkTweaksMetadata();

        HANDLER.sendPlayPayload(player, ServuxTweaksPacket.MetadataResponse(this.metadata));
    }

    public void onPacketFailure(ServerPlayer player) { this.setPlayerInvalid(player); }

    public void removePlayer(ServerPlayer player) { this.removeInvalidPlayer(player); }

    private void setPlayerInvalid(ServerPlayer player) { if (!this.invalidPlayers.contains(player.getUUID())) { this.invalidPlayers.add(player.getUUID()); } }
    private boolean isPlayerInvalid(ServerPlayer player) { return this.invalidPlayers.contains(player.getUUID()); }
    private void removeInvalidPlayer(ServerPlayer player) { this.invalidPlayers.remove(player.getUUID()); }

    public void onBlockEntityRequest(ServerPlayer player, BlockPos pos)
    {
        if (!this.hasPermission(player) || !this.isEnabled()) { return; }

        ServerLevel level = (ServerLevel) player.level();
        BlockEntity be = level.getBlockEntity(pos);
        CompoundTag nbt = be != null ? be.saveWithFullMetadata(player.registryAccess()) : new CompoundTag();
        HANDLER.encodeServerData(player, ServuxTweaksPacket.SimpleBlockResponse(pos, nbt));
    }

    public void onEntityRequest(ServerPlayer player, int entityId)
    {
        if (!this.hasPermission(player)) { return; }

        ServerLevel level = (ServerLevel) player.level();
        Entity entity = level.getEntity(entityId);
        if (entity == null) { return; }

        try
        {
            NbtView view = NbtView.getWriter(level.registryAccess());
            Identifier id = EntityType.getKey(entity.getType());

            entity.saveWithoutId(view.getWriter());
            CompoundTag nbt = view.readNbt();

            if (nbt != null)
            {
                if (entity.getType() == EntityType.PLAYER)
                {
                    if (!EntitiesDataProvider.INSTANCE.hasPlayerInventoryPermission(player))
                    {
                        nbt.remove("Inventory");
                        nbt.put("Inventory", new ListTag());
                    }
                    if (!EntitiesDataProvider.INSTANCE.hasPlayerEnderItemsPermission(player))
                    {
                        nbt.remove("EnderItems");
                        nbt.put("EnderItems", new ListTag());
                    }
                }

                if (id != null) { nbt.putString("id", id.toString()); }
                HANDLER.encodeServerData(player, ServuxTweaksPacket.SimpleEntityResponse(entityId, nbt.copy()));
            }
        }
        catch (Exception e)
        {
            ServuxLog.debug("onEntityRequest 失败 entityId=" + entityId + ": " + e.getMessage());
        }
    }

    public boolean shouldEmptyShulkersStack() { return this.stackableShulkers.getValue(); }

    public boolean isStackableShulkersFixActive()
    {
        return this.shouldEmptyShulkersStack() && this.stackableShulkersFix.getValue();
    }

    public int defaultEmptyShulkersMaxCount()
    {
        if (this.shouldEmptyShulkersStack()) { return this.stackableShulkersSize.getValue(); }
        return 1;
    }

    /**
     * 潜影盒最大堆叠数查询（降级：服务端不真改堆叠行为，仅返回 setting 值或物品默认值）。
     */
    public int getEmptyShulkersMaxCount(ItemStack stack)
    {
        if (this.shouldEmptyShulkersStack() && stack != null && stack.is(net.minecraft.tags.ItemTags.SHULKER_BOXES))
        {
            return this.defaultEmptyShulkersMaxCount();
        }
        return stack != null ? stack.getMaxStackSize() : 1;
    }

    @Override public boolean hasPermission(ServerPlayer player) { return Perms.check(player, this.permNode, this.permissionLevel.getValue()); }

    @Override public void onPlayerJoin(ServerPlayer player) { this.sendMetadata(player); }
    @Override public void onPlayerQuit(ServerPlayer player) { this.removePlayer(player); }

    @Override public void onTickEndPre() { /* NO-OP */ }
    @Override public void onTickEndPost() { /* NO-OP */ }

    // Callbacks marks the config as dirty so that we can broadcast the config changes
    public static class BoolCallbacks implements IServuxSettingCallback<Boolean>
    {
        @Override
        public void onValueChanged(IServuxSetting<Boolean> setting, Boolean oldValue, Boolean value)
        {
            ServuxLog.debug("Config Change detected; " + setting.dataProvider().getName() + ":" + setting.name());
            TweaksDataProvider.INSTANCE.configDirty = true;
        }
    }

    public static class IntCallbacks implements IServuxSettingCallback<Integer>
    {
        @Override
        public void onValueChanged(IServuxSetting<Integer> setting, Integer oldValue, Integer value)
        {
            ServuxLog.debug("Config Change detected; " + setting.dataProvider().getName() + ":" + setting.name());
            TweaksDataProvider.INSTANCE.configDirty = true;
        }
    }
}
