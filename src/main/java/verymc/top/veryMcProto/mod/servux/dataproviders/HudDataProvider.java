package verymc.top.veryMcProto.mod.servux.dataproviders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

import org.bukkit.scheduler.BukkitRunnable;

import com.mojang.serialization.DataResult;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.WeatherData;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.dataproviders.DataProviderBase;
import verymc.top.veryMcProto.mod.servux.ServuxDebug;
import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.framework.network.ServerPlayHandler;
import verymc.top.veryMcProto.framework.nms.Nms;
import verymc.top.veryMcProto.framework.permission.Perms;
import verymc.top.veryMcProto.framework.settings.IServuxSetting;
import verymc.top.veryMcProto.framework.settings.IServuxSettingCallback;
import verymc.top.veryMcProto.framework.settings.ServuxBoolSetting;
import verymc.top.veryMcProto.framework.settings.ServuxIntSetting;
import verymc.top.veryMcProto.framework.settings.ServuxStringListSetting;
import verymc.top.veryMcProto.mod.servux.ServuxReference;
import verymc.top.veryMcProto.mod.servux.loggers.DataLogger;
import verymc.top.veryMcProto.mod.servux.loggers.DataLoggerBase;
import verymc.top.veryMcProto.mod.servux.network.ServuxHudHandler;
import verymc.top.veryMcProto.mod.servux.network.ServuxHudPacket;
import verymc.top.veryMcProto.mod.servux.util.nbt.RecipeNbtNormalizer;

/**
 * HUD Provider（mod 层，配 MiniHUD）。移植自原版 {@code HudDataProvider}（通道 servux:hud_metadata，协议版本 2）。
 *
 * <p><b>适配点</b>：
 * <ul>
 *   <li>{@code Permissions.check} → {@link Perms#check}；{@code Reference} → {@link ServuxReference}；</li>
 *   <li>{@code registerHandler}：{@code registerPlayPayload/registerPlayReceiver} 去掉（Paper plugin messaging 注册即收发），
 *       改 {@link ServerPlayHandler#registerServerPlayHandler}（内含通道注册）；</li>
 *   <li>{@code sendMetadata}：去 NMS networkHandler 重载，统一走 plugin messaging（{@code HANDLER.sendPlayPayload}）；</li>
 *   <li>{@code tick}：去 ProfilerFiller 形参与 push/pop；</li>
 *   <li>{@code onPlayerJoin}：plugin messaging 握手需时间，延迟 sendMetadata（原版走 Mixin placeNewPlayer 直推）；</li>
 *   <li>天气采集：原版 Mixin advanceWeatherCycle 触发 tickWeather，Paper 改在 tick 内周期读 {@link ServerLevel} 天气计时。</li>
 * </ul>
 */
public class HudDataProvider extends DataProviderBase
{
    public static final HudDataProvider INSTANCE = new HudDataProvider();
    protected static final ServuxHudHandler HANDLER = ServuxHudHandler.getInstance();

    protected final CompoundTag metadata = new CompoundTag();

    private final ServuxIntSetting permissionLevel = new ServuxIntSetting(this, "permission_level", 0, 4, 0);
    private final ServuxIntSetting updateInterval = new ServuxIntSetting(this, "update_interval", 40, 300, 20);
    private final ServuxBoolSetting shareWeatherStatus = new ServuxBoolSetting(this, "share_weather_status", false);
    private final ServuxIntSetting weatherPermissionLevel = new ServuxIntSetting(this, "weather_permission_level", 0, 4, 0);
    private final ServuxBoolSetting shareSeed = new ServuxBoolSetting(this, "share_seed", false);
    private final ServuxIntSetting seedPermissionLevel = new ServuxIntSetting(this, "seed_permission_level", 2, 4, 0);
    private final ServuxBoolSetting loggersEnabled = new ServuxBoolSetting(this, "loggers_enabled", false, new BoolCallback());
    private final ServuxStringListSetting loggersEnableList = new ServuxStringListSetting(this, "loggers_enable_list", this.getDefaultLoggers(), new StringListCallback());
    private final ServuxIntSetting loggerPermissionLevel = new ServuxIntSetting(this, "logger_permission_level", 0, 4, 0);
    private final List<IServuxSetting<?>> settings = List.of(
            this.permissionLevel, this.updateInterval,
            this.shareWeatherStatus, this.weatherPermissionLevel,
            this.shareSeed, this.seedPermissionLevel,
            this.loggersEnabled, this.loggersEnableList, this.loggerPermissionLevel
    );

