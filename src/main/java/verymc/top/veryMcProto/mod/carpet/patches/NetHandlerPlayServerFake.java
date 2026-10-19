package verymc.top.veryMcProto.mod.carpet.patches;

import java.util.Set;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;

/**
 * 假人监听器（克隆上游 {@code carpet/patches/NetHandlerPlayServerFake.java} 26.1 树；{@code teleport}
 * 覆写逐字同构，kick 拦截面按 Paper 26.1 漏斗重推导——见下）。
 *
 * <p><b>kick 拦截（与上游的关键偏差，docs/carpet/architecture.md 偏差清单）</b>：上游覆写
 * {@code disconnect(Component)} 单参方法拦截 idling/重复登录转 kill；Paper 26.1 中该单参方法已退化为
 * 纯转发壳，且 idling 踢出（{@code ServerGamePacketListenerImpl.tick} keepalive 超时）与重复登录踢出
 * 均直接调用双参重载族，全部收敛于 {@code disconnect(DisconnectionDetails)} 终端（PlayerKickEvent 的
 * fire 点也位于该终端内部）。故 B 侧改拦终端：idling / duplicate_login 两 key 转
 * {@link EntityPlayerMPFake#kill}（保留完整清理语义），其余 reason 直接吞掉——假人不可被踢，
 * 与上游单参覆写的语义逐位一致。kill 链走 {@code onDisconnect}（不经本终端），无递归。
 *
 * <p>{@code teleport}：假人无客户端回传 teleport 确认，服务端自驱 resetPosition + 区块追踪移动
 * （上游逐字；守卫 = 玩家仍在世界中才 move）。
 */
public class NetHandlerPlayServerFake extends ServerGamePacketListenerImpl
{
    public NetHandlerPlayServerFake(final MinecraftServer minecraftServer, final Connection connection, final ServerPlayer serverPlayer, final CommonListenerCookie cookie)
    {
        super(minecraftServer, connection, serverPlayer, cookie);
    }

    @Override
    public void disconnect(DisconnectionDetails details)
    {
        if (details.reason().getContents() instanceof TranslatableContents text
                && (text.getKey().equals("multiplayer.disconnect.idling")
                || text.getKey().equals("multiplayer.disconnect.duplicate_login")))
        {
            ((EntityPlayerMPFake) player).kill(details.reason());
        }
        // 其余 reason 吞掉（上游同语义：假人不响应任何其他 disconnect 调用）
    }

    @Override
    public void teleport(PositionMoveRotation positionMoveRotation, Set<Relative> set)
    {
        super.teleport(positionMoveRotation, set);
        if (player.level().getPlayerByUUID(player.getUUID()) != null)
        {
            resetPosition();
            player.level().getChunkSource().move(player);
        }
    }
}
