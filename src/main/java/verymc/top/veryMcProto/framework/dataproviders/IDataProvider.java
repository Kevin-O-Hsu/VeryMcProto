package verymc.top.veryMcProto.framework.dataproviders;

import java.util.List;

import com.google.gson.JsonObject;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.framework.settings.IServuxSetting;

/**
 * Provider 契约（框架层）。移植自原版 {@code fi.dy.masa.servux.dataproviders.IDataProvider}。
 *
 * <p>每条协议功能 = 一个 Provider。{@link #getName()} 是逻辑名（config key，如 "hud_data"），
 * <b>≠</b> {@link #getNetworkChannel()}（网络通道，如 servux:hud_metadata）。
 *
 * <p><b>框架增强 / 适配</b>（相对原版）：
 * <ul>
 *   <li>{@link #tick} 去掉 {@code ProfilerFiller} 形参——1.21.11 MinecraftServer 无公开 profiler getter，
 *       且 profiler 仅供性能分析（原版 provider 的 push/pop 在移植时去掉，不影响功能）；</li>
 *   <li>新增 {@link #onPlayerJoin}/{@link #onPlayerQuit}/{@link #onPlayerRespawn} 统一生命周期钩子
 *       （原版在 PlayerListener 按 provider 类型分发握手，本框架统一为接口默认方法，更解耦）。</li>
 * </ul>
 */
public interface IDataProvider
{
    /** 逻辑名（config key，lowercase，如 "hud_data"）。注意 ≠ 网络通道名。 */
    String getName();

    String getDescription();

    /** 网络通道（如 servux:hud_metadata）。 */
    Identifier getNetworkChannel();

    /** 协议版本（客户端协商，须与原版一致）。 */
    int getProtocolVersion();

    boolean isEnabled();

    void setEnabled(boolean enabled);

    boolean isRegistered();

    void setRegistered(boolean toggle);

    /** 启用：注册通道 + receiver。 */
    void registerHandler();

    /** 禁用：反注册。 */
    void unregisterHandler();

    /** 是否需要周期 tick。 */
    default boolean shouldTick()
    {
        return false;
    }

    /** tick 间隔（默认 40）。 */
    int getTickInterval();

    default void tick(MinecraftServer server, int tickCounter)
    {
    }

    default IPluginServerPlayHandler getPacketHandler()
    {
        return null;
    }

    boolean isPlayerRegistered(ServerPlayer player);

    boolean hasPermission(ServerPlayer player);

    // ───── 统一生命周期钩子（框架增强）─────
    default void onPlayerJoin(ServerPlayer player) { }

    default void onPlayerQuit(ServerPlayer player) { }

    default void onPlayerRespawn(ServerPlayer player) { }

    void onTickEndPre();

    void onTickEndPost();

    JsonObject toJson();

    void fromJson(JsonObject obj);

    List<IServuxSetting<?>> getSettings();
}
