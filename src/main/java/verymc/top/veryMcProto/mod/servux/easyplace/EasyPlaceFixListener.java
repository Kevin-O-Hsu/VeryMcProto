package verymc.top.veryMcProto.mod.servux.easyplace;

import java.util.UUID;

import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCanBuildEvent;
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
import net.minecraft.world.phys.shapes.CollisionContext;

import verymc.top.veryMcProto.framework.nms.Nms;
import verymc.top.veryMcProto.mod.servux.ServuxDebug;
import verymc.top.veryMcProto.mod.servux.dataproviders.ConfigProvider;
import verymc.top.veryMcProto.mod.servux.util.PlacementHandler;

/**
 * EasyPlace 双挂点修正钩子（mod 层，Bukkit 事件）——「改写放行」范式的收口半段。
 *
 * <p>上游原版用 Mixin 注入 {@code BlockItem.getPlacementState} HEAD（{@code cancellable}）：拒绝时
 * {@code setReturnValue(null)} → vanilla {@code place} 在 {@code placeBlock} 之前整次 FAIL（无方块、
 * 不消耗、无音效/gameEvent/stat）；放行时写入前改态、恰好 1 次 setBlock。Paper 无 Mixin，本移植把
 * 同一语义拆成两个主线程挂点：
 *
 * <ul>
 *   <li>{@link #onBlockCanBuild}（<b>写入前否决</b>）：{@code BlockCanBuildEvent} fire 于
 *       {@code BlockItem.canPlace} 体内（{@code getPlacementState} 调用链内，先于 setBlock/音效/
 *       gameEvent/消耗/进度全部副作用）。事件携带的 {@code BlockData} 即 vanilla 候选态
 *       {@code stateForPlacement.asBlockData()}（26.1.2 字节码实证：canPlace :83-136 内
 *       asBlockData :98-99 / callEvent :126 / ireturn isBuildable :136）——与上游 Mixin 注入点
 *       的 {@code stateOrig} 同位等价物，判定世界为放置前状态（与上游完全同位）。否决
 *       {@code setBuildable(false)} → {@code getPlacementState} 返回 null → vanilla 写入前 FAIL，
 *       与上游 {@code setReturnValue(null)} 同位同效、零残差。</li>
 *   <li>{@link #onBlockPlace}（<b>纯写入</b>）：{@code BlockPlaceEvent} fire 于
 *       {@code ItemStack.useOn} 内全部放置副作用之后（capture 关闭后，26.1.2 useOn :604 与 26.2 同构）。
 *       vanilla 已按主线程队列语义完成放置 / 消耗 / ack，本监听器仅消费 canBuild 暂存的判定结果做<b>属性级</b>修正
 *       （facing / half / type 等白名单属性，同方块同 BE，无副作用回放）。</li>
 * </ul>
 *
 * <p><b>权限时点</b>：权限门在两挂点（主线程）而非 netty 侧——netty 侧对玩家状态零读取是本范式
 * 消除「手持 desync」竞态的结构保证（权限缓存 /LuckPerms 等同为玩家态，一并避开）。
 *
 * <p><b>已知与上游的差异</b>（有意接受，详见 docs/07 降级矩阵与 docs/09 差异表）：
 * <ul>
 *   <li><b>修正态实体碰撞 / canSurvive 补查恒生效</b>（锚点要求）：canBuild 拒绝判定（canSurvive +
 *       {@code checkEntityCollision}，后者与 vanilla {@code canPlace} 逐字同构）不受
 *       {@code easy_place_validator_enabled} 门控——validator 关闭时上游连基座检查都旁路
 *       （Mixin {@code @At("HEAD") cancellable} 整体替换方法体），我方恒查，<b>比上游严</b>。</li>
 *   <li><b>vanilla 基座检查失败时保守不动</b>：canBuild 事件 buildable 初值 false（vanilla 候选态
 *       检查失败）时我方不 override 放行——validator 开启时与上游一致（上游 Mixin :48 对原态查
 *       {@code canPlace}），关闭时并入上条「我方更严」方向。</li>
 *   <li>双半格方块（门族 / 垂滴叶族，{@code DoubleBlockHalf} 计数体系）：{@code BlockMultiPlaceEvent}
 *       extends {@code BlockPlaceEvent} 且无自有 HandlerList，经父类派发<b>本监听器实际收到</b>。
 *       下半格修正后以修正态重演 {@code setPlacedBy} 派生上半格，两半构造性一致；不依赖
 *       {@code updateShape} 邻居自愈——该链受 BlockPhysicsEvent 取消门控。</li>
 *   <li>床（{@code BedPart} 计数体系）：编码朝向==vanilla 时不修正；≠vanilla 且目标头位被占则
 *       canBuild 拒绝（整次 FAIL，与上游一致）；≠vanilla 且目标头位可替换则仅 FOOT 被修正、床头
 *       仍留 vanilla 相对位 → 两半错位（上游为写入前整体改向、头随脚走；事件后无法无损重构，
 *       已知差异——{@code PlacementHandler} 床头检查 NPE 修复后该差异从不可达变为现实）。</li>
 *   <li>canBuild→place 间隙（同 tick）内 capture 窗口事件监听器（BlockPhysics / 进度触发 /
 *       gameEvent 接收端）理论上可扰动事件位世界态——place 侧 setBlock 前的 canSurvive 复查失败
 *       时降级保留 vanilla 态（并入既有登记，不另立）。</li>
 *   <li>{@code itemPlacementContext} 恒 null（无 Mixin 取不到 NMS {@code BlockPlaceContext}）——
 *       {@code PlacementHandler} 床头可替换检查已改为事件前世界态等价判定（见该类注释），
 *       非床方块与上游等价；</li>
 *   <li>物品 BLOCK_STATE 组件（创造 pick-block）在 canBuild 之后、placeBlock 之前改写最终落块态
 *       （vanilla {@code updateBlockStateFromTag}），我方修正会覆盖组件态——极边缘（vanilla 自身
 *       同样不复查组件态），登记。</li>
 *   <li>{@code EasyPlacePending} TTL 1000ms 窗口内，同玩家同位置的 stale 条目理论上可命中后续
 *       手动放置（canBuild 误判 / place 误修正）——罕见、自愈、有界（TTL 兜底 + register 覆盖）。</li>
 *   <li>事件派发内直接 {@code setBlock}（下半修正 / 双半格上半派生）不在 CraftBukkit 捕获快照内：
 *       若事件被更晚优先级的监听器取消，{@code revertPlace} 按捕获位写回快照会覆盖这些直写——
 *       与既有单格修正同类的取舍，非新增风险。</li>
 * </ul>
 */
