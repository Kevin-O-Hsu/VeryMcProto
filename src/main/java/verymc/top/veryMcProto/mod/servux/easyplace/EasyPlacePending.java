package verymc.top.veryMcProto.mod.servux.easyplace;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EasyPlace 待修正注册表（mod 层）。
 *
 * <p>「改写放行」范式的跨线程载体：{@link EasyPlaceListener} 在 netty IO 线程把编码包的
 * {@code protocolValue} 登记于此，vanilla 主线程全流程放置后由 {@link EasyPlaceFixListener}
 * 在 {@code BlockPlaceEvent}（同 tick~下一 tick）消费。
 *
 * <p><b>最小状态载体</b>：每条目仅 {@code (protocolValue, 截止时间)}——解码器
 * {@code PlacementHandler.applyPlacementProtocolV3} 对 UseContext 的引用面为
 * {@code pos / world / hitVec.x（仅重推 pv）/ entity / itemPlacementContext}，
 * {@code side / hand / hitVec.y / hitVec.z} 全部零引用，故无需登记 face/cursor。
 *
 * <p><b>线程模型</b>：netty IO 线程 {@link #register}（写），主线程 {@link #take}（读+删）——
 * {@link ConcurrentHashMap} 保证可见性；PacketEvents 事件按包到达顺序在 netty 线程串行触发，
 * 主线程 vanilla 包处理同 tick 晚于 netty 解码，无乱序窗口。
 *
 * <p><b>TTL 命门</b>：vanilla 正常消费发生在登记后同 tick~下一 tick（毫秒级）；TTL 只为兜底
 * 「vanilla 放置失败（canPlace 拒 / 距离检查拒 / BlockPlaceEvent 被保护插件取消）→ 事件不来 →
 * 条目残留 → 污染同位置后续<b>手动</b>放置」的交叉污染窗口。1000ms 已比正常消费路径宽裕 1~2 个
 * 数量级，同时把污染窗口压到难以人为触发的量级。TTL 过期的条目在 {@link #take} 中视同未登记。
 */
public final class EasyPlacePending
{
    private EasyPlacePending() { }

    /** 条目有效期（毫秒）。 */
    private static final long TTL_MILLIS = 1000L;

    /** 外层 key = 玩家 UUID；内层 key = 放置目标 BlockPos.asLong()。 */
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<Long, Pending>> PENDING = new ConcurrentHashMap<>();

    /** 待修正条目：协议值 + 截止时刻。 */
    public record Pending(int protocolValue, long deadlineMillis) { }

    /** 登记待修正条目（netty IO 线程调用）。顺带惰性清理该玩家的过期条目。 */
    public static void register(UUID playerId, long posLong, int protocolValue)
    {
        long now = System.currentTimeMillis();
        ConcurrentHashMap<Long, Pending> map = PENDING.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        map.values().removeIf(p -> p.deadlineMillis() < now);
        map.put(posLong, new Pending(protocolValue, now + TTL_MILLIS));
    }

    /** 取出并移除条目（主线程调用）。过期视同未登记（返回 null）。 */
    public static EasyPlacePending.Pending take(UUID playerId, long posLong)
    {
        ConcurrentHashMap<Long, Pending> map = PENDING.get(playerId);
        if (map == null) { return null; }

        Pending pending = map.remove(posLong);
        if (pending == null || pending.deadlineMillis() < System.currentTimeMillis())
        {
            return null;
        }
        return pending;
    }

    /** 玩家退出时清空其全部条目（防泄漏；PlayerQuitEvent 主线程调用）。 */
    public static void clear(UUID playerId)
    {
        PENDING.remove(playerId);
    }
}
