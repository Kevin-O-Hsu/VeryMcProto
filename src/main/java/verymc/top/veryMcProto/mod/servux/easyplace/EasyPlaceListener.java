package verymc.top.veryMcProto.mod.servux.easyplace;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.mod.servux.ServuxDebug;
import verymc.top.veryMcProto.framework.nms.Nms;
import verymc.top.veryMcProto.mod.servux.dataproviders.ConfigProvider;
import verymc.top.veryMcProto.mod.servux.util.PlacementHandler;

/**
 * EasyPlace 包拦截器（mod 层）。
 *
 * <p>Paper 无 Mixin，无法像原版那样注入 {@code BlockItem.getPlacementState} / 短路 hitVec 距离检查。
 * 本类改用 PacketEvents 拦截原版 {@code use_item_on}（PacketEvents 包名 {@code PLAYER_BLOCK_PLACEMENT}）：
 * 取消原版包处理 → 用 {@link PlacementHandler#applyPlacementProtocolV3} 解码出精确 BlockState → 手动复刻
 * {@code BlockItem.place} 的副作用（setBlock / setPlacedBy 初始化方块实体 / 放置音效 / 物品消耗 / 回 ack）。
 *
 * <p><b>解码命门</b>：Tweakeroo 客户端把 {@code protocolValue} 编码进 hitVec.x 偏移
 * （PlacementTweaks: {@code x = posNew.x + relX + 2 + protocolValue}）。1.21 {@code ServerboundUseItemOnPacket}
 * 的 3 个 cursor float 是「相对被点击方块」的偏移（{@code hitVec − blockPos}），PacketEvents 原样读出。
 * 故 {@code cursor.x = relX + 2 + protocolValue}，{@code protocolValue = (int) cursor.x − 2}，与
 * {@link PlacementHandler} 的 {@code (int)(hitVec.x − pos.x) − 2} 完全对应（因 {@code hitVec.x = pos.x + cursor.x}）。
 *
 * <p><b>接管判定命门（最关键）</b>：必须只接管「已编码」的 EasyPlace 包，普通放置包一律放行。
 * <ul>
 *   <li><b>已编码包</b>（客户端握手到 servux 后 ACCURATE_PLACEMENT_PROTOCOL=AUTO→V3）：
 *       {@code cursor.x = relX + 2 + protocolValue ≥ 2}（relX∈[0,1), protocolValue≥0）→ 接管。</li>
 *   <li><b>普通放置包</b>（玩家正常右键 / 客户端未握手 servux 没启用 EasyPlace）：
 *       {@code cursor.x∈[0,1]}（方块表面击中点）→ <b>放行原版</b>。</li>
 * </ul>
 * 原版 servux 用 Mixin 注入 {@code getPlacementState}，对未编码包天然无副作用（位置/朝向全由原版处理）；
 * 本移植「取消包+手动 setBlock」，若对未编码包也接管：解码必得 {@code protocolValue<0} →
 * {@code applyPlacementProtocolV3} 直接返回 defaultBlockState（非 null）→ {@code setBlock} 落在「被点击方块」pos 上 →
 * ① 准星指向的实体方块被手里的方块覆盖；② 客户端块预测在 {@code pos.relative(face)}，与服务端 pos 冲突，
 * ack 触发回滚 → 方块「放出来又消失」。故 {@code protocolValue<0} 必须放行原版，绝不可接管。
 *
 * <p><b>放置位置</b>：EasyPlace 编码包里 Tweakeroo 已把包 position 调整为「放置目标」
 * （{@code getPlacementPositionForTargetedPosition}：可替换则原位、否则 relative(side)，等同 NMS
 * {@code BlockPlaceContext.getClickedPos()} 的 replaceClicked 逻辑），故 {@code pkt.getBlockPosition()} 即放置目标，
 * 直接 {@code setBlock(pos)} 位置正确（与原版 servux 用 {@code ctx.getClickedPos()} 一致）。
 *
 * <p><b>权限</b>：仅当玩家通过 {@code servux.main.easy_place}（{@link ConfigProvider#hasPermission_EasyPlace}）
 * 时才进入接管判定；否则放行原版包走默认放置。物品非 BlockItem 时也放行（EasyPlace 只精确放置方块）。
 *
 * <p><b>调试日志</b>：本类所有日志走 {@link ServuxDebug#log} + {@link ServuxDebug.Cat#EASYPLACE} 分类，
 * 受 {@code /servux debug cat easyplace} 控制（与总开关正交）。servux 已无任何无分类 debug 入口（旧 ServuxLog 已删）——
 * 那样 {@code cat none} 后只要 master 开着仍会刷屏。
 *
 * <p><b>ack 命门</b>：1.19+ 的 {@code use_item_on} 带 sequence，原版处理后回 {@code block_changed_ack}。
 * 取消包后原版不回 ack，故接管路径<b>必须</b>手动 {@code send(ClientboundBlockChangedAckPacket)}，否则客户端
 * 会因等待 ack 而延迟应用后续块变更。ack 与放置成败无关（原版成功/失败都回）。
 *
 * <p><b>依赖</b>：运行时需服务器安装 PacketEvents 插件（{@code plugin.yml: depend: [packetevents]}）。
 */
public class EasyPlaceListener implements PacketListener
{
    @Override
    public void onPacketReceive(PacketReceiveEvent event)
    {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) { return; }

