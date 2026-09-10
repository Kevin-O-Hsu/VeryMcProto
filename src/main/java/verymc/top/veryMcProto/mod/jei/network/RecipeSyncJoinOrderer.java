package verymc.top.veryMcProto.mod.jei.network;

import java.nio.channels.ClosedChannelException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;

import io.papermc.paper.connection.PlayerConfigurationConnection;

import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.reflect.Reflect;

/**
 * fabric 配方同步进服时序整形器（netty 出站拦截——全仓库首例，范式论证见 docs/30 §5.3）。
 *
 * <p><b>要解决的问题</b>：JEI 客户端在处理 play 相位 {@code ClientboundUpdateRecipesPacket} 的 RETURN
 * 时启动并一次性判定配方同步状态（上游 {@code ClientPacketListenerRecipeUpdateMixin} →
 * {@code JeiStarter.verifyClientRecipes}，Paper brand 落入 unavailable 分支打印警告）。上游 Fabric
 * 服务端在 {@code PlayerList.placeNewPlayer} 内、构造 UpdateRecipesPacket <b>之前</b>发送配方
 * （fabric-lifecycle-events-v1 {@code PlayerListMixin} 注入点 {@code @At("NEW", target=...UpdateRecipesPacket)}），
 * payload 因此排在 UpdateRecipesPacket 之前上线。Paper 上该时点没有任何 Bukkit 钩子可用
 * （PlayerJoinEvent 在 UpdateRecipesPacket 之后、PlayerRegisterChannelEvent 依赖客户端收到
 * LoginPacket 后的 play register——更晚），唯一先于 UpdateRecipesPacket 离开服务端的可控点
 * 是 netty 出站管线。
 *
 * <p><b>机制</b>：configuration 相位末（{@code AsyncPlayerConnectionConfigureEvent}）向连接管线
 * {@code addBefore("encoder")} 安装本一次性拦截器，扣住首个出站 UpdateRecipesPacket；客户端处理
 * LoginPacket 后立即回发 play register（= 客户端能收 {@code fabric:recipe_sync} 的<b>正向证据</b>，
 * 即现有 {@code PlayerRegisterChannelEvent} 触发路径）→ {@code RecipeSyncService.sendFabric} 提交
 * payload 写 → {@link #release(UUID)} 放行被扣包。主线程同步提交序 + eventLoop FIFO ⇒ wire 序
 * 恒为 payload → UpdateRecipesPacket，与上游不变量逐字节同源。
 *
 * <p><b>防护语义</b>：只发给声明过通道的客户端（无证据绝不发送——~100KB 载荷发给 vanilla 客户端
 * 会命中未知通道 32767 解码上限断连）。vanilla / 慢网客户端走超时放行（配方书晚到上界 3s，无感知）。
 * 一切内部失败（反射漂移 / 管线异常）fail-open 降级为现状时序（JEI 警告依旧，无新增损害）。
 *
 * <p><b>线程模型</b>：held/done 等可变状态仅在 eventLoop 线程读写（write 回调与释放任务都被其串行化）；
 * 静态注册表 {@link #BY_UUID} 为 ConcurrentHashMap（install 在 config 事件线程写、release 在主线程读）。
 */
public final class RecipeSyncJoinOrderer extends ChannelDuplexHandler
{
    /** netty 管线内处理器名（幂等移除 / 防重复安装）。 */
    public static final String HANDLER_NAME = "verymc_jei_recipe_orderer";

    /**
     * 证据等待超时（毫秒）。客户端处理 LoginPacket 后立即回发 play register，正常网络 &lt;100ms；
     * 3s 覆盖 ~1s RTT 余量。超时放行 = vanilla/慢网客户端的配方书晚到上界；JEI 客户端慢于此时
     * 则退化为现状警告（无回归）。
     */
    static final long RELEASE_TIMEOUT_MS = 3000L;