public class EasyPlaceFixListener implements Listener
{
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockCanBuild(BlockCanBuildEvent event)
    {
        // 发射器等非 ServerPlayer 放置路径事件不带 player（canPlace 字节码 instanceof 分支）——零拦截。
        if (event.getPlayer() == null) { return; }

        UUID uuid = event.getPlayer().getUniqueId();

        // pending key = 编码包的放置目标（包 position）；vanilla 因目标被抢占而偏移放置位时
        // （BlockPlaceContext.replaceClicked=false → relative(face)）此处 miss → 安全降级不否决。
        BlockPos pos = new BlockPos(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
        EasyPlacePending.Pending pending = EasyPlacePending.peek(uuid, pos.asLong());
        if (pending == null) { return; }

        // 权限门（主线程）：无权限则不动 buildable、不暂存——vanilla 语义完整保留。
        ServerPlayer player = Nms.toNms(event.getPlayer());
        if (!ConfigProvider.INSTANCE.hasPermission_EasyPlace(player)) { return; }

        ServerLevel level = (ServerLevel) player.level();
        int protocolValue = pending.protocolValue();

        // candidate = vanilla 候选态（事件 BlockData 即 stateForPlacement.asBlockData()，26.1.2 字节码
        // 实证：canPlace :83-136 内 asBlockData :98-99 / callEvent :126 / isBuildable :136）——判定世界
        // 为放置前状态，与上游 Mixin 注入点的 getStateForPlacement 基座同位等价。
        BlockState candidate = ((CraftBlockData) event.getBlockData()).getState();

        // hitVec.x = pos.x + 2 + pv：解码器入口 (int)(hitVec.x − pos.x) − 2 重推出同一 pv；
        // side / hand / hitVec.y / hitVec.z 为解码器零引用字段。
        PlacementHandler.UseContext ctx = new PlacementHandler.UseContext(
                level, pos, null,
                new Vec3(pos.getX() + 2 + protocolValue, 0, 0),
                player, null, null);

        BlockState finalState = PlacementHandler.applyPlacementProtocolV3(candidate, ctx);
        ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "in pv=" + protocolValue + " pos=" + pos + " vanilla=" + candidate);
        ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "out final=" + finalState);

