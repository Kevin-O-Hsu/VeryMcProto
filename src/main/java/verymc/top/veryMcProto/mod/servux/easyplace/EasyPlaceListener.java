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
import verymc.top.veryMcProto.framework.nms.Nms;
import verymc.top.veryMcProto.mod.servux.ServuxLog;
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
 * （{@code hitVec.x = pos.x + relX + 2 + protocolValue}）。PacketEvents 的
 * {@link WrapperPlayClientPlayerBlockPlacement#getCursorPosition()} 返回该 hitVec 相对方块的偏移
 * （{@code cursor.x = hitVec.x - pos.x}），故 {@code protocolValue = (int) cursor.x - 2}，与
 * {@link PlacementHandler} 的 {@code (int)(hitVec.x - pos.x) - 2} 完全对应。
 *
 * <p><b>权限</b>：仅当玩家通过 {@code servux.main.easy_place}（{@link ConfigProvider#hasPermission_EasyPlace}）
 * 时才接管；否则放行原版包走默认放置。物品非 BlockItem 时也放行（EasyPlace 只精确放置方块）。
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

        // —— 接管分割线：取消原版包，后续逻辑由本类完成（含失败路径也必须回 ack）——
        event.setCancelled(true);

        ServerLevel level = (ServerLevel) player.level();
        Vector3i bp = pkt.getBlockPosition();
        BlockPos pos = new BlockPos(bp.x, bp.y, bp.z);
        Direction face = Direction.from3DDataValue(pkt.getFace().getFaceValue());
        Vector3f cursor = pkt.getCursorPosition();
        Vec3 hitVec = new Vec3(pos.getX() + cursor.x, pos.getY() + cursor.y, pos.getZ() + cursor.z);
        final int sequence = pkt.getSequence();

        // ⚠ setBlock / setPlacedBy / playSound / shrink / ack 必须在世界 tick 线程（主线程）。
        // PacketEvents 的 onPacketReceive 在 Netty IO 线程触发，直接调 level.setBlock 会被 Paper
        // AsyncCatcher 拦截（"block onPlace" 主线程检查）。取消包后调度到下一 tick 主线程执行放置。
        Bukkit.getScheduler().runTask(Reference.plugin(), () ->
        {
            try
            {
                // 基础状态用 defaultBlockState：PlacementHandler 会用 protocolValue 修正 facing + 全部白名单属性。
                // （原版 MixinBlockItem 用 getStateForPlacement(ctx) 作基础，但 EasyPlace 语义是客户端已精确决定
                //  所有属性，defaultBlockState + PlacementHandler 解码即可覆盖；canSurvive validator 兜底挡非法放置。）
                BlockState baseState = blockItem.getBlock().defaultBlockState();
                PlacementHandler.UseContext ctx = new PlacementHandler.UseContext(
                        level, pos, face, hitVec, player, hand, null);

                BlockState finalState = PlacementHandler.applyPlacementProtocolV3(baseState, ctx);

                if (finalState != null && finalState.canSurvive(level, pos))
                {
                    level.setBlock(pos, finalState, Block.UPDATE_ALL);
                    // setPlacedBy：初始化方块实体（箱子内容/告示牌文本/信标等）；无 BE 的方块为 no-op。
                    try { blockItem.getBlock().setPlacedBy(level, pos, finalState, player, stack); }
                    catch (Exception ignored) { }
                    playPlaceSound(level, pos, finalState);
                    // 物品消耗（非创造）
                    if (!player.getAbilities().instabuild) { stack.shrink(1); }
                    ServuxLog.debug("EasyPlace 放置 " + finalState + " @ " + pos);
                }
                else
                {
                    ServuxLog.debug("EasyPlace 拒绝放置 @ " + pos
                            + " (" + (finalState == null ? "validator=null" : "canSurvive-fail") + ")");
                }
            }
            catch (Exception ex)
            {
                ServuxLog.debug("EasyPlace 异常 @ " + pos + ": " + ex.getMessage());
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