    /** 同一连接只装一次的标记：reconfigure 的二次 config 相位跳过（通道集合已从 play 相位携带、无 register 事件可等）。 */
    private static final AttributeKey<Boolean> FIRST_CONFIG_SEEN = AttributeKey.valueOf("verymc_jei_first_config_seen");

    /** 注册表：玩家 UUID → 待释放实例（installOnEventLoop 登记，release / 各清理路径移除）。 */
    private static final ConcurrentHashMap<UUID, RecipeSyncJoinOrderer> BY_UUID = new ConcurrentHashMap<>();

    private final Channel channel;
    private final UUID uuid;
    private final long timeoutMs;

    // —— 以下可变状态仅 eventLoop 线程读写（见类注释线程模型） ——
    private ChannelHandlerContext ctx;
    private Object heldMsg;
    private ChannelPromise heldPromise;
    private boolean done;
    private ScheduledFuture<?> timeoutTask;

    RecipeSyncJoinOrderer(Channel channel, UUID uuid)
    {
        this(channel, uuid, RELEASE_TIMEOUT_MS);
    }

    /** 测试可注入超时（生产恒 {@link #RELEASE_TIMEOUT_MS}）。 */
    RecipeSyncJoinOrderer(Channel channel, UUID uuid, long timeoutMs)
    {
        this.channel = channel;
        this.uuid = uuid;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 在 config 相位末安装本整形器（{@code JeiListener.onAsyncConfigure} 调用）。
     * 任何失败只记日志并降级为现状时序，绝不影响连接。
     */
    public static void install(PlayerConfigurationConnection connection)
    {
        try
        {
            Object listener = resolveListener(connection);
            if (!(listener instanceof ServerCommonPacketListenerImpl nmsListener) || nmsListener.connection == null)
            {
                return;
            }
            Channel channel = Reflect.getOr(nmsListener.connection, "channel", null);
            if (channel == null)
            {
                return;
            }
            if (channel.attr(FIRST_CONFIG_SEEN).get() != null)
            {
                return;
            }
            channel.attr(FIRST_CONFIG_SEEN).set(Boolean.TRUE);
            UUID uuid = connection.getProfile().getId();
            if (uuid == null)
            {
                return;
            }
            channel.eventLoop().execute(() -> installOnEventLoop(channel, uuid));
        }
        catch (Throwable t)
        {
            // AGENTS.md §6 教训 5：类加载/反射失败 try-catch 抓不到——这里整链 catch(Throwable) 隔离
            Reference.logger().warning("[JEI] 配方时序整形器安装失败（降级为现状时序）: " + t);
        }
    }

    /**
     * 证据到达（{@code PlayerRegisterChannelEvent(fabric:recipe_sync)}）后放行被扣包。
     * 调用方须<b>先</b>完成 payload 发送（{@code connection.send} 同步提交写任务），eventLoop FIFO
     * 保证 wire 序 = payload → UpdateRecipesPacket。
     */
    public static void release(UUID uuid)
    {
        RecipeSyncJoinOrderer orderer = BY_UUID.get(uuid);
        if (orderer == null)
        {
            return;
        }
        try
        {
            orderer.channel.eventLoop().execute(orderer::releaseInternal);
        }
        catch (Throwable ignored)
        {
            // eventLoop 已关闭 = 连接已断，channelInactive 路径已清理
        }
    }

    private static Object resolveListener(PlayerConfigurationConnection connection)
    {
        // dev-bundle 26.1.2.build.74 实证字段名 packetListener（PaperCommonConnection.patch:25）；
        // handle 回退对冲 Paper 内部重命名漂移（Reflect.getOr 沿父类链查找）
        Object listener = Reflect.getOr(connection, "packetListener", null);
        if (listener == null)
        {
            listener = Reflect.getOr(connection, "handle", null);
        }
        return listener;
    }

    private static void installOnEventLoop(Channel channel, UUID uuid)
    {
        try
        {
            if (!channel.isActive())
            {
                return;
            }
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null)
            {
                pipeline.remove(HANDLER_NAME);
            }
            RecipeSyncJoinOrderer orderer = new RecipeSyncJoinOrderer(channel, uuid);
            // 出站编码槽位自 LOGIN 握手装配协议起恒名 encoder（UnconfiguredPipelineHandler.replace 改名，
            // outbound_config 仅是未装配占位名）。出站事件沿 tail→head 传播——必须 addAfter（encoder 的
            // tail 侧）才能在编码前看到 Packet 对象；addBefore 会在编码后收到 ByteBuf、拦截永不命中
            pipeline.addAfter(HandlerNames.ENCODER, HANDLER_NAME, orderer);
            BY_UUID.put(uuid, orderer);
        }
        catch (Throwable t)
        {
            Reference.logger().warning("[JEI] 配方时序整形器管线插入失败（降级为现状时序）: " + t);
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx)
    {
        if (!ctx.channel().isActive())
        {
            // 加入时连接已死：channelInactive 不会对本实例触发，主动清理注册表防泄漏
            this.done = true;
            BY_UUID.remove(this.uuid, this);
            return;
        }
        this.ctx = ctx;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception
    {
        if (this.done || !(msg instanceof ClientboundUpdateRecipesPacket))
        {
            super.write(ctx, msg, promise);
            return;
        }
        if (this.heldMsg == null)
        {
            // 扣住 placeNewPlayer 的进服 UpdateRecipesPacket（JEI 在其 RETURN 处启动判定）
            this.heldMsg = msg;
            this.heldPromise = promise;
            this.timeoutTask = ctx.executor().schedule(this::releaseInternal, this.timeoutMs, TimeUnit.MILLISECONDS);
            return;
        }
        // 扣留期间又来一个（进服 3s 内 /reload 竞态）：按原 wire 序放行被扣包再透传新包，随后终结
        cancelTimeout();
        Object held = this.heldMsg;
        ChannelPromise heldPromise = this.heldPromise;
        this.heldMsg = null;
        this.heldPromise = null;
        this.done = true;
        ctx.write(held, heldPromise);
        super.write(ctx, msg, promise);
        removeSelf();
    }

    /** 放行被扣包并自移除（幂等；仅 eventLoop 线程调用——write 回调 / 超时任务 / release 提交）。 */
    void releaseInternal()
    {
        if (this.done)
        {
            return;
        }
        this.done = true;
        cancelTimeout();
        try
        {
            if (this.ctx != null && this.heldMsg != null)
            {
                this.ctx.writeAndFlush(this.heldMsg, this.heldPromise);
            }
            this.heldMsg = null;
            this.heldPromise = null;
        }
        finally
        {
            removeSelf();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception
    {
        if (!this.done)
        {
            this.done = true;
            cancelTimeout();
            // voidPromise 不可 fail（会 fireExceptionCaught）；被扣包随连接关闭丢弃
            if (this.heldPromise != null && !this.heldPromise.isVoid() && !this.heldPromise.isDone())
            {
                this.heldPromise.tryFailure(new ClosedChannelException());
            }
            this.heldMsg = null;
            this.heldPromise = null;
        }
        BY_UUID.remove(this.uuid, this);
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx)
    {
        // 任何被移除路径（含外部 remove）统一回收注册表
        this.done = true;
        cancelTimeout();
        BY_UUID.remove(this.uuid, this);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception
    {
        if (!this.done)
        {
            // fail-open：绝不让整形器吞掉进服配方包
            Reference.logger().warning("[JEI] 配方时序整形器异常，放行被扣包: " + cause);
            releaseInternal();
        }
        super.exceptionCaught(ctx, cause);
    }

    private void removeSelf()
    {
        ChannelPipeline pipeline = this.channel.pipeline();
        if (pipeline.get(HANDLER_NAME) == this)
        {
            pipeline.remove(this);
        }
        BY_UUID.remove(this.uuid, this);
    }

    private void cancelTimeout()
    {
        if (this.timeoutTask != null)
        {
            this.timeoutTask.cancel(false);
            this.timeoutTask = null;
        }
    }
}