    private GlobalPos spawnPos = new GlobalPos(Level.OVERWORLD, BlockPos.ZERO);
    private long worldSeed = 0;
    private int clearWeatherTime = -1;
    private int rainWeatherTime = -1;
    private int thunderWeatherTime = -1;
    private boolean isRaining;
    private boolean isThundering;
    private long lastTick;
    private long lastWeatherTick;
    private boolean refreshSpawnMetadata;
    private boolean refreshWeatherData;
    private final List<UUID> invalidPlayers = new ArrayList<>();
    /** 注册名册（上游 registeredPlayers，HudDataProvider:68）：C2S REGISTER 版本门禁 + 权限双门通过后入册；deny/未注册玩家的一切刷新与请求入口均被 {@link #isPlayerRegistered} 拦截。 */
    private final List<UUID> registeredPlayers = new ArrayList<>();

    private final HashMap<UUID, List<DataLogger>> loggerPlayers = new HashMap<>();
    private final HashMap<DataLogger, DataLoggerBase<?>> LOGGERS = new HashMap<>();
    private final HashMap<DataLogger, Tag> DATA = new HashMap<>();

    protected HudDataProvider()
    {
        super("hud_data",
                ServuxHudHandler.CHANNEL_ID,
                ServuxHudPacket.PROTOCOL_VERSION,
                0, ServuxReference.MOD_ID + ".provider.hud_data",
                "MiniHUD Meta Data provider for various Server-Side information");

        this.metadata.putString("name", this.getName());
        this.metadata.putString("id", this.getNetworkChannel().toString());
        this.metadata.putInt("version", this.getProtocolVersion());
        this.metadata.putString("servux", ServuxReference.MOD_STRING);

        this.metadata.putString("spawnDimension", this.getSpawnPos().dimension().identifier().toString());
        this.metadata.putInt("spawnPosX", this.getSpawnPos().pos().getX());
        this.metadata.putInt("spawnPosY", this.getSpawnPos().pos().getY());
        this.metadata.putInt("spawnPosZ", this.getSpawnPos().pos().getZ());

        this.checkIfLoggersAreInitialized();
    }

    private List<String> getDefaultLoggers()
    {
        List<String> list = new ArrayList<>();
        for (DataLogger type : DataLogger.VALUES)
        {
            list.add(type.getSerializedName());
        }
        return list;
    }

    private void resetLoggersFromConfig()
    {
        if (this.isLoggersEnabled())
        {
            this.loggersEnabled.setValueNoCallback(false);
            this.checkIfLoggersAreInitialized();
            this.loggersEnabled.setValueNoCallback(true);
        }
        else
        {
            this.checkIfLoggersAreInitialized();
        }
    }

    @Override
    public List<IServuxSetting<?>> getSettings() { return this.settings; }

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

    @Override
    public boolean isPlayerRegistered(ServerPlayer player)
    {
        return this.registeredPlayers.contains(player.getUUID()) && !this.isPlayerInvalid(player);
    }

    @Override
    public boolean shouldTick() { return this.enabled; }

    @Override
    public void tick(MinecraftServer server, int tickCounter)
    {
        if (!this.isEnabled()) { return; }

        List<ServerPlayer> playerList = server.getPlayerList().getPlayers();

        if ((tickCounter % this.updateInterval.getValue()) == 0)
        {
            this.lastTick = tickCounter;

            if (this.worldSeed == 0)
            {
                this.checkWorldSeed(server);
            }
            else if (this.shareSeed.getValue() == false)
            {
                this.setWorldSeed(0);
            }

            // 周期采集天气（替代原版 Mixin advanceWeatherCycle）
            this.pollWeather(server);

            for (ServerPlayer player : playerList)
            {
                if (this.isPlayerInvalid(player)) { continue; }

                // 上游 tick 传空 CompoundData（非 null）以通过 refresh 入口的 tags==null 门禁（上游 :206-209）
                if (this.shouldRefreshWeatherData()) { this.refreshWeatherData(player, new CompoundTag()); }
                if (this.shouldRefreshSpawnMetadata()) { this.refreshSpawnMetadata(player, new CompoundTag()); }
            }

            if (this.shouldRefreshWeatherData())
            {
                this.lastWeatherTick = tickCounter;
                this.setRefreshWeatherDataComplete();
            }
            if (this.shouldRefreshSpawnMetadata()) { this.setRefreshSpawnMetadataComplete(); }
        }

        this.checkIfLoggersAreInitialized();

        if (this.isLoggersEnabled())
        {
            this.tickLoggers(server);
            for (ServerPlayer player : playerList)
            {
                this.tickLoggerPlayer(player);
            }
        }
    }

