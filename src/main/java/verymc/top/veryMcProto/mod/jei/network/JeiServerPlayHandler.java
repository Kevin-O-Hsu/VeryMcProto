package verymc.top.veryMcProto.mod.jei.network;

import java.util.function.BiConsumer;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.mod.jei.JeiReference;
import verymc.top.veryMcProto.mod.jei.config.JeiConfiguration;
import verymc.top.veryMcProto.mod.jei.network.payload.PacketDeletePlayerItem;
import verymc.top.veryMcProto.mod.jei.network.payload.PacketGiveItemStack;
import verymc.top.veryMcProto.mod.jei.network.payload.PacketRecipeTransferCountedWithResult;
import verymc.top.veryMcProto.mod.jei.network.payload.PacketRecipeTransferWithResult;
import verymc.top.veryMcProto.mod.jei.network.payload.PacketRequestCheatPermission;
import verymc.top.veryMcProto.mod.jei.network.payload.PacketSetHotbarItemStack;
import verymc.top.veryMcProto.mod.jei.network.payload.legacy.PacketRecipeTransfer;
import verymc.top.veryMcProto.mod.jei.network.payload.legacy.PacketRecipeTransferCounted;

/**
 * jei:* C2S 通道统一处理器（mod 层）。实现框架 {@link IPluginServerPlayHandler} 字节级回调
 * （{@code ProtocolChannel} 已完成 byte[] → {@link FriendlyByteBuf} 包装与 Paper 路由），
 * 每通道一个实例（{@link JeiReference#C2S_CHANNELS} 清单），把 buffer 升级为
 * {@link RegistryFriendlyByteBuf}（ItemStack codec 需要 registryAccess）后按通道分发解码。
 *
 * <p><b>门禁语义（R3 硬约束）</b>：{@code enabled=false} 仅在 handler 内丢弃后续包——
 * <b>通道保持注册、绝不注销</b>。客户端在通道声明有效期内仍可能发送 C2S，注销 incoming 会让
 * Paper 对未注册通道的 custom payload 踢玩家（AGENTS.md「C2S 接收命门」）。
 *
 * <p><b>安全面</b>：逐包 try-catch（框架 {@code ProtocolChannel} 外层另有兜底）；解码严格
 * （{@code readEnum} 越界抛 {@code DecoderException}、{@code TransferOperation} count&lt;1 拒绝）；
 * 槽位界校验见 {@code AbstractRecipeTransferPacket.getSlots}。
 */
public final class JeiServerPlayHandler implements IPluginServerPlayHandler
{
    private final Identifier channel;
    private final BiConsumer<RegistryFriendlyByteBuf, JeiServerPacketContext> dispatch;
    private volatile boolean registered;

    private JeiServerPlayHandler(Identifier channel, BiConsumer<RegistryFriendlyByteBuf, JeiServerPacketContext> dispatch)
    {
        this.channel = channel;
        this.dispatch = dispatch;
    }

    /** 按通道构造（清单外通道抛出——步骤 0 冻结清单的运行时守护）。 */
    public static JeiServerPlayHandler forChannel(Identifier channel)
    {
        return new JeiServerPlayHandler(channel, dispatcher(channel));
    }

    private static BiConsumer<RegistryFriendlyByteBuf, JeiServerPacketContext> dispatcher(Identifier channel)
    {
        if (channel.equals(JeiReference.CHANNEL_REQUEST_CHEAT_PERMISSION))
        {
            return PacketRequestCheatPermission::decodeAndProcess;
        }
        if (channel.equals(JeiReference.CHANNEL_GIVE_ITEM_STACK))
        {
            return (buf, ctx) -> PacketGiveItemStack.decode(buf).process(ctx);
        }
        if (channel.equals(JeiReference.CHANNEL_DELETE_PLAYER_ITEM))
        {
            return (buf, ctx) -> PacketDeletePlayerItem.decode(buf).process(ctx);
        }
        if (channel.equals(JeiReference.CHANNEL_SET_HOTBAR_ITEM_STACK))
        {
            return (buf, ctx) -> PacketSetHotbarItemStack.decode(buf).process(ctx);
        }
        if (channel.equals(JeiReference.CHANNEL_RECIPE_TRANSFER_WITH_RESULT))
        {
            return (buf, ctx) -> PacketRecipeTransferWithResult.decode(buf).process(ctx);
        }
        if (channel.equals(JeiReference.CHANNEL_RECIPE_TRANSFER_COUNTED_WITH_RESULT))
        {
            return (buf, ctx) -> PacketRecipeTransferCountedWithResult.decode(buf).process(ctx);
        }
        if (channel.equals(JeiReference.CHANNEL_RECIPE_TRANSFER_LEGACY))
        {
            return (buf, ctx) -> PacketRecipeTransfer.decode(buf).process(ctx);
        }
        if (channel.equals(JeiReference.CHANNEL_RECIPE_TRANSFER_COUNTED_LEGACY))
        {
            return (buf, ctx) -> PacketRecipeTransferCounted.decode(buf).process(ctx);
        }
        throw new IllegalArgumentException("非 jei:* C2S 通道: " + channel);
    }

    @Override
    public Identifier getPayloadChannel()
    {
        return channel;
    }

    @Override
    public boolean isPlayRegistered(Identifier channel)
    {
        return registered;
    }

    @Override
    public void setPlayRegistered(Identifier channel)
    {
        this.registered = true;
    }

    @Override
    public void clearPlayRegistered(Identifier channel)
    {
        this.registered = false;
    }

    @Override
    public void reset(Identifier channel)
    {
        // 无跨包状态（cheat 请求-应答无状态、transferId 是客户端侧关联键），无需清理
    }

    @Override
    public void encodeWithSplitter(ServerPlayer player, FriendlyByteBuf buf)
    {
        // jei:* S2C 恒小包，不走 PacketSplitter（大包路径在 JeiPacketSender / recipesync）
    }

    @Override
    public void receivePlayPayload(FriendlyByteBuf data, ServerPlayer player)
    {
        try
        {
            // R3 门禁：disabled 只丢包，通道不注销（见类 javadoc）
            JeiConfiguration config = JeiConfiguration.getInstance();
            if (config == null || !config.isEnabled())
            {
                return;
            }

            RegistryFriendlyByteBuf buf = data instanceof RegistryFriendlyByteBuf registryBuf
                    ? registryBuf
                    : new RegistryFriendlyByteBuf(data, player.registryAccess());
            dispatch.accept(buf, new JeiServerPacketContext(player, config));
        }
        catch (Exception e)
        {
            Reference.logger().warning("[JEI] C2S 处理失败 " + channel + " ← "
                    + player.getName().getString() + ": " + e.getMessage());
        }
    }
}
