package verymc.top.veryMcProto.mod.servux.easyplace;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link EasyPlacePending} 生命周期单测（双挂点范式守卫）。
 *
 * <p>验证 canBuild/place 双挂点依赖的三条不变量：
 * ① {@code peek} 只读不删（canBuild 放行后 place 仍能 take 到同一条目）且 TTL 过期视同 miss；
 * ② {@code take} 读即删（含 canBuild 拒绝路径的主动消费）；
 * ③ {@code stash} 暂存与条目同灭——register 覆盖即清除旧 stash、clear 全清。
 *
 * <p>时钟经 {@link EasyPlacePending#setClockForTest} 注入（AtomicLong 可控时钟），
 * 无需真实等待 TTL。stash 断言用容器/条目<b>同一性</b>（{@code assertSame}）承载——
 * {@code Blocks} 等方块常量类的静态初始化依赖 MC 注册表 bootstrap（纯 JVM 抛
 * {@code Not bootstrapped}，实测），不可在单测中构造真实 BlockState 实例；
 * AtomicReference 的值传递语义由 JDK 保证，无需重复验证。
 */
class EasyPlacePendingTest
{
    private final AtomicLong now = new AtomicLong(1_000_000L);

    EasyPlacePendingTest()
    {
        EasyPlacePending.setClockForTest(now::get);
    }

    @AfterEach
    void restoreClock()
    {
        EasyPlacePending.setClockForTest(System::currentTimeMillis);
    }

    @Test
    void peekIsReadOnlyWithinTtl()
    {
        UUID player = UUID.randomUUID();
        EasyPlacePending.register(player, 42L, 7);

        EasyPlacePending.Pending peeked = EasyPlacePending.peek(player, 42L);
        assertNotNull(peeked);
        assertEquals(7, peeked.protocolValue());

        // peek 不删除：take 仍能取到同一条目（canBuild 放行 → place 消费的正常链路）。
        EasyPlacePending.Pending taken = EasyPlacePending.take(player, 42L);
        assertNotNull(taken);
        assertEquals(7, taken.protocolValue());
        assertNull(EasyPlacePending.take(player, 42L));
    }

    @Test
    void peekTreatsExpiredEntryAsMissAndRemovesIt()
    {
        UUID player = UUID.randomUUID();
        EasyPlacePending.register(player, 42L, 7);

        now.addAndGet(1001L); // 越过 TTL=1000ms

        assertNull(EasyPlacePending.peek(player, 42L));
        // 过期条目被 peek 顺带移除：后续 take / 再 peek 均 miss。
        assertNull(EasyPlacePending.take(player, 42L));
        assertNull(EasyPlacePending.peek(player, 42L));
    }

    @Test
    void takeRemovesEntryAndMissesWhenAbsent()
    {
        UUID player = UUID.randomUUID();
        EasyPlacePending.register(player, 42L, 7);

        assertNotNull(EasyPlacePending.take(player, 42L));
        assertNull(EasyPlacePending.take(player, 42L));

        // 未注册玩家 / 未登记位置。
        assertNull(EasyPlacePending.peek(UUID.randomUUID(), 42L));
        assertNull(EasyPlacePending.take(player, 99L));
    }

    @Test
    void stashCarriesFinalStateAndRegisterOverwriteClearsIt()
    {
        UUID player = UUID.randomUUID();
        EasyPlacePending.register(player, 42L, 7);

        // canBuild 暂存 → place 消费：peek 与 take 返回同一条目实例、同一 stash 容器。
        EasyPlacePending.Pending peeked = EasyPlacePending.peek(player, 42L);
        peeked.stash().set(null); // no-op 标记路径（canBuild 侧 set 的就是同一容器）

        EasyPlacePending.Pending taken = EasyPlacePending.take(player, 42L);
        assertSame(peeked, taken);
        assertSame(peeked.stash(), taken.stash());

        // register 覆盖（同 key 新包）后旧 stash 随旧条目消失：新条目携带全新容器、值恒 null。
        EasyPlacePending.register(player, 42L, 9);
        EasyPlacePending.Pending next = EasyPlacePending.take(player, 42L);
        assertEquals(9, next.protocolValue());
        assertNotSame(peeked.stash(), next.stash());
        assertNull(next.stash().get());
    }

    @Test
    void clearRemovesAllEntriesForPlayer()
    {
        UUID player = UUID.randomUUID();
        EasyPlacePending.register(player, 1L, 7);
        EasyPlacePending.register(player, 2L, 8);

        EasyPlacePending.clear(player);

        assertNull(EasyPlacePending.peek(player, 1L));
        assertNull(EasyPlacePending.peek(player, 2L));
        assertNull(EasyPlacePending.take(player, 1L));
    }
}