    /** 周期读取主世界天气计时，缓存并触发天气推送（替代原版 Mixin advanceWeatherCycle → tickWeather）。 */
    private void pollWeather(MinecraftServer server)
    {
        ServerLevel overworld = server.overworld();
        if (overworld == null) { return; }
        try
        {
            // 26.1：天气状态自 ServerLevelData 迁入 ServerLevel.getWeatherData()（WeatherData，SavedData）
            WeatherData weather = overworld.getWeatherData();
            this.tickWeather(
                    weather.getClearWeatherTime(),
                    weather.getRainTime(),
                    weather.getThunderTime(),
                    weather.isRaining(),
                    weather.isThundering());
        }
        catch (Exception e) { ServuxDebug.log(ServuxDebug.Cat.TICK, "pollWeather 天气采集失败（NMS 字段漂移？）: " + e.getMessage()); }
    }

    private void setPlayerInvalid(ServerPlayer player)
    {
        if (!this.invalidPlayers.contains(player.getUUID())) { this.invalidPlayers.add(player.getUUID()); }
    }

    private boolean isPlayerInvalid(ServerPlayer player) { return this.invalidPlayers.contains(player.getUUID()); }

    private void removeInvalidPlayer(ServerPlayer player) { this.invalidPlayers.remove(player.getUUID()); }

    public void tickWeather(int clearTime, int rainTime, int thunderTime, boolean isRaining, boolean isThunder)
    {
        if (!this.isEnabled()) { return; }

        this.clearWeatherTime = clearTime;
        this.rainWeatherTime = rainTime;
        this.thunderWeatherTime = thunderTime;
        this.isRaining = isRaining;
        this.isThundering = isThunder;

        if ((this.lastTick - this.lastWeatherTick) > this.getTickInterval())
        {
            this.refreshWeatherData = true; // 节流：避免天气 tick 刷屏
        }
    }

    private void checkIfLoggersAreInitialized()
    {
        if (this.isLoggersEnabled())
        {
            if (!this.metadata.contains("Loggers")) { this.metadata.put("Loggers", this.putEnabledLoggers()); }
            this.setTickRate(15);
            if (this.LOGGERS.isEmpty()) { this.initializeLoggers(); }
        }
        else
        {
            if (this.metadata.contains("Loggers")) { this.metadata.remove("Loggers"); }
            if (!this.LOGGERS.isEmpty()) { this.LOGGERS.clear(); }
            this.setTickRate(40);
            if (!this.DATA.isEmpty()) { this.DATA.clear(); }
        }
    }

    private CompoundTag putEnabledLoggers()
    {
        CompoundTag nbt = new CompoundTag();
        this.validateLoggerListConfig();
        for (DataLogger type : DataLogger.VALUES)
        {
            nbt.putBoolean(type.getSerializedName(), this.isLoggerTypeEnabled(type));
        }
        return nbt;
    }

    private void validateLoggerListConfig()
    {
        List<String> list = this.loggersEnableList.getValue();
        List<String> safeList = new ArrayList<>();
        boolean dirty = false;

        for (String entry : list)
        {
            DataLogger type = DataLogger.fromStringStatic(entry);
            if (type == null) { dirty = true; }
            else { safeList.add(entry); }
        }

        if (dirty) { this.loggersEnableList.setValueNoCallback(safeList); }
    }

    private boolean isLoggerTypeEnabled(DataLogger type) { return this.loggersEnableList.getValue().contains(type.getSerializedName()); }

    private void initializeLoggers()
    {
        if (this.LOGGERS.isEmpty())
        {
            for (DataLogger type : DataLogger.VALUES)
            {
                if (this.isLoggerTypeEnabled(type))
                {
                    DataLoggerBase<?> entry = type.init();
                    if (entry != null) { this.LOGGERS.put(type, entry); }
                }
            }
        }
    }

