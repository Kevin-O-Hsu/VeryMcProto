package verymc.top.veryMcProto.mod.jei.network;

import java.util.Map;
import java.util.UUID;

import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecipeSyncJoinOrderer} 状态机单测（EmbeddedChannel 纯 netty 逻辑；
 * install 的反射链属 Paper 运行时面，不在本测范围）。覆盖：
 * <ul>
 *   <li>扣留吞写——首个 UpdateRecipesPacket 持有不出站，非 UpdateRecipes 消息透传；</li>
 *   <li>释放保序——payload 写 → releaseInternal ⇒ wire 序 = payload → UpdateRecipes，且 handler 自移除；</li>
 *   <li>超时兜底——timeoutMs 到期后放行（vanilla/慢网路径）；</li>
 *   <li>扣留期间第二个 UpdateRecipes——被扣包先于新包放行（/reload 竞态保序）后终结；</li>
 *   <li>断连清理——close 持有中不抛异常，之后 releaseInternal 幂等 no-op。</li>
 * </ul>
 */
class RecipeSyncJoinOrdererTest
{
    @org.junit.jupiter.api.BeforeAll
    static void bootstrapMinecraft()
    {
        // 26.1：ClientboundUpdateRecipesPacket 的 <clinit> 静态链（RecipePropertySet → Item → 注册表）
        // 要求 MC bootstrap（与 TaskGroupTest 同模式；且必须先于其他用例引导，否则失败会被
        // 类初始化缓存并污染同 JVM 的后续 bootstrap）
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static EmbeddedChannel newChannelWithOrderer(long timeoutMs)
    {
        EmbeddedChannel channel = new EmbeddedChannel();
        // 占位编码槽（生产 addAfter 目标名，见 RecipeSyncJoinOrderer.installOnEventLoop——出站沿
        // tail→head 传播，拦截器须在 encoder 的 tail 侧才能在编码前看到 Packet 对象）
        channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        channel.pipeline().addAfter("encoder", RecipeSyncJoinOrderer.HANDLER_NAME,
                new RecipeSyncJoinOrderer(channel, UUID.randomUUID(), timeoutMs));
        return channel;
    }

    private static RecipeSyncJoinOrderer orderer(EmbeddedChannel channel)
    {
        return (RecipeSyncJoinOrderer) channel.pipeline().get(RecipeSyncJoinOrderer.HANDLER_NAME);
    }

    private static ClientboundUpdateRecipesPacket updateRecipes()
    {
        return new ClientboundUpdateRecipesPacket(Map.of(), SelectableRecipe.SingleInputSet.<StonecutterRecipe>empty());
    }

    @Test
    void holdsFirstUpdateRecipesAndPassesOthersThrough()
    {
        EmbeddedChannel channel = newChannelWithOrderer(60_000);

        channel.writeOutbound(updateRecipes());
        assertNull(channel.readOutbound(), "被扣期间不应有任何 UpdateRecipes 出站");

        Object payload = new Object();
        assertTrue(channel.writeOutbound(payload), "非 UpdateRecipes 消息应透传");
        assertSame(payload, channel.readOutbound());
        assertNull(channel.readOutbound());

        orderer(channel).releaseInternal();
        assertTrue(channel.readOutbound() instanceof ClientboundUpdateRecipesPacket, "释放后 UpdateRecipes 应出站");
        assertNull(channel.readOutbound(), "释放后无残留");
        assertNull(channel.pipeline().get(RecipeSyncJoinOrderer.HANDLER_NAME), "handler 应自移除");
    }

    @Test
    void releaseKeepsWireOrderPayloadBeforeUpdateRecipes()
    {
        EmbeddedChannel channel = newChannelWithOrderer(60_000);
        channel.writeOutbound(updateRecipes());

        // 模拟真实链：register 证据 → sendFabric 的 payload 写先提交 → release 放行被扣包
        Object payload = new Object();
        channel.writeOutbound(payload);
        orderer(channel).releaseInternal();

        assertSame(payload, channel.readOutbound(), "wire 序第一 = fabric:recipe_sync payload");
        assertTrue(channel.readOutbound() instanceof ClientboundUpdateRecipesPacket, "wire 序第二 = UpdateRecipes");
        assertNull(channel.readOutbound());
    }

    @Test
    void timeoutReleasesWithoutEvidence()
    {
        EmbeddedChannel channel = newChannelWithOrderer(20);
        channel.writeOutbound(updateRecipes());

        channel.runScheduledPendingTasks(); // 无到期任务时 no-op
        try
        {
            Thread.sleep(60);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        channel.runScheduledPendingTasks(); // 执行到期超时任务

        assertTrue(channel.readOutbound() instanceof ClientboundUpdateRecipesPacket, "超时应放行");
        assertNull(channel.pipeline().get(RecipeSyncJoinOrderer.HANDLER_NAME), "超时路径同样自移除");
    }

    @Test
    void secondUpdateRecipesDuringHoldKeepsOriginalOrder()
    {
        EmbeddedChannel channel = newChannelWithOrderer(60_000);
        ClientboundUpdateRecipesPacket first = updateRecipes();
        ClientboundUpdateRecipesPacket second = updateRecipes();
        channel.writeOutbound(first);

        Object payload = new Object();
        channel.writeOutbound(payload);
        channel.writeOutbound(second);

        assertSame(payload, channel.readOutbound());
        assertSame(first, channel.readOutbound(), "被扣包须先于扣留期新包放行");
        assertSame(second, channel.readOutbound());
        assertNull(channel.pipeline().get(RecipeSyncJoinOrderer.HANDLER_NAME), "竞态路径终结后自移除");
    }

    @Test
    void closeWhileHoldingThenReleaseIsSafeNoOp()
    {
        EmbeddedChannel channel = newChannelWithOrderer(60_000);
        channel.writeOutbound(updateRecipes());
        RecipeSyncJoinOrderer orderer = orderer(channel); // 先取引用（EmbeddedChannel close 即清空 pipeline）

        channel.close().syncUninterruptibly();
        assertNull(channel.readOutbound(), "断连后无出站");

        // 迟到的释放必须幂等 no-op（不抛异常、不再出站）；生产入口 release(UUID) 对注册表缺失同样安全
        orderer.releaseInternal();
        RecipeSyncJoinOrderer.release(UUID.randomUUID());
        assertNull(channel.readOutbound());
    }
}
