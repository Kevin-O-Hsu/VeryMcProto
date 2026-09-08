package verymc.top.veryMcProto.mod.servux.scheduler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import verymc.top.veryMcProto.mod.servux.schematic.selection.Box;
import verymc.top.veryMcProto.mod.servux.util.EntityUtils;
import verymc.top.veryMcProto.mod.servux.util.IntBoundingBox;
import verymc.top.veryMcProto.mod.servux.util.WorldUtils;
import verymc.top.veryMcProto.mod.servux.util.position.PositionUtils;

/**
 * Litematica Fill/Delete 选区任务（26.1 移植，v3 合并形态；timer / 队列 / 推帧 / 完成链共享面已上提
 * {@link LitematicaTask}——paste 任务化时基类化，B/CF 轮终审裁定）。
 *
 * <p>合并上游五个类：{@code TaskBase + TaskProcessChunkBase + TaskProcessChunkMultiPhase + TaskFillArea +
 * TaskDeleteArea}（Delete = fillState=AIR 的 Fill，上游 TaskDeleteArea.java:11-16 同构）。行为真值逐项对照：
 * <ul>
 *   <li>盒子 → 分区块队列：{@link PositionUtils#getPerChunkBoxes} + 世界高度钳制 + 最近优先排序；每轮 fetch 前
 *       re-sort（MultiPhase:187）；</li>
 *   <li>每 tick <b>25ms 纳秒固定预算</b>（MultiPhase:93）+ 无进展即退出 + currentChunkPos 预检短路
 *       （MultiPhase:78-96 照抄，<b>不得</b>写成裸 while(&lt;25ms)）——与 PasteTask 的 vanillaTickTime+60ms
 *       动态预算（Direct 路径）刻意不同源，勿"顺手统一"；</li>
 *   <li>{@link #execute()} 逐行照抄 TaskFillArea:74-114：z 外 / x 中 / <b>y 内层降序</b>、三态替换条件、
 *       容器先 clearContent + barrier(0x32)、setBlock flags 0x32（无 UPDATE_NEIGHBORS，上游 Mixin 更新抑制的
 *       等效载体）、RemoveEntities 走 AABB 非玩家 discard；</li>
 *   <li>进度推送：仅进度变化（processedChunksThisTick&gt;0）或首推（initialInfoSync）才发 type 16，
 *       且 pendingChunks 为空时全程零进度帧（TaskBase:151 守卫）；FINISHED 提前 return 不推末帧
 *       （MultiPhase:116-120）——与 PasteTask 的每 tick 无条件推帧（Direct:98）刻意不同源；</li>
 *   <li>完成链顺序（帧先于聊天、feedback 门控读活值）见基类 {@link LitematicaTask#stop()}。</li>
 * </ul>
 *
 * <p><b>有意偏差（相对上游，B 轮终审裁定）</b>：裁掉 sendCommand 死机制与 SEND_COMMAND_FEEDBACK gamerule
 * 翻转（对不发命令的任务零可观测效果，MultiPhase:259 零调用点）；玩家退出<b>不取消</b>任务（上游跑完语义，
 * 保证世界方块结果一致性）；中断终行文案随基类化由恒 completed 修正为 finished 条件（对齐上游
 * TaskFeedbackListener:75 aborted 行——Fill/Delete 的中断仅插件停用 clearTasks 路径可达，见 docs/09 §26.1.5）。
 */
public class FillDeleteTask extends LitematicaTask
{
    // ───── 上游 TaskFeedbackListener 的消息文案（servux en_us.json 原文）─────
    static final String MSG_FILL_SUCCESSFUL = "§aServux Task: Area filled§r";
    static final String MSG_FILL_INTERRUPTED = "§cServux Task: Area fill aborted or interrupted§r";
    static final String MSG_DELETE_SUCCESSFUL = "§aServux Task: Area deleted§r";
    static final String MSG_DELETE_INTERRUPTED = "§cServux Task: Area deletion aborted or interrupted§r";

    private final BlockState fillState;
    @Nullable private final BlockState replaceState;
    private final boolean removeEntities;

    private final Map<ChunkPos, List<IntBoundingBox>> boxesInChunks = new HashMap<>();

    private boolean initialInfoSync = true;
    @Nullable private ChunkPos currentChunkPos;

    private int processedChunksThisTick;
    private long taskStartTimeForCurrentTick;

    public FillDeleteTask(String name,
                          MinecraftServer server,
                          ServerLevel level,
                          ServerPlayer player,
                          List<Box> boxes,
                          BlockState fillState,
                          @Nullable BlockState replaceState,
                          boolean removeEntities)
    {
        super(name, server, level, player, System.currentTimeMillis());

        this.fillState = fillState;
        this.replaceState = replaceState;
        this.removeEntities = removeEntities;

        this.addPerChunkBoxes(boxes);
    }

    // ───── 执行主循环（上游 MultiPhase.executeMultiPhase 照抄，裁 sendCommand 维度）─────