    private void tickLoggers(MinecraftServer server)
    {
        this.DATA.clear();
        if (!this.isLoggersEnabled()) { return; }
        this.LOGGERS.forEach((type, logger) -> this.DATA.put(type, (Tag) (logger.getResult(server))));
    }

    private void tickLoggerPlayer(ServerPlayer player)
    {
        if (!this.isLoggersEnabled()) { return; }
        UUID uuid = player.getUUID();

        if (this.loggerPlayers.containsKey(uuid))
        {
            List<DataLogger> list = this.loggerPlayers.get(uuid);
            if (!list.isEmpty())
            {
                CompoundTag nbt = new CompoundTag();
                for (DataLogger type : list)
                {
                    if (this.DATA.containsKey(type)) { nbt.put(type.getSerializedName(), this.DATA.get(type)); }
                }
                HANDLER.encodeServerData(player, ServuxHudPacket.DataLoggerTick(nbt));
            }
        }
    }

    /**
     * C2S 注册入口（type 2 METADATA_REQUEST）。上游 HudDataProvider.register（:405-448）字面移植：
     * isEnabled → 版本门禁（deny 四件套：warn 日志 + 聊天提示 + tickFailures 检疫 + return 不入册）
     * → 权限（不足 debugLog + return 不入册）→ 入册 → sendMetadata。
     */
    @Override
    public void register(ServerPlayer player, @Nullable CompoundTag tags)
    {
        if (!this.isEnabled()) { return; }

        if (DataProviderBase.isVersionTooLow(tags, this.getProtocolVersion()))
        {
            Reference.logger().warning("hud_data: Denying access for player " + player.getName().getString()
                    + ", Insufficient Protocol Version; This Server Requires: Version " + this.getProtocolVersion());
            player.sendSystemMessage(Component.literal(ServuxReference.MSG_PROTOCOL_VERSION_TOO_LOW.formatted(this.getName())));
            HANDLER.tickFailures(player);
            return;
        }

        if (!this.hasPermission(player))
        {
            // No Permission
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "hud_data: Denying access for player "
                    + player.getName().getString() + ", Insufficient Permissions");
            return;
        }

