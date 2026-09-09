package verymc.top.veryMcProto.mod.servux.dataproviders;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.dataproviders.DataProviderBase;
import verymc.top.veryMcProto.mod.servux.ServuxDebug;
import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.framework.network.ServerPlayHandler;
import verymc.top.veryMcProto.framework.permission.Perms;
import verymc.top.veryMcProto.framework.settings.IServuxSetting;
import verymc.top.veryMcProto.framework.settings.IServuxSettingCallback;
import verymc.top.veryMcProto.framework.settings.ServuxIntSetting;
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
 * sendMetadata 走 plugin messaging（去 networkHandler 重载分支）；tick 去 ProfilerFiller 形参。
 *
 * <p><b>潜影盒堆叠——未实现（不可能实现）</b>：原版通过 Mixin 改 {@code ItemStack.getMaxStackSize()} /
 * {@code HopperBlockEntity} 的 NMS 方法全局返回行为，使空潜影盒可堆叠。Paper 无 Mixin 运行时，
 * 反射改不了方法行为、Bukkit 事件模拟在 {@code maxStackSize=1} 前提下不成立、设 MAX_STACK_SIZE 组件
 * 是 per-item 且污染序列化——三条路均不通。故本 provider <b>不保留</b>原版的 {@code stackable_shulkers}
 * 系列 setting 与 {@code stackingShulkers} 元数据下发（避免客户端误以为服务端开了堆叠而与服务端不一致）。
 * 详见 {@code docs/04-mixin-analysis.md} §1 / §4。
 */
public class TweaksDataProvider extends DataProviderBase
{
    public static final TweaksDataProvider INSTANCE = new TweaksDataProvider();
    protected static final ServuxTweaksHandler HANDLER = ServuxTweaksHandler.getInstance();

    protected final CompoundTag metadata = new CompoundTag();
    private final IntCallbacks intCallback = new IntCallbacks();

    private final ServuxIntSetting permissionLevel = new ServuxIntSetting(this, "permission_level", 0, 4, 0, this.intCallback);
    private final ServuxIntSetting updateInterval = new ServuxIntSetting(this, "update_interval", 120, 1200, 40, this.intCallback);
    private final List<IServuxSetting<?>> settings = List.of(
            this.permissionLevel,
            this.updateInterval
    );

    private final List<UUID> invalidPlayers = new ArrayList<>();
    /** 注册名册（上游 registeredPlayers）：C2S REGISTER 版本门禁 + 权限双门通过后入册。 */
    private final List<UUID> registeredPlayers = new ArrayList<>();
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

    @Override
    public boolean isPlayerRegistered(ServerPlayer player)
    {
        return this.registeredPlayers.contains(player.getUUID()) && !this.isPlayerInvalid(player);
    }

    public void updateAllTweaks(MinecraftServer server)
    {
        ServuxDebug.log(ServuxDebug.Cat.PROVIDER, "tweaksData: Invoke updateAllTweaks()");
        List<ServerPlayer> players = server.getPlayerList().getPlayers();

        for (ServerPlayer player : players)
        {
            if (this.isPlayerRegistered(player)) { this.sendMetadata(player); }
        }
    }