        // 拒绝判定（不受 validator 开关门控——补查为锚点字面要求）：null = validator / 床头拒绝；
        // canSurvive + 实体碰撞（与 vanilla canPlace 逐字同构，checkEntityCollision 的
        // checkCanSee 分支对 vanish 玩家的语义与 isUnobstructed 不同，勿回退）。
        boolean reject = finalState == null
                || !finalState.canSurvive(level, pos)
                || !level.checkEntityCollision(finalState, player, CollisionContext.placementContext(player), pos, true);

        if (reject)
        {
            // 与上游 setReturnValue(null) 同位同效：place 在 placeBlock 之前整次 FAIL——无方块、
            // 不消耗、无音效、无 gameEvent、无 stat。同步移除 pending（防 stale 条目在 TTL 窗口内
            // 误拒后续手动放置）。
            event.setBuildable(false);
            EasyPlacePending.take(uuid, pos.asLong());
            ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "拒绝放置（写入前 FAIL，对齐上游）@ " + pos);
            return;
        }

        // 放行：暂存判定结果（== candidate 时置 null 作 no-op 标记），由 place 挂点消费。
        pending.stash().set(finalState == candidate ? null : finalState);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event)
    {
        UUID uuid = event.getPlayer().getUniqueId();

        // pending key = 编码包的放置目标（包 position）；与 canBuild 挂点同 miss 逻辑（偏移即降级）。
        BlockPos pos = new BlockPos(event.getBlockPlaced().getX(), event.getBlockPlaced().getY(), event.getBlockPlaced().getZ());
        EasyPlacePending.Pending pending = EasyPlacePending.take(uuid, pos.asLong());
        if (pending == null) { return; }

        // stash == null 的两个来源（行为同归：不写入，vanilla 结果保留）：
        // 1) canBuild 阶段 no-op（编码值与候选态一致）或无权限早退——常规可达路径；
        // 2) canBuild 未走过的病态路径（防御式，正常流程 canBuild 必先于本事件 fire）。
        BlockState finalState = pending.stash().get();
        if (finalState == null)
        {
            ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "无需修正（no-op / 无权限 / 病态路径）@ " + pos);
            return;
        }

        ServerPlayer player = Nms.toNms(event.getPlayer());
        ServerLevel level = (ServerLevel) player.level();

        // 写入前 canSurvive 复查：闭合 canBuild→place 间隙（同 tick）内 capture 窗口事件监听器
        // 对事件位世界态的病态扰动面；失败降级保留 vanilla 态（并入既有登记）。
        if (!finalState.canSurvive(level, pos))
        {
            ServuxDebug.log(ServuxDebug.Cat.EASYPLACE, "修正态 canSurvive 复查失败（病态扰动），保留 vanilla 状态 @ " + pos);
            return;
        }

        level.setBlock(pos, finalState, Block.UPDATE_ALL_IMMEDIATE);
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
