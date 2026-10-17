package verymc.top.veryMcProto.mod.carpet.patches;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.authlib.GameProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.OldUsersConverter;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;

import verymc.top.veryMcProto.mod.carpet.CarpetSettings;
import verymc.top.veryMcProto.mod.carpet.helpers.ActionPackManager;

/**
 * 假人实体（克隆上游 {@code carpet/patches/EntityPlayerMPFake.java} 26.2 树 286 行同构移植；
 * 上游零 Mixin 依赖的纯 ServerPlayer 子类——B 侧同类）。
 *
 * <p>spawn 链：{@link #createFake} 解析 UUID / 异步拉取 profile → 原版
 * {@code PlayerList.placeNewPlayer(FakeClientConnection, ...)} 走完整进服管线（读 playerdata、
 * 入名册/世界、占 tab 栏、计入睡比例、可被 /tp /ban 原版命令识别）→ 返回后 new
 * {@link NetHandlerPlayServerFake} 替换监听器（其构造器自动 {@code player.connection = this}）。
 *
 * <p>与上游的偏差（docs/carpet-architecture.md 偏差清单）：
 * <ul>
 *   <li>cookie 适配：Paper 26.2 的 {@link CommonListenerCookie} 为 7 组件 record（上游 vanilla 4 参）——
 *       createFake 用 {@code createInitial(profile, false)}（cli 恰为 createDefault），createShadow 用
 *       7 参 canonical（cli 沿用原玩家自定义）。</li>
 *   <li>shadow 名册守卫：Paper 的重复登录踢线可被第三方插件取消（PlayerKickEvent）——placeNewPlayer 前
 *       断言目标已从名册移除，否则中止（上游 Fabric 无事件面，不存在此竞态）。</li>
 *   <li>{@code fixStartingPosition} 字段删除：其唯一消费点 = 上游 {@code PlayerList_fakePlayersMixin} ①
 *       在 placeNewPlayer 内部注入运行——B 侧无注入面，定位由 spawn 后的 {@code teleportTo} 承载
 *       （上游 :106 同款；差异 = placeNewPlayer 过程中假人短暂处于读档/出生点位置，调参级）。</li>
 *   <li>{@code respawnFake} 工厂删除：其唯一消费点 = 上游 Mixin ② respawn 内 {@code new ServerPlayer}
 *       重定向——Paper respawn 为实例复用（零 new），假人子类身份天然保留。</li>
 *   <li>{@code Messenger.s} → {@link Component#literal}；上游 {@code ServerPlayerInterface.getActionPack()}
 *       → {@link ActionPackManager#get}（侧表取包，禁 Mixin 的等价物）。</li>
 * </ul>
 */
@SuppressWarnings("EntityConstructor")
public class EntityPlayerMPFake extends ServerPlayer
{
    private static final Logger LOG = LoggerFactory.getLogger(EntityPlayerMPFake.class);

    private static final Set<String> spawning = new HashSet<>();

    public boolean isAShadow;