    @Override
    boolean execute()
    {
        this.taskStartTimeForCurrentTick = System.nanoTime();
        this.processedChunksThisTick = 0;

        // currentChunkPos 预检短路（MultiPhase:78-82）：当前区块未加载 → 本 tick 直接退出（无进展）
        if (this.currentChunkPos != null && this.canProcessChunk(this.currentChunkPos) == false)
        {
            return false;
        }

        int processedChunksLast = -1;

        // 无进展即退出（MultiPhase:87-88：sentCommands/processChunks 双计数退化为单计数）+ 25ms 预算（:93-96）
        while (this.processedChunksThisTick != processedChunksLast)
        {
            long elapsedTickTime = System.nanoTime() - this.taskStartTimeForCurrentTick;

            if (elapsedTickTime >= 25_000_000L)
            {
                break;
            }

            processedChunksLast = this.processedChunksThisTick;

            this.fetchNextChunk();

            if (this.finished)
            {
                // FINISHED 提前 return，不推末帧（MultiPhase:116-120）
                return true;
            }
        }

        if (this.processedChunksThisTick > 0)
        {
            this.updateInfoHudLines();
        }
        else if (this.initialInfoSync)
        {
            this.updateInfoHudLines();
            this.initialInfoSync = false;
        }

        return false;
    }

    // ───── 区块队列（上游 TaskProcessChunkBase 照抄，裁 LayerRange 分支——Fill/Delete 恒 ALL）─────

    private void addPerChunkBoxes(List<Box> allBoxes)
    {
        this.boxesInChunks.clear();
        this.pendingChunks.clear();

        PositionUtils.getPerChunkBoxes(allBoxes, this::clampToWorldHeightAndAddBox);
        this.pendingChunks.addAll(this.boxesInChunks.keySet());
        this.sortChunkList();
    }

    private void clampToWorldHeightAndAddBox(ChunkPos pos, IntBoundingBox box)
    {
        IntBoundingBox clamped = PositionUtils.clampBoxToWorldHeightRange(box, this.level);

        if (clamped != null)
        {
            this.boxesInChunks.computeIfAbsent(pos, k -> new java.util.ArrayList<>()).add(clamped);
        }
    }

    private void fetchNextChunk()
    {
        if (this.pendingChunks.isEmpty() == false)
        {
            this.sortChunkList();

            ChunkPos pos = this.pendingChunks.getFirst();

            if (this.canProcessChunk(pos))
            {
                this.currentChunkPos = pos;
                this.directFillBoxesInChunk(pos);
            }
        }
        else
        {
            this.finished = true;
        }
    }

    private void directFillBoxesInChunk(ChunkPos pos)
    {
        for (IntBoundingBox box : this.boxesInChunks.getOrDefault(pos, List.of()))
        {
            this.directFillBox(box, this.removeEntities);
        }

        this.finishProcessingChunk(pos);
    }

    private void finishProcessingChunk(ChunkPos pos)
    {
        this.boxesInChunks.remove(pos);
        this.pendingChunks.remove(pos);
        this.currentChunkPos = null;
        ++this.processedChunksThisTick;
    }

    private boolean canProcessChunk(ChunkPos pos)
    {
        // TaskFillArea.canProcessChunk → areSurroundingChunksLoaded(radius 0) → hasChunk
        return this.level.getChunkSource().hasChunk(pos.x(), pos.z());
    }

    // ───── 世界写入（TaskFillArea.directFillBox :74-114 逐行照抄）─────

    private void directFillBox(IntBoundingBox box, boolean removeEntities)
    {
        if (removeEntities)
        {
            directRemoveEntities(box, this.level);
        }

        WorldUtils.setShouldPreventBlockUpdates(this.level, true);

        BlockState barrier = Blocks.BARRIER.defaultBlockState();
        BlockPos.MutableBlockPos posMutable = new BlockPos.MutableBlockPos();

        for (int z = box.minZ(); z <= box.maxZ(); ++z)
        {
            for (int x = box.minX(); x <= box.maxX(); ++x)
            {
                for (int y = box.maxY(); y >= box.minY(); --y)
                {
                    posMutable.set(x, y, z);
                    BlockState oldState = this.level.getBlockState(posMutable);

                    if ((this.replaceState == null && oldState != this.fillState) || oldState == this.replaceState)
                    {
                        BlockEntity te = this.level.getBlockEntity(posMutable);

                        if (te instanceof Container)
                        {
                            ((Container) te).clearContent();
                            this.level.setBlock(posMutable, barrier, 0x32);
                        }

                        this.level.setBlock(posMutable, this.fillState, 0x32);
                    }
                }
            }
        }

        WorldUtils.setShouldPreventBlockUpdates(this.level, false);
    }

    private static void directRemoveEntities(IntBoundingBox box, ServerLevel level)
    {
        AABB aabb = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
        List<Entity> entities = level.getEntities((Entity) null, aabb, EntityUtils.NOT_PLAYER);

        for (Entity entity : entities)
        {
            if ((entity instanceof Player) == false)
            {
                entity.discard();
            }
        }
    }

    @Override
    String successMessage()
    {
        return "Fill".equals(this.name) ? MSG_FILL_SUCCESSFUL : MSG_DELETE_SUCCESSFUL;
    }

    @Override
    String interruptedMessage()
    {
        return "Fill".equals(this.name) ? MSG_FILL_INTERRUPTED : MSG_DELETE_INTERRUPTED;
    }
}
