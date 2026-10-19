package verymc.top.veryMcProto.mod.carpet.patches;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * 假人连接载体（克隆上游 {@code carpet/patches/FakeClientConnection.java} 1.21.11 树，47 行逐字同构）。
 *
 * <p>挂 {@link EmbeddedChannel}（上游注释明示这让 {@code isOpen} 恒真——末影珍珠等依赖连接存活的
 * 传送路径对假人可用）；{@code send}/{@code handleDisconnection} 等五方法全 no-op——S2C 包在连接层
 * 丢弃，假人"能收不能回"。
 *
 * <p>与上游的偏差（对账登记，docs/carpet.md）：上游经 {@code ClientConnectionInterface}
 * duck 接口（{@code Connection_packetCounterMixin} 注入的 setChannel）注入 channel——B 侧
 * {@link Connection#channel} 本为 public 字段，构造器直接赋值，零 Mixin。
 *
 * <p>no-op 覆写不可省略：{@link Connection#setupInboundProtocol} 内部操作 netty pipeline
 * （addAfter("decoder", ...)），对裸 EmbeddedChannel 会因无 "decoder" handler 抛异常——上游
 * 同款防御，勿删。
 */
public class FakeClientConnection extends Connection
{
    public FakeClientConnection(PacketFlow p)
    {
        super(p);
        // compat with adventure-platform-fabric. This does NOT trigger other vanilla handlers for establishing a channel
        // also makes #isOpen return true, allowing enderpearls to teleport fake players
        this.channel = new EmbeddedChannel();
    }

    @Override
    public void setReadOnly()
    {
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener channelFutureListener, boolean bl)
    {
    }

    @Override
    public void handleDisconnection()
    {
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener packetListener)
    {
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T packetListener)
    {
    }
}
