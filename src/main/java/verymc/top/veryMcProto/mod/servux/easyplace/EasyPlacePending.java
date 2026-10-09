package verymc.top.veryMcProto.mod.servux.easyplace;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import net.minecraft.world.level.block.state.BlockState;

/**
 * EasyPlace 待修正注册表（mod 层）。
 *
 * <p>「改写放行」范式的跨线程载体：{@link EasyPlaceListener} 在 netty IO 线程把编码包的
 * {@code protocolValue} 登记于此，主线程双挂点消费——{@code BlockCanBuildEvent}（写入前，
 * {@link #peek} 只读 + {@code stash} 暂存判定结果）与 {@code BlockPlaceEvent}
 * （写入后，{@link #take} 读即删 + 消费暂存）。同一放置流程 canBuild 必先于 place fire，
 * 故 take 到的条目恒为「canBuild 已判定」状态（无权限早退等路径除外，stash 恒 null）。
 *
 * <p><b>最小状态载体</b>：每条目仅 {@code (protocolValue, 截止时间, stash 暂存)}——解码器
 * {@code PlacementHandler.applyPlacementProtocolV3} 对 UseContext 的引用面为
 * {@code pos / world / hitVec.x（仅重推 pv）/ entity / itemPlacementContext}，
 * {@code side / hand / hitVec.y / hitVec.z} 全部零引用，故无需登记 face/cursor。stash 由
 * canBuild 挂点写入 {@code null}（no-op 标记）或修正态，register 覆盖（put 换新条目）即隐式清除。
 *
 * <p><b>线程模型</b>：netty IO 线程 {@link #register}（写），主线程 {@link #peek}（读，可惰性
 * 清理本条目）/{@link #take}（读+删）——{@link ConcurrentHashMap} 保证可见性；PacketEvents 事件
 * 按包到达顺序在 netty 线程串行触发，主线程 vanilla 包处理同 tick 晚于 netty 解码，无乱序窗口。
 *
 * <p><b>TTL 命门</b>：vanilla 正常消费发生在登记后同 tick~下一 tick（毫秒级）；TTL 只为兜底
 * 「vanilla 放置失败（canPlace 拒 / 距离检查拒 / BlockPlaceEvent 被保护插件取消 / canBuild
 * 拒绝后事件不来）→ 条目残留 → 污染同位置后续<b>手动</b>放置」的交叉污染窗口。1000ms 已比正常
 * 消费路径宽裕 1~2 个数量级，同时把污染窗口压到难以人为触发的量级。TTL 过期的条目在
 * {@link #take} / {@link #peek} 中均视同未登记。
 */
public final class EasyPlacePending
{
    private EasyPlacePending() { }

    /** 条目有效期（毫秒）。 */
    private static final long TTL_MILLIS = 1000L;

    /** 时钟源（默认系统时间；单测注入用，见 {@link #setClockForTest}）。 */
    private static volatile LongSupplier clock = System::currentTimeMillis;

    /** 外层 key = 玩家 UUID；内层 key = 放置目标 BlockPos.asLong()。 */
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<Long, Pending>> PENDING = new ConcurrentHashMap<>();

    /**
     * 待修正条目：协议值 + 截止时刻 + canBuild 暂存。
     * stash 为 {@link AtomicReference} 容器（record 浅不可变，容器内值由 canBuild 挂点主线程
     * 写入、place 挂点同线程读取，无竞态）；{@code null} 值 = no-op 标记（编码值与 vanilla
     * 候选态一致）或 canBuild 阶段无权限早退——两枝在 place 侧行为同归（不写入）。
     */
    public record Pending(int protocolValue, long deadlineMillis, AtomicReference<BlockState> stash) { }

    /** 登记待修正条目（netty IO 线程调用）。顺带惰性清理该玩家的过期条目。 */
    public static void register(UUID playerId, long posLong, int protocolValue)
    {
        long now = clock.getAsLong();
        ConcurrentHashMap<Long, Pending> map = PENDING.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        map.values().removeIf(p -> p.deadlineMillis() < now);
        map.put(posLong, new Pending(protocolValue, now + TTL_MILLIS, new AtomicReference<>()));
    }

    /** 只读查询条目，不删除（主线程 canBuild 挂点调用）。过期视同未登记并顺带移除本条目（不做全表清理）。 */
    public static EasyPlacePending.Pending peek(UUID playerId, long posLong)
    {
        ConcurrentHashMap<Long, Pending> map = PENDING.get(playerId);
        if (map == null) { return null; }

        Pending pending = map.get(posLong);
        if (pending == null) { return null; }
        if (pending.deadlineMillis() < clock.getAsLong())
        {
            map.remove(posLong, pending);
            return null;
        }
        return pending;
    }

    /** 取出并移除条目（主线程调用）。过期视同未登记（返回 null）。 */
    public static EasyPlacePending.Pending take(UUID playerId, long posLong)
    {
        ConcurrentHashMap<Long, Pending> map = PENDING.get(playerId);
        if (map == null) { return null; }

        Pending pending = map.remove(posLong);
        if (pending == null || pending.deadlineMillis() < clock.getAsLong())
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

    /** 单测注入时钟（仅测试用；主代码不得调用）。 */
    static void setClockForTest(LongSupplier testClock)
    {
        clock = testClock;
    }
}