    // Returns true if it was successful, false if couldn't spawn due to the player not existing in Mojang servers
    public static boolean createFake(String username, MinecraftServer server, Vec3 pos, double yaw, double pitch, ResourceKey<Level> dimensionId, GameType gamemode, boolean flying)
    {
        //prolly half of that crap is not necessary, but it works
        ServerLevel worldIn = server.getLevel(dimensionId);
        server.services().nameToIdCache().resolveOfflineUsers(false);
        GameProfile gameprofile;

        UUID uuid = OldUsersConverter.convertMobOwnerIfNecessary(server, username);
        if (uuid == null && CarpetSettings.allowSpawningOfflinePlayers) {
            server.services().nameToIdCache().resolveOfflineUsers(server.isDedicatedServer() && server.usesAuthentication());
            uuid = UUIDUtil.createOfflinePlayerUUID(username);
        }
        if (uuid == null) {
            return false; // no uuid, no player
        }
        gameprofile = new GameProfile(uuid, username);

        // We need to mark this player as spawning so that we do not
        // try to spawn another player with the name while the profile
        // is being fetched - preventing multiple players spawning
        String name = gameprofile.name();
        spawning.add(name);

        fetchGameProfile(server, gameprofile.id()).whenCompleteAsync((p, t) -> {
            // Always remove the name, even if exception occurs
            spawning.remove(name);
            if (t != null)
            {
                return;
            }

            GameProfile current;
            if (p.name().isEmpty()) {
                current = gameprofile;
            }
            else {
                current = p;
            }

            EntityPlayerMPFake instance = new EntityPlayerMPFake(server, worldIn, current, ClientInformation.createDefault(), false);
            // Paper 26.2 cookie 为 7 组件 record——cli=createDefault 与 createInitial 工厂默认逐字段一致
            FakeClientConnection fakeConnection = new FakeClientConnection(PacketFlow.SERVERBOUND);
            server.getPlayerList().placeNewPlayer(fakeConnection, instance, CommonListenerCookie.createInitial(current, false));
            loadPlayerData(instance);
            instance.stopRiding(); // otherwise the created fake player will be on the vehicle
            instance.teleportTo(worldIn, pos.x, pos.y, pos.z, Set.of(), (float) yaw, (float) pitch, true);
            instance.setHealth(20.0F);
            instance.unsetRemoved();
            instance.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.6F);
            instance.gameMode.changeGameModeForPlayer(gamemode);
            server.getPlayerList().broadcastAll(new ClientboundRotateHeadPacket(instance, (byte) (instance.yHeadRot * 256 / 360)), dimensionId);
            server.getPlayerList().broadcastAll(ClientboundEntityPositionSyncPacket.of(instance), dimensionId);
            instance.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f); // show all model layers (incl. capes)
            instance.getAbilities().flying = flying;

            // placeNewPlayer 内部以真监听器收尾——替换为 fake 监听器（ctor 自动 player.connection = this；
            // connection 字段 public 非 final）。被弃的真实例无全局注册面（textFilter 恒 DUMMY，双 join 无害）
            new NetHandlerPlayServerFake(server, fakeConnection, instance, CommonListenerCookie.createInitial(current, false));
        }, server);
        return true;
    }

    private static CompletableFuture<GameProfile> fetchGameProfile(MinecraftServer server, final UUID name) {
        final ResolvableProfile resolvableProfile = ResolvableProfile.createUnresolved(name);
        return resolvableProfile.resolveProfile(server.services().profileResolver());
    }

    private static void loadPlayerData(EntityPlayerMPFake player)
    {
        try (ProblemReporter.ScopedCollector scopedCollector = new ProblemReporter.ScopedCollector(player.problemPath(), LOG))
        {
            Optional<ValueInput> optional = player.level().getServer().getPlayerList().loadPlayerData(player.nameAndId()).map((compoundTag) -> TagValueInput.create(scopedCollector, player.registryAccess(), compoundTag));
            optional.ifPresent( valueInput -> {
                player.load(valueInput);
                player.loadAndSpawnEnderPearls(valueInput);
                player.loadAndSpawnParentVehicle(valueInput);
            });
        }
    }

    public static EntityPlayerMPFake createShadow(MinecraftServer server, ServerPlayer player)
    {
        player.connection.disconnect(Component.translatable("multiplayer.disconnect.duplicate_login"));
        // Paper 特有守卫：重复登录踢线可被第三方插件取消（PlayerKickEvent）——目标未离场时中止，
        // 防止同 UUID 双实体（上游 Fabric 无事件面，不可达此竞态）
        if (server.getPlayerList().getPlayer(player.getUUID()) != null)
        {
            return null;
        }
        ServerLevel worldIn = player.level();
        GameProfile gameprofile = player.getGameProfile();
        EntityPlayerMPFake playerShadow = new EntityPlayerMPFake(server, worldIn, gameprofile, player.clientInformation(), true);
        playerShadow.setChatSession(player.getChatSession());
        // Paper 26.2 cookie 为 7 组件 record——cli 沿用原玩家自定义，其余字段与 createInitial 默认对齐
        FakeClientConnection shadowConnection = new FakeClientConnection(PacketFlow.SERVERBOUND);
        server.getPlayerList().placeNewPlayer(shadowConnection, playerShadow,
                new CommonListenerCookie(gameprofile, 0, player.clientInformation(), true, null, new HashSet<>(), new io.papermc.paper.util.KeepAlive()));
        new NetHandlerPlayServerFake(server, shadowConnection, playerShadow,
                new CommonListenerCookie(gameprofile, 0, player.clientInformation(), true, null, new HashSet<>(), new io.papermc.paper.util.KeepAlive()));
        loadPlayerData(playerShadow);

        playerShadow.setHealth(player.getHealth());
        playerShadow.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        playerShadow.gameMode.changeGameModeForPlayer(player.gameMode.getGameModeForPlayer());
        ActionPackManager.get(playerShadow).copyFrom(ActionPackManager.get(player));
        // this might create problems if a player logs back in...
        playerShadow.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.6F);
        playerShadow.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, player.getEntityData().get(DATA_PLAYER_MODE_CUSTOMISATION));

        server.getPlayerList().broadcastAll(new ClientboundRotateHeadPacket(playerShadow, (byte) (player.yHeadRot * 256 / 360)), playerShadow.level().dimension());
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, playerShadow), playerShadow.level().dimension());
        playerShadow.getAbilities().flying = player.getAbilities().flying;
        return playerShadow;
    }

    public static boolean isSpawningPlayer(String username)
    {
        return spawning.contains(username);
    }

    private EntityPlayerMPFake(MinecraftServer server, ServerLevel worldIn, GameProfile profile, ClientInformation cli, boolean shadow)
    {
        super(server, worldIn, profile, cli);
        this.isAShadow = shadow;
    }

    @Override
    public void onEquipItem(final EquipmentSlot slot, final ItemStack previous, final ItemStack stack)
    {
        if (!isUsingItem()) super.onEquipItem(slot, previous, stack);
    }

    @Override
    public void kill(ServerLevel level)
    {
        kill(Component.literal("Killed"));
    }

    public void kill(Component reason)
    {
        shakeOff();

        // this.connection 静态类型 = ServerGamePacketListenerImpl（ServerPlayer.connection 字段声明），
        // onDisconnect = 完整退出链（PlayerQuitEvent + removePlayerFromWorld + 存档），不经 kick 终端
        if (reason.getContents() instanceof TranslatableContents text && text.getKey().equals("multiplayer.disconnect.duplicate_login")) {
            this.connection.onDisconnect(new DisconnectionDetails(reason));
        } else {
            this.level().getServer().schedule(new TickTask(this.level().getServer().getTickCount(), () -> {
                this.connection.onDisconnect(new DisconnectionDetails(reason));
            }));
        }
    }

    @Override
    public void tick()
    {
        if (this.level().getServer().getTickCount() % 10 == 0)
        {
            this.connection.resetPosition();
            this.level().getChunkSource().move(this);
        }
        try
        {
            super.tick();
            this.doTick();
        }
        catch (NullPointerException e)
        {
            // happens with that paper port thingy - not sure what that would fix, but hey
            // the game not gonna crash violently.（上游自证的 Paper 移植防御，逐字保留；排错注意此吞点）
            if (!npeLogged)
            {
                npeLogged = true;
                LOG.warn("[Carpet] fake player tick NPE (upstream-known Paper port interaction), first stack trace:", e);
            }
        }
    }

    private boolean npeLogged;

    @Override
    public boolean startRiding(Entity entityToRide, boolean force, boolean sendEventAndTriggers) {
        if (super.startRiding(entityToRide, force, sendEventAndTriggers)) {
            // from ClientPacketListener.handleSetEntityPassengersPacket
            if (entityToRide instanceof AbstractBoat) {
                this.yRotO = entityToRide.getYRot();
                this.setYRot(entityToRide.getYRot());
                this.setYHeadRot(entityToRide.getYRot());
            }
            return true;
        } else {
            return false;
        }
    }

    private void shakeOff()
    {
        if (getVehicle() instanceof Player) stopRiding();
        for (Entity passenger : getIndirectPassengers())
        {
            if (passenger instanceof Player) passenger.stopRiding();
        }
    }

    @Override
    public void die(DamageSource cause)
    {
        shakeOff();
        super.die(cause);
        setHealth(20);
        this.foodData = new FoodData();
        kill(this.getCombatTracker().getDeathMessage());
    }

    @Override
    public String getIpAddress()
    {
        return "127.0.0.1";
    }

    @Override
    public boolean allowsListing() {
        return CarpetSettings.allowListingFakePlayers;
    }

    @Override
    public ServerPlayer teleport(TeleportTransition serverLevel)
    {
        super.teleport(serverLevel);
        if (wonGame) {
            ServerboundClientCommandPacket p = new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN);
            connection.handleClientCommand(p);
        }

        // If above branch was taken, *this* has been removed and replaced, the new instance has been set
        // on 'our' connection (which is now theirs, but we still have a ref).
        // （Paper respawn = 实例复用，替换物即自身——上游 Mixin ② 的 respawnFake 重定向在 B 侧无对应现实）
        if (connection.player.isChangingDimension()) {
            connection.player.hasChangedDimension();
        }
        return connection.player;
    }
}
