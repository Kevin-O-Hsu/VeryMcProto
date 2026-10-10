package verymc.top.veryMcProto.mod.syncmatica.communication;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.network.FriendlyByteBuf;

import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaContext;
import verymc.top.veryMcProto.mod.syncmatica.communication.exchange.Exchange;
import verymc.top.veryMcProto.mod.syncmatica.data.ServerPlacement;
import verymc.top.veryMcProto.mod.syncmatica.extended_core.PlayerIdentifier;
import verymc.top.veryMcProto.mod.syncmatica.network.PacketType;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link CommunicationManager} 修改锁表（modifyState）契约单测——固化两条 NPE 链的修复语义：
 *
 * <ul>
 *   <li><b>链①（迁移引入，已修复）</b>：上游 {@code modifyState} 为 HashMap，「{@code setModifier(p, null)}
 *       写 null 值」即解锁 API；本仓库 ConcurrentHashMap 禁 null 值，同一调用曾必抛 NPE（修改成功不广播、
 *       REMOVE 全流程静默失败、锁条目残留致后续 MODIFY 恒 DENY）。修复 = setModifier 内 null→remove 翻译，
 *       本测 {@code lock_then_unlock} 用例即修复前的 NPE 复现点。</li>
 *   <li><b>链②（上游原生缺陷，本地修复）</b>：{@code getModifier(null)}（placement 不存在，如 REMOVE
 *       静默失败残留的幽灵 id）曾沿 {@code null.getHash()} 抛 NPE 且 close 中 onClose 先于 sendCancelPacket
 *       致 DENY 永不发出。修复 = null placement 归一返回 null（无锁）。</li>
 * </ul>
 *
 * <p>实例化方式：同包匿名子类（protected 构造器；{@code handle}/{@code handleExchange} 空实现——
 * 本测不触包派发）。{@code setModifier} 的 syncmatic 非 null 前置（与上游一致、无守卫）不在断言范围。
 */
class ModifyStateTest
{
    private static CommunicationManager newManager()
    {
        return new CommunicationManager()
        {
            @Override
            protected void handle(ExchangeTarget source, PacketType type, FriendlyByteBuf packetBuf) { }

            @Override
            protected void handleExchange(Exchange exchange) { }
        };
    }

    /** 全空实现的最小 Exchange 桩（仅作锁表值类型，不参与通信）。 */
    private static Exchange newDummyExchange()
    {
        return new Exchange()
        {
            @Override public ExchangeTarget getPartner() { return null; }
            @Override public SyncmaticaContext getContext() { return null; }
            @Override public boolean checkPacket(PacketType type, FriendlyByteBuf packetBuf) { return false; }
            @Override public void handle(PacketType type, FriendlyByteBuf packetBuf) { }
            @Override public boolean isFinished() { return false; }
            @Override public boolean isSuccessful() { return false; }
            @Override public void close(boolean notifyPartner) { }
            @Override public void init() { }
        };
    }

    /** 五参构造纯赋值（同 {@code ServerPlacementCleanFileNameTest} 范式），hash 显式指定以便按 key 断言。 */
    private static ServerPlacement placementOf(UUID hash)
    {
        return new ServerPlacement(UUID.randomUUID(), "castle.litematic", "name", hash, PlayerIdentifier.MISSING_PLAYER);
    }

    @Test
    void lock_then_unlock_returnsNull()
    {
        CommunicationManager comMan = newManager();
        ServerPlacement p = placementOf(UUID.randomUUID());
        Exchange lock = newDummyExchange();
        comMan.setModifier(p, lock);
        assertSame(lock, comMan.getModifier(p)); // 占锁可查
        comMan.setModifier(p, null); // 上游解锁 API；修复前 CHM.put(hash, null) 必抛 NPE（链①复现点）
        assertNull(comMan.getModifier(p));
    }

    @Test
    void unlock_withoutLock_isIdempotent()
    {
        // 无锁解锁须幂等：与上游 HashMap put(hash, null) 后 get==null 的可观测行为等价（REMOVE 路径
        // getModifier==null 时不触解锁，但 onPlayerLeave 对未持锁 exchange 的 close 会走到此处）
        CommunicationManager comMan = newManager();
        ServerPlacement p = placementOf(UUID.randomUUID());
        comMan.setModifier(p, null);
        assertNull(comMan.getModifier(p));
    }

    @Test
    void getModifier_nullPlacement_returnsNull()
    {
        // 链②守卫：null placement（不存在的投影）必须表现为「无锁」而非 NPE
        assertNull(newManager().getModifier(null));
    }

    @Test
    void lockOverwrite_latestWins_thenUnlock()
    {
        // 覆盖占锁：后写胜出（REMOVE 强制 close 旧 modifier 后新请求 accept 的等价形态）
        CommunicationManager comMan = newManager();
        ServerPlacement p = placementOf(UUID.randomUUID());
        Exchange first = newDummyExchange();
        Exchange second = newDummyExchange();
        comMan.setModifier(p, first);
        comMan.setModifier(p, second);
        assertSame(second, comMan.getModifier(p));
        comMan.setModifier(p, null);
        assertNull(comMan.getModifier(p));
    }

    @Test
    void unlock_onePlacement_doesNotAffectOther()
    {
        // 异 key 解锁隔离：锁表按 placement hash 键控，互不串扰
        CommunicationManager comMan = newManager();
        ServerPlacement a = placementOf(UUID.randomUUID());
        ServerPlacement b = placementOf(UUID.randomUUID());
        Exchange lockB = newDummyExchange();
        comMan.setModifier(a, newDummyExchange());
        comMan.setModifier(b, lockB);
        comMan.setModifier(a, null);
        assertNull(comMan.getModifier(a));
        assertSame(lockB, comMan.getModifier(b));
    }
}