        Player bukkitPlayer = event.getPlayer();
        if (bukkitPlayer == null) { return; }
        ServerPlayer player = Nms.toNms(bukkitPlayer);

        // 无 EasyPlace 权限 → 放行原版包（走默认放置）
        if (!ConfigProvider.INSTANCE.hasPermission_EasyPlace(player)) { return; }

        WrapperPlayClientPlayerBlockPlacement pkt = new WrapperPlayClientPlayerBlockPlacement(event);

        // 手牌必须是 BlockItem（EasyPlace 只精确放置方块；其余放行原版）
        InteractionHand hand = toNms(pkt.getHand());
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) { return; }

        Vector3i bp = pkt.getBlockPosition();
        BlockPos pos = new BlockPos(bp.x, bp.y, bp.z);
        Vector3f cursor = pkt.getCursorPosition();

        // ★ EasyPlace V3 接管判定：只接管「已编码」包（见类注释「接管判定命门」）。
        int protocolValue = (int) cursor.x - 2;
        if (protocolValue < 0)
        {
            // 普通放置包（玩家正常右键 / 客户端未握手 servux 没启用 EasyPlace）→ 放行原版，
            // 不取消、不回 ack——交给原版 use_item_on 全流程处理（原版会回 ack）。
            return;
        }

        // —— 以下为 EasyPlace V3 已编码包：接管原版包处理 ——
        // 取消后原版不再处理此包，故后续（含失败路径）必须手动回 ack。
        event.setCancelled(true);

        Direction face = Direction.from3DDataValue(pkt.getFace().getFaceValue());
        Vec3 hitVec = new Vec3(pos.getX() + cursor.x, pos.getY() + cursor.y, pos.getZ() + cursor.z);
        final int sequence = pkt.getSequence();

        // ⚠ setBlock / setPlacedBy / playSound / shrink / ack 必须在世界 tick 线程（主线程）。
        // PacketEvents 的 onPacketReceive 在 Netty IO 线程触发，直接调 level.setBlock 会被 Paper
        // AsyncCatcher 拦截（"block onPlace" 主线程检查）。取消包后调度到下一 tick 主线程执行放置。
        Bukkit.getScheduler().runTask(Reference.plugin(), () ->
        {
            try
            {
                ServerLevel level = (ServerLevel) player.level();

                // 基础状态用 defaultBlockState：PlacementHandler 会用 protocolValue 修正 facing + 全部白名单属性。
                // （原版 MixinBlockItem 用 getStateForPlacement(ctx) 作基础，但 EasyPlace 语义是客户端已精确决定
                //  所有属性，defaultBlockState + PlacementHandler 解码即可覆盖；canSurvive validator 兜底挡非法放置。）
                BlockState baseState = blockItem.getBlock().defaultBlockState();
                PlacementHandler.UseContext ctx = new PlacementHandler.UseContext(
                        level, pos, face, hitVec, player, hand, null);

                BlockState finalState = PlacementHandler.applyPlacementProtocolV3(baseState, ctx);
                ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "in pv=" + protocolValue + " cursor=" + cursor.x + "," + cursor.y + "," + cursor.z
                        + " face=" + face + " pos=" + pos + " base=" + baseState);
                ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "out final=" + finalState);

                // 放置目标必须可替换（原版 BlockItem.place 的 canPlace 语义；Tweakeroo 已选定可放置位置，正常为空气）。
                boolean replaceOk = level.getBlockState(pos).canBeReplaced();

                if (finalState != null && replaceOk && finalState.canSurvive(level, pos))
                {
                    level.setBlock(pos, finalState, Block.UPDATE_ALL);
                    // setPlacedBy：初始化方块实体（箱子内容/告示牌文本/信标等）；无 BE 的方块为 no-op。
                    try { blockItem.getBlock().setPlacedBy(level, pos, finalState, player, stack); }
                    catch (Exception ignored) { }
                    playPlaceSound(level, pos, finalState);
                    // 物品消耗（非创造）
                    if (!player.getAbilities().instabuild) { stack.shrink(1); }
                    ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "放置 " + finalState + " @ " + pos);
                }
                else
                {
                    ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "拒绝放置 @ " + pos
                            + " (" + (finalState == null ? "validator=null"
                                      : !replaceOk ? "target-not-replaceable" : "canSurvive-fail") + ")");
                }
            }
            catch (Exception ex)
            {
                ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "异常 @ " + pos + ": " + ex.getMessage());
            }
            finally
            {
                // 无论放置成败，都回 ack（原版成功/失败都回；不回会卡客户端块变更预测）
                ack(player, sequence);
            }
        });
    }

    private static void ack(ServerPlayer player, int sequence)
    {
        try { player.connection.send(new ClientboundBlockChangedAckPacket(sequence)); }
        catch (Exception ignored) { }
    }

    private static void playPlaceSound(ServerLevel level, BlockPos pos, BlockState state)
    {
        try
        {
            SoundType st = state.getSoundType();
            level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    st.getPlaceSound(), SoundSource.BLOCKS, (st.getVolume() + 1.0F) / 2.0F, st.getPitch() * 0.8F);
        }
        catch (Exception ignored) { }
    }

    private static InteractionHand toNms(com.github.retrooper.packetevents.protocol.player.InteractionHand hand)
    {
        return hand == com.github.retrooper.packetevents.protocol.player.InteractionHand.OFF_HAND
                ? InteractionHand.OFF_HAND
                : InteractionHand.MAIN_HAND;
    }
}
