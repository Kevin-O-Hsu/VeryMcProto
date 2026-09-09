package verymc.top.veryMcProto.mod.servux.easyplace;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;

import org.bukkit.entity.Player;

import net.minecraft.core.BlockPos;

import verymc.top.veryMcProto.mod.servux.ServuxDebug;

/**
 * EasyPlace 包改写器（mod 层）——「改写放行」范式。
 *
 * <p>Paper 无 Mixin，无法像上游那样注入 {@code BlockItem.getPlacementState}（写入前改态）。
 * 本类改用 PacketEvents 在 netty IO 线程拦截原版 {@code use_item_on}（PacketEvents 包名
 * {@code PLAYER_BLOCK_PLACEMENT}），对<b>已编码</b>的 EasyPlace 包做且仅做两件零状态依赖的事：
 * <ol>
 *   <li>把 {@code cursor.x} 从编码值改写回真实命中偏移 {@code relX∈[0,1)}（PacketEvents wrapper
 *       原生写回，见下「写回命门」）；</li>
 *   <li>登记 {@code (protocolValue)} 到 {@link EasyPlacePending}，由主线程
 *       {@link EasyPlaceFixListener} 在 {@code BlockPlaceEvent} 内修正最终状态。</li>
 * </ol>
 * 放置的其余一切（手持读取 / 距离与保护检查 / 放置 / setPlacedBy / BE 初始化 / 物品消耗 /
 * BlockPlaceEvent / ack）全部由 vanilla 主线程包队列原生完成。
 *
 * <p><b>手持 desync 命门（本范式存在的理由）</b>：{@code use_item_on} 包内不带物品，方块种类由
 * 「vanilla 主线程包队列时刻的玩家手持」隐式决定（26.1.2 {@code handleUseItemOn} 实证）。litematica
 * easyPlace 自动换槽（SetCarriedItem / 容器 SWAP，主线程包队列才应用）与编码包同 tick 背靠背发出——
 * 若在 netty 线程读手持（旧实现），必读到<b>旧槽物品</b>，偶发错块（实机日志 [Netty NIO IO #1]
 * 线程名实锤）。本实现的 netty 路径对玩家状态<b>零读取</b>（不读手持 / 权限 / 世界），该竞态类被
 * 结构性消除；权限门移至 {@link EasyPlaceFixListener}（主线程）。
 *
 * <p><b>cursor.x 改写命门</b>：客户端编码 {@code x = pos.x + relX + 2 + protocolValue}
 * （litematica {@code WorldUtils.applyPlacementProtocolV3}），故 {@code protocolValue = (int) cursor.x − 2}。
 * vanilla {@code handleUseItemOn} 对 hitVec 做逐轴校验 {@code |location − 方块中心| < 1.0000001}
 * （Paper patch），编码值必被静默拒绝（上游为此专门 Mixin 短路该检查）；改写回 {@code relX∈[0,1)}
 * 后校验恒通过且 {@code BlockPlaceContext} 的点击位置语义恢复正常——等效上游 Mixin，无需注入。
 * y / z 保持客户端原始命中偏移（客户端只编码 x）。
 *
 * <p><b>接管判定命门</b>：只改写「已编码」包（pv ≥ 0），普通放置包原样放行：
 * <ul>
 *   <li><b>已编码包</b>（客户端握手到 servux 后 ACCURATE_PLACEMENT_PROTOCOL=AUTO→V3）：
 *       {@code cursor.x = relX + 2 + protocolValue ≥ 2}（relX∈[0,1), protocolValue≥0）。</li>
 *   <li><b>普通放置包</b>（玩家正常右键 / 未启用 EasyPlace 协议）：{@code cursor.x∈[0,1]} → pv<0。</li>
 * </ul>
 *
 * <p><b>写回命门（PacketEvents 语义）</b>：只要构造过 wrapper（读 cursor 必须构造），PacketEvents
 * 就会在事件结束后<b>重编码整个包</b>（{@code PacketEventsImplHelper.handleServerBoundPacket}：
 * clear buffer + 重写 packetId + wrapper.write()），而非字节透传。read/write 逐字段对称（26.1 线的
 * hand/pos/face/cursor/insideBlock/worldBorderHit/sequence 全保真），行为透明；但若未来升级
 * PacketEvents 出现 read/write 不对称，此处是活风险点。{@code event.getPlayer()} 仅取 UUID 作
 * pending key，不触玩家状态。
 *
 * <p><b>依赖</b>：运行时需服务器安装 PacketEvents 插件（{@code plugin.yml: softdepend: [packetevents]}，
 * 由 {@link EasyPlaceBootstrap} 反射加载，缺失时整个 EasyPlace 优雅降级）。
 */
public class EasyPlaceListener implements PacketListener
{
    @Override
    public void onPacketReceive(PacketReceiveEvent event)
    {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) { return; }

        Player bukkitPlayer = event.getPlayer();
        if (bukkitPlayer == null) { return; }

        // 构造 wrapper 读 cursor（注意：构造即注册 lastUsedWrapper，事件后整个包被保真重编码，见类注释「写回命门」）
        WrapperPlayClientPlayerBlockPlacement pkt = new WrapperPlayClientPlayerBlockPlacement(event);
        Vector3f cursor = pkt.getCursorPosition();

        // ★ EasyPlace V3 接管判定：只改写「已编码」包（见类注释「接管判定命门」）。
        int protocolValue = (int) cursor.x - 2;
        Vector3i bp = pkt.getBlockPosition();

        if (protocolValue < 0)
        {
            // 普通放置包 → 原样放行（保真重编码），vanilla 全流程处理（原版自回 ack）。
            // 若持续只有此行而无「★ 编码包」日志 = 客户端从未发送协议编码包（未握手 servux / 协议配置关闭）。
            ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "放行未编码普通放置包 pos=" + bp.x + "," + bp.y + "," + bp.z
                    + " cursor.x=" + cursor.x + " pv=" + protocolValue + "（<0：普通右键或客户端未启用 EasyPlace 协议）");
            return;
        }

        // —— 以下为 EasyPlace V3 已编码包：改写放行 + 登记待修正 ——
        // 1) cursor.x 编码值 → 真实命中偏移 relX（过 vanilla 逐轴 hitVec 校验 + 恢复点击位置语义）
        float relX = cursor.x - (int) cursor.x;
        pkt.setCursorPosition(new Vector3f(relX, cursor.y, cursor.z));

        // 2) 登记待修正（主线程 BlockPlaceEvent 消费；pos 用包 position——litematica 已调整为放置目标）
        BlockPos pos = new BlockPos(bp.x, bp.y, bp.z);
        EasyPlacePending.register(bukkitPlayer.getUniqueId(), pos.asLong(), protocolValue);

        ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "★ 编码包→改写放行 pos=" + pos + " cursor=" + cursor.x + "," + cursor.y + "," + cursor.z
                + " pv=" + protocolValue + " → relX=" + relX + "（已登记待修正）");
    }
}