    /**
     * C2S 注册入口（type 2 METADATA_REQUEST）。上游 TweaksDataProvider.register（:183-209）字面移植：
     * isEnabled → 版本门禁（deny 四件套）→ 权限（不入册）→ 入册 → sendMetadata。
     * 上游在入册前另有 checkTweaksMetadata()（:206，仅维护潜影盒堆叠元数据）——按 AGENTS.md §3
     * 潜影盒禁令不移植（既有偏差，见类 javadoc）；上游 Tweaks register 也不调 removeInvalidPlayer，从上游。
     */
    @Override
    public void register(ServerPlayer player, CompoundTag tags)
    {
        if (!this.isEnabled()) { return; }

        if (DataProviderBase.isVersionTooLow(tags, this.getProtocolVersion()))
        {
            Reference.logger().warning("tweaks_data: Denying access for player " + player.getName().getString()
                    + ", Insufficient Protocol Version; This Server Requires: Version " + this.getProtocolVersion());
            player.sendSystemMessage(Component.literal(ServuxReference.MSG_PROTOCOL_VERSION_TOO_LOW.formatted(this.getName())));
            HANDLER.tickFailures(player);
            return;
        }

        if (!this.hasPermission(player))
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "tweaks_data: Denying access for player "
                    + player.getName().getString() + ", Insufficient Permissions");
            return;
        }

        this.registeredPlayers.add(player.getUUID());
        this.sendMetadata(player);
    }

    /** C2S 注销（UNREGISTER_REPLY）：resetFailures + 出注册名册（上游 :238-244 字面，不清 invalid）。 */
    @Override
    public void unregister(ServerPlayer player)
    {
        if (this.registeredPlayers.contains(player.getUUID()))
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "tweaks_data: Unregistered player " + player.getName().getString());
        }

        HANDLER.resetFailures(this.getNetworkChannel(), player);
        this.registeredPlayers.remove(player.getUUID());
    }

    public void sendMetadata(ServerPlayer player)
    {
        if (!this.isEnabled())
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "tweaks sendMetadata 跳过: provider disabled");
            return;
        }
        if (!this.hasPermission(player))
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "tweaks sendMetadata 拒绝 " + player.getName().getString() + " (权限不足)");
            return;
        }

        boolean ok = HANDLER.sendPlayPayload(player, ServuxTweaksPacket.MetadataResponse(this.metadata));
        ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "tweaks sendMetadata → " + player.getName().getString()
                + " ok=" + ok + " servux=" + this.metadata.getStringOr("servux", "?")
                + " ver=" + this.metadata.getIntOr("version", -1)
                + " keys=" + this.metadata.keySet());
    }

    public void onPacketFailure(ServerPlayer player)
    {
        this.setPlayerInvalid(player);
        this.registeredPlayers.remove(player.getUUID());
    }

    /** 玩家退出（quit）全清理：invalid + 注册名册 + resetFailures（上游 removePlayer 字面）。 */
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
        if (!this.isPlayerRegistered(player) || !this.hasPermission(player) || !this.isEnabled()) { return; }

        ServerLevel level = (ServerLevel) player.level();
        BlockEntity be = level.getBlockEntity(pos);
        CompoundTag nbt = be != null ? be.saveWithFullMetadata(player.registryAccess()) : new CompoundTag();
        HANDLER.encodeServerData(player, ServuxTweaksPacket.SimpleBlockResponse(pos, nbt));
    }

    public void onEntityRequest(ServerPlayer player, int entityId)
    {
        if (!this.isPlayerRegistered(player) || !this.hasPermission(player)) { return; }

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
            ServuxDebug.log(ServuxDebug.Cat.PACKET, "onEntityRequest 失败 entityId=" + entityId + ": " + e.getMessage());
        }
    }

    @Override public boolean hasPermission(ServerPlayer player) { return Perms.check(player, this.permNode, this.permissionLevel.getValue()); }

    /** 白名单：仅已注册玩家推送（旧客户端被版本门禁拒后永不入册 → 永不收 metadata）。 */
    @Override public void onPlayerJoin(ServerPlayer player)
    {
        if (this.isPlayerRegistered(player)) { this.sendMetadata(player); }
    }

    @Override
    public void onPlayerRegisterChannel(ServerPlayer player, String channel)
    {
        // ★ 修复 tweaks sync not_enabled：onPlayerJoin 时通道未声明，sendMetadata 丢弃；
        // 客户端声明 servux:tweaks（= 装了 Tweakeroo 等）时立即重发。sendMetadata 幂等。
        // 白名单：声明通常先于客户端首个 REGISTER 到达，此时重发被挡——metadata 首达由 REGISTER 应答链保证。
        if (this.getNetworkChannel().toString().equals(channel) && this.isPlayerRegistered(player))
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "tweaks onPlayerRegisterChannel: 客户端声明 " + channel + " → 重发 metadata");
            this.sendMetadata(player);
        }
    }

    @Override public void onPlayerQuit(ServerPlayer player) { this.removePlayer(player); }

    @Override public void onTickEndPre() { /* NO-OP */ }
    @Override public void onTickEndPost() { /* NO-OP */ }

    // Callbacks marks the config as dirty so that we can broadcast the config changes
    public static class IntCallbacks implements IServuxSettingCallback<Integer>
    {
        @Override
        public void onValueChanged(IServuxSetting<Integer> setting, Integer oldValue, Integer value)
        {
            ServuxDebug.log(ServuxDebug.Cat.CONFIG, "Config Change detected; " + setting.dataProvider().getName() + ":" + setting.name());
            TweaksDataProvider.INSTANCE.configDirty = true;
        }
    }
}