        // 上游 register 内的 removeInvalidPlayer(:426) 由 sendMetadata 内(:366)调用天然覆盖（终态等价：出 invalid、入 registered）
        this.registeredPlayers.add(player.getUUID());
        this.sendMetadata(player);
    }

    /**
     * C2S 注销（UNREGISTER_REPLY）。上游 HudDataProvider.unregister（:451-464）字面移植：
     * resetFailures + 出注册名册 + 摘 loggerPlayers；<b>不清</b> invalid 名册（invalid 由
     * removePlayer[quit] / register 成功路径清理）。
     */
    @Override
    public void unregister(ServerPlayer player)
    {
        if (this.registeredPlayers.contains(player.getUUID()))
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "hud_data: Unregistered player " + player.getName().getString());
        }

        HANDLER.resetFailures(this.getNetworkChannel(), player);
        this.registeredPlayers.remove(player.getUUID());
        this.loggerPlayers.remove(player.getUUID());
    }

    public void sendMetadata(ServerPlayer player)
    {
        if (!this.isEnabled())
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "hud sendMetadata 跳过: provider disabled");
            return;
        }
        if (!this.hasPermission(player))
        {
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "hud sendMetadata 拒绝 " + player.getName().getString() + " (权限不足)");
            return;
        }

        this.removeInvalidPlayer(player);

        CompoundTag nbt = new CompoundTag();
        nbt.merge(this.metadata);

        if (!this.hasPermissionsForSeed(player) && nbt.contains("worldSeed")) { nbt.remove("worldSeed"); }

        // 方案 A：走 plugin messaging（原版走 NMS networkHandler 首发保真，Paper 用握手重试替代）
        boolean ok = HANDLER.sendPlayPayload(player, ServuxHudPacket.MetadataResponse(nbt));
        ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "hud sendMetadata → " + player.getName().getString()
                + " ok=" + ok + " servux=" + nbt.getStringOr("servux", "?")
                + " ver=" + nbt.getIntOr("version", -1)
                + " keys=" + nbt.keySet()
                + " loggers=" + (this.isLoggersEnabled() ? "ON" : "off"));
    }

    public void refreshLoggers(ServerPlayer player, CompoundTag nbt)
    {
        if (!this.isPlayerRegistered(player) || !this.isEnabled() || nbt == null) { return; }

        if (!this.hasPermissionsForLoggers(player))
        {
            player.sendSystemMessage(Component.literal(ServuxReference.MSG_INSUFFICIENT_FOR_LOGGERS.formatted("any")));
            return;
        }

        if (!nbt.isEmpty())
        {
            List<DataLogger> list = new ArrayList<>();
            UUID uuid = player.getUUID();

            for (String key : nbt.keySet())
            {
                DataLogger type = DataLogger.fromStringStatic(key);
                boolean enable = nbt.getBooleanOr(key, false);

                if (type != null)
                {
                    if (this.hasPermissionsForLogger(player, key) && enable) { list.add(type); }
                    else if (!enable) { continue; }
                    else { player.sendSystemMessage(Component.literal(ServuxReference.MSG_INSUFFICIENT_FOR_LOGGERS.formatted(key))); }
                }
            }

            if (!list.isEmpty()) { this.loggerPlayers.put(uuid, list); }
            else { this.loggerPlayers.remove(uuid); }
        }
    }

    public void onPacketFailure(ServerPlayer player)
    {
        this.setPlayerInvalid(player);
        this.registeredPlayers.remove(player.getUUID());
        this.removePlayerLoggers(player);
    }

    /** 玩家退出（quit）全清理：invalid + 注册名册 + loggers + resetFailures（上游 removePlayer :476-483 字面）。 */
    public void removePlayer(ServerPlayer player)
    {
        this.removeInvalidPlayer(player);
        this.registeredPlayers.remove(player.getUUID());
        this.removePlayerLoggers(player);
        HANDLER.resetFailures(this.getNetworkChannel(), player);
    }

    private void removePlayerLoggers(ServerPlayer player) { this.loggerPlayers.remove(player.getUUID()); }

    public void refreshSpawnMetadata(ServerPlayer player, @Nullable CompoundTag data)
    {
        if (!this.isPlayerRegistered(player) || !this.isEnabled() || data == null) { return; }

        GlobalPos spawnPos = this.getSpawnPos();
        CompoundTag nbt = new CompoundTag();

        nbt.putString("id", this.getNetworkChannel().toString());
        nbt.putString("servux", ServuxReference.MOD_STRING);
        nbt.putInt("version", this.getProtocolVersion());
        nbt.putString("spawnDimension", spawnPos.dimension().identifier().toString());
        nbt.putInt("spawnPosX", spawnPos.pos().getX());
        nbt.putInt("spawnPosY", spawnPos.pos().getY());
        nbt.putInt("spawnPosZ", spawnPos.pos().getZ());

        if (this.shareSeed.getValue() && this.hasPermissionsForSeed(player))
        {
            nbt.putLong("worldSeed", this.worldSeed);
        }

        HANDLER.encodeServerData(player, ServuxHudPacket.SpawnResponse(nbt));
    }

    public void refreshWeatherData(ServerPlayer player, @Nullable CompoundTag data)
    {
        if (!this.isPlayerRegistered(player) || !this.isEnabled() || data == null) { return; }

        if (!this.hasPermissionsForWeather(player)) { return; }

        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", this.getNetworkChannel().toString());
        nbt.putString("servux", ServuxReference.MOD_STRING);

        if (this.isRaining && this.rainWeatherTime > -1)
        {
            nbt.putInt("SetRaining", this.rainWeatherTime);
            nbt.putBoolean("isRaining", true);
        }
        else { nbt.putBoolean("isRaining", false); }

        if (this.isThundering && this.thunderWeatherTime > -1)
        {
            nbt.putInt("SetThundering", this.thunderWeatherTime);
            nbt.putBoolean("isThundering", true);
        }
        else { nbt.putBoolean("isThundering", false); }

        if (this.clearWeatherTime > -1) { nbt.putInt("SetClear", this.clearWeatherTime); }

        HANDLER.encodeServerData(player, ServuxHudPacket.WeatherTick(nbt));
    }

    public void refreshRecipeManager(ServerPlayer player, @Nullable CompoundTag data)
    {
        if (!this.isPlayerRegistered(player) || !this.isEnabled() || data == null) { return; }

        if (!this.hasPermission(player)) { return; }

        ServerLevel world = (ServerLevel) player.level();
        java.util.Collection<RecipeHolder<?>> recipes = world.recipeAccess().getRecipes();
        CompoundTag nbt = new CompoundTag();
        ListTag list = new ListTag();

        if (data != null)
        {
            ServuxDebug.log(ServuxDebug.Cat.PACKET, "hudDataChannel: 收到 RecipeManager 请求 from " + player.getName().getString()
                    + ", client version=" + data.getStringOr("version", "?"));
        }

        for (RecipeHolder<?> recipeEntry : recipes)
        {
            DataResult<Tag> dr = Recipe.CODEC.encodeStart(NbtOps.INSTANCE, recipeEntry.value());

            if (dr.result().isPresent())
            {
                CompoundTag entry = new CompoundTag();
                entry.putString("id_reg", recipeEntry.id().registry().toString());
                entry.putString("id_value", recipeEntry.id().identifier().toString());

                // wire 兼容层：混合 ingredients 列表同构化（详见 RecipeNbtNormalizer javadoc）；
                // 防御兜底——任何异常退回原树，行为与未规范化时一致
                Tag recipeTag = dr.result().get();

                try
                {
                    recipeTag = RecipeNbtNormalizer.normalizeIngredients(recipeTag);
                }
                catch (Throwable t)
                {
                    ServuxDebug.log(ServuxDebug.Cat.PACKET, "normalizeIngredients failed (falling back to raw tree) for "
                            + recipeEntry.id().identifier() + ": " + t);
                }

                entry.put("recipe", recipeTag);
                list.add(entry);
            }
        }

        nbt.put("RecipeManager", list);

        // 大包，走 PacketSplitter 分包
        HANDLER.encodeServerData(player, ServuxHudPacket.ResponseS2CStart(nbt));
    }

    public GlobalPos getSpawnPos()
    {
        if (this.spawnPos == null) { this.spawnPos = new GlobalPos(ServerLevel.OVERWORLD, BlockPos.ZERO); }
        return this.spawnPos;
    }

    public void setSpawnPos(GlobalPos spawnPos)
    {
        if (!this.spawnPos.equals(spawnPos))
        {
            this.metadata.remove("spawnDimension");
            this.metadata.remove("spawnPosX");
            this.metadata.remove("spawnPosY");
            this.metadata.remove("spawnPosZ");
            this.metadata.putString("spawnDimension", spawnPos.dimension().identifier().toString());
            this.metadata.putInt("spawnPosX", spawnPos.pos().getX());
            this.metadata.putInt("spawnPosY", spawnPos.pos().getY());
            this.metadata.putInt("spawnPosZ", spawnPos.pos().getZ());
            this.refreshSpawnMetadata = true;
        }

        this.spawnPos = spawnPos;
    }

    /** 同步当前世界出生点（由生命周期事件调用，替代原版 MixinMinecraftServer.prepareLevels / setRespawnData）。 */
    public void updateSpawnFromServer(MinecraftServer server)
    {
        try
        {
            ServerLevel overworld = server.overworld();
            if (overworld == null) { return; }
            org.bukkit.Location loc = overworld.getWorld().getSpawnLocation();
            BlockPos pos = new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            this.setSpawnPos(new GlobalPos(ServerLevel.OVERWORLD, pos));
        }
        catch (Exception e) { ServuxDebug.log(ServuxDebug.Cat.TICK, "updateSpawnFromServer 出生点采集失败（NMS 字段漂移？）: " + e.getMessage()); }
    }

    public boolean shouldRefreshSpawnMetadata() { return this.refreshSpawnMetadata; }
    public void setRefreshSpawnMetadataComplete() { this.refreshSpawnMetadata = false; }
    public boolean shouldRefreshWeatherData() { return this.refreshWeatherData; }
    public void setRefreshWeatherDataComplete() { this.refreshWeatherData = false; }

    public long getWorldSeed() { return this.worldSeed; }

    public void setWorldSeed(long seed)
    {
        if (this.worldSeed != seed)
        {
            if (this.shareSeed.getValue())
            {
                this.metadata.remove("worldSeed");
                this.metadata.putLong("worldSeed", seed);
                this.refreshSpawnMetadata = true;
            }
        }
        this.worldSeed = seed;
    }

    public void checkWorldSeed(MinecraftServer server)
    {
        if (this.shareSeed.getValue())
        {
            ServerLevel world = server.overworld();
            if (world != null) { this.setWorldSeed(world.getSeed()); }
        }
    }

    public boolean isLoggersEnabled() { return this.loggersEnabled.getValue(); }

    public boolean hasPermissionsForWeather(ServerPlayer player) { return Perms.check(player, this.permNode + ".weather", this.weatherPermissionLevel.getValue()); }
    public boolean hasPermissionsForSeed(ServerPlayer player) { return Perms.check(player, this.permNode + ".seed", this.seedPermissionLevel.getValue()); }
    public boolean hasPermissionsForLoggers(ServerPlayer player) { return Perms.check(player, this.permNode + ".logger", this.loggerPermissionLevel.getValue()); }
    public boolean hasPermissionsForLogger(ServerPlayer player, String type) { return Perms.check(player, this.permNode + ".logger." + type, this.loggerPermissionLevel.getValue()); }

    @Override
    public boolean hasPermission(ServerPlayer player) { return Perms.check(player, this.permNode, this.permissionLevel.getValue()); }

    @Override
    public void onPlayerJoin(ServerPlayer player)
    {
        if (!this.isEnabled()) { return; }
        // plugin messaging 握手（客户端 MC|Register servux:hud_metadata）需要时间，延迟 sendMetadata。
        // 白名单：仅已通过 C2S REGISTER 版本门禁的玩家（上游无 join 推送路径，此处是我方 Paper
        // configuration-phase 握手补偿——被版本门禁拒绝的旧客户端永不入册，故永不收到 metadata）。
        try
        {
            new BukkitRunnable()
            {
                @Override
                public void run()
                {
                    if (HudDataProvider.this.isPlayerRegistered(player))
                    {
                        HudDataProvider.this.sendMetadata(player);
                    }
                }
            }.runTaskLater(Reference.plugin(), 40L); // 2s
        }
        catch (Exception e) { ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "onPlayerJoin 延迟 sendMetadata 失败: " + e.getMessage()); }
    }

    @Override
    public void onPlayerQuit(ServerPlayer player) { this.removePlayer(player); }

    @Override
    public void onPlayerRegisterChannel(ServerPlayer player, String channel)
    {
        // 客户端声明监听 servux:hud_metadata = 装了 MiniHUD（configuration phase 后的可靠信号，
        // 比 onPlayerJoin 固定 40t 延迟更准时）。白名单：仅已注册玩家重发（声明通常先于客户端首个
        // REGISTER 到达，此时重发被挡——metadata 首达由 REGISTER 应答链保证[同通道 C2S 证明兜底]；
        // sendMetadata 幂等，注册后重复无害）。
        if (ServuxReference.CHANNEL_HUD.toString().equals(channel) && this.isPlayerRegistered(player))
        {
            this.sendMetadata(player);
        }
    }

    @Override public void onTickEndPre() { /* NO-OP */ }
    @Override public void onTickEndPost() { /* NO-OP */ }

    @Override
    public void onConfigLoaded()
    {
        // 配置加载后（server 已就绪）同步真实世界出生点，替代原版 MixinMinecraftServer 钩子。
        // 否则 spawnPos 恒为构造默认 (0,0,0)，MiniHUD 的 spawn 坐标/指针显示错误。
        try
        {
            MinecraftServer server = Nms.server();
            if (server != null)
            {
                this.updateSpawnFromServer(server);
                GlobalPos sp = this.getSpawnPos();
                ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "hud onConfigLoaded: 同步出生点 → dim=" + sp.dimension().identifier() + " pos=" + sp.pos());
            }
        }
        catch (Exception e) { ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "hud onConfigLoaded 同步出生点异常: " + e.getMessage()); }
    }

    public static class BoolCallback implements IServuxSettingCallback<Boolean>
    {
        @Override
        public void onValueChanged(IServuxSetting<Boolean> setting, Boolean oldValue, Boolean value)
        {
            HudDataProvider.INSTANCE.resetLoggersFromConfig();
        }
    }

    public static class StringListCallback implements IServuxSettingCallback<List<String>>
    {
        @Override
        public void onValueChanged(IServuxSetting<List<String>> setting, List<String> oldValue, List<String> value)
        {
            HudDataProvider.INSTANCE.resetLoggersFromConfig();
        }
    }
}
