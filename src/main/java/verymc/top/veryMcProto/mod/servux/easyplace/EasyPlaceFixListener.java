package verymc.top.veryMcProto.mod.servux.easyplace;

import java.util.UUID;

import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import verymc.top.veryMcProto.framework.nms.Nms;
import verymc.top.veryMcProto.mod.servux.ServuxDebug;
import verymc.top.veryMcProto.mod.servux.dataproviders.ConfigProvider;
import verymc.top.veryMcProto.mod.servux.util.PlacementHandler;

/**
 * EasyPlace 放置修正钩子（mod 层，Bukkit 事件）——「改写放行」范式的收口半段。
 *
 * <p>上游原版用 Mixin 注入 {@code BlockItem.getPlacementState}（写入前改态、恰好 1 次 setBlock）；
 * Paper 无 Mixin，且 CraftBukkit 的 {@code BlockCanBuildEvent} 丢弃事件 BlockData（无写入前改态通道），
 * 故本移植把修正放在<b>写入后</b>的 {@link BlockPlaceEvent}：vanilla 已按主线程队列语义完成
 * 手持读取 / 距离与保护检查 / 放置 / setPlacedBy / BE 初始化 / 物品消耗 / ack，本监听器仅对
 * 已落块状态做<b>属性级</b>修正（facing / half / type 等白名单属性，同方块同 BE，无副作用回放）。
 *
 * <p><b>时序依据</b>（26.1.2 dev bundle 实证）：{@code ItemStack.useOn} 在 capture 关闭后、
 * 事件后通知循环前 fire 本事件——事件内 {@code level.getBlockState(pos)} 即 vanilla 真实落块状态
 * （= 上游 {@code getStateForPlacement} 基座的等价物，含玩家朝向 / 点击面上下文），事件内
 * {@code level.setBlock} 为正常全量写（自带广播与邻居通知），后续 vanilla 通知循环现读世界、
 * 以修正后状态收尾，无覆盖冲突。
 *
 * <p><b>权限时点</b>：权限门在此（主线程）而非 netty 侧——netty 侧对玩家状态零读取是本范式
 * 消除「手持 desync」竞态的结构保证（权限缓存 /LuckPerms 等同为玩家态，一并避开）。
 *
 * <p><b>已知与上游的差异</b>（有意接受，详见 docs/07 降级矩阵）：
 * <ul>
 *   <li>双半格方块（门族 / 垂滴叶族，{@code DoubleBlockHalf} 计数体系）：{@code BlockMultiPlaceEvent}
 *       extends {@code BlockPlaceEvent} 且无自有 HandlerList，经父类派发<b>本监听器实际收到</b>。
 *       下半格修正后以修正态重演 {@code setPlacedBy} 派生上半格（见 {@link #onBlockPlace} 尾段），
 *       两半构造性一致、与上游「写入前改态后走同一派生」同构；不依赖 {@code updateShape} 邻居
 *       自愈——该链受 BlockPhysicsEvent 取消门控，且垂滴叶族无自愈复制语义。</li>
 *   <li>床（{@code BedPart} 计数体系，不走上段派生）：编码朝向==vanilla 时不修正；≠vanilla 且
 *       目标头位被占则 {@code PlacementHandler} 床头检查拒绝（保留 vanilla 床，两半自洽）；
 *       ≠vanilla 且目标头位可替换则仅 FOOT 被修正、床头仍留 vanilla 相对位 → 两半错位（上游为
 *       写入前整体改向、头随脚走；事件后无法无损重构，已知差异）。</li>
 *   <li>{@code itemPlacementContext} 恒 null（无 Mixin 取不到 NMS {@code BlockPlaceContext}），
 *       仅影响 {@code PlacementHandler} 的床头可替换检查分支，非床方块与上游等价；</li>
 *   <li>修正基座继承 vanilla {@code canPlace}（含实体碰撞检查）——上游 Mixin 默认 validator
 *       开启时行为等价；仅管理员显式关闭 validator 时上游更宽：其 {@code @At("HEAD") cancellable}
 *       注入以 {@code setReturnValue} 取消整个 {@code getPlacementState} 方法体，连带绕过体内
 *       canPlace（含实体碰撞）；我方基座恒为 vanilla 全量检查，无此绕过。</li>
 *   <li>事件派发内直接 {@code setBlock}（下半修正 / 双半格上半派生）不在 CraftBukkit 捕获快照内：
 *       若事件被更晚优先级的监听器取消，{@code revertPlace} 按捕获位写回快照会覆盖这些直写
 *       （下半 / 上半均为捕获位）——与既有单格修正同类的取舍，非新增风险。</li>
 * </ul>
 */
public class EasyPlaceFixListener implements Listener
{
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event)
    {
        UUID uuid = event.getPlayer().getUniqueId();

        // pending key = 编码包的放置目标（包 position）；vanilla 因目标被抢占而偏移放置位时
        // （BlockPlaceContext.replaceClicked=false → relative(face)）此处 miss → 安全降级不修正。
        BlockPos pos = new BlockPos(event.getBlockPlaced().getX(), event.getBlockPlaced().getY(), event.getBlockPlaced().getZ());
        EasyPlacePending.Pending pending = EasyPlacePending.take(uuid, pos.asLong());
        if (pending == null) { return; }

        int protocolValue = pending.protocolValue();

        // 权限门（主线程）：无权限则不修正，vanilla 默认放置结果保留。
        ServerPlayer player = Nms.toNms(event.getPlayer());
        if (!ConfigProvider.INSTANCE.hasPermission_EasyPlace(player)) { return; }

        ServerLevel level = (ServerLevel) player.level();
        BlockState vanillaState = level.getBlockState(pos);

        // hitVec.x = pos.x + 2 + pv：解码器入口 (int)(hitVec.x − pos.x) − 2 重推出同一 pv；
        // side / hand / hitVec.y / hitVec.z 为解码器零引用字段。
        PlacementHandler.UseContext ctx = new PlacementHandler.UseContext(
                level, pos, null,
                new Vec3(pos.getX() + 2 + protocolValue, 0, 0),
                player, null, null);

        BlockState finalState = PlacementHandler.applyPlacementProtocolV3(vanillaState, ctx);
        ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "in pv=" + protocolValue + " pos=" + pos + " vanilla=" + vanillaState);
        ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "out final=" + finalState);

        if (finalState == null)
        {
            // validator 拒绝（修正后状态 canSurvive 失败）：保留 vanilla 已放置状态，不修正。
            ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "拒绝修正 @ " + pos + " (validator=null，保留 vanilla 状态)");
            return;
        }

        if (finalState == vanillaState)
        {
            // 编码值与 vanilla 结果一致（如竖直原木 axis=y），无需修正。
            ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "无需修正（编码值与 vanilla 一致）@ " + pos);
            return;
        }

        level.setBlock(pos, finalState, Block.UPDATE_ALL);
        ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "修正放置 " + finalState + " @ " + pos);

        // 双半格（门族/垂滴叶族）：上半格在事件前已由 vanilla setPlacedBy 按 vanilla 态写入，
        // 以修正态重演同一派生恢复两半一致——不依赖 updateShape 邻居自愈（受 BlockPhysicsEvent
        // 取消门控，垂滴叶族无自愈复制语义）。床走 BedPart 计数体系，不在此列（见类注释）。
        if (finalState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) &&
            finalState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER)
        {
            finalState.getBlock().setPlacedBy(level, pos, finalState, player,
                    CraftItemStack.asNMSCopy(event.getItemInHand()));
            ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "双半格上半派生 @ " + pos.above());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        EasyPlacePending.clear(event.getPlayer().getUniqueId());
    }
}
