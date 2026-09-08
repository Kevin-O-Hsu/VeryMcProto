package verymc.top.veryMcProto.mod.servux.scheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

import verymc.top.veryMcProto.mod.servux.dataproviders.LitematicsDataProvider;
import verymc.top.veryMcProto.mod.servux.schematic.selection.Box;
import verymc.top.veryMcProto.mod.servux.util.EntityUtils;
import verymc.top.veryMcProto.mod.servux.util.IntBoundingBox;
import verymc.top.veryMcProto.mod.servux.util.WorldUtils;
import verymc.top.veryMcProto.mod.servux.util.position.PositionUtils;

/**
 * Litematica Fill/Delete 选区任务（26.1 移植，v3 合并形态）。
 *
 * <p>合并上游五个类：{@code TaskBase + TaskProcessChunkBase + TaskProcessChunkMultiPhase + TaskFillArea +
 * TaskDeleteArea}（Delete = fillState=AIR 的 Fill，上游 TaskDeleteArea.java:11-16 同构）。行为真值逐项对照：
 * <ul>
 *   <li>盒子 → 分区块队列：{@link PositionUtils#getPerChunkBoxes} + 世界高度钳制 + 最近优先排序
 *       （参考点 = <b>构造时捕获的原 ServerPlayer 引用</b>——上游 TaskBase:38/156 的冻结语义：在线时活位置、
 *       重生/退出后冻结；仅发送路径改 UUID 解析）；每轮 fetch 前 re-sort（MultiPhase:187）；</li>
 *   <li>每 tick 25ms 纳秒预算 + 无进展即退出 + currentChunkPos 预检短路（MultiPhase:78-96 照抄，
 *       <b>不得</b>写成裸 while(&lt;25ms)）；</li>
 *   <li>{@link #directFillBox} 逐行照抄 TaskFillArea:74-114：z 外 / x 中 / <b>y 内层降序</b>、三态替换条件、
 *       容器先 clearContent + barrier(0x32)、setBlock flags 0x32（无 UPDATE_NEIGHBORS，上游 Mixin 更新抑制的
 *       等效载体）、RemoveEntities 走 AABB 非玩家 discard；</li>
 *   <li>进度推送：仅进度变化（processedChunksThisTick&gt;0）或首推（initialInfoSync）才发 type 16，
 *       且 pendingChunks 为空时全程零进度帧（TaskBase:151 守卫）；FINISHED 提前 return 不推末帧（MultiPhase:116-120）；</li>
 *   <li>完成链顺序（TaskFillArea.onStop → TaskProcessChunkBase.onStop → notifyListener）：
 *       ①完成/中断消息入缓冲 → ②<b>Complete 帧（type 16, InfoHudComplete=true）</b> → ③冲刷缓冲 +
 *       completed/aborted 行——<b>帧先于聊天</b>（客户端先移除 HUD renderer 再见消息）；completed 行独立于
 *       缓冲有无，feedback 门控读<b>活值</b>（LitematicsDataProvider.shouldSendPlayerTaskFeedback()）。</li>
 * </ul>
 *
 * <p><b>有意偏差（相对上游，B 轮终审裁定）</b>：裁掉 sendCommand 死机制与 SEND_COMMAND_FEEDBACK gamerule
 * 翻转（对不发命令的任务零可观测效果，MultiPhase:259 零调用点）；玩家句柄发送路径用 UUID 解析（respawn 安全；
 * 退出后解析为 null 即跳过发送 = 上游"发死连接静默丢弃"等效）；玩家退出<b>不取消</b>任务（上游跑完语义，
 * 保证世界方块结果一致性）。
 */
public class FillDeleteTask
{
    // ───── 上游 TaskFeedbackListener 的消息文案（servux en_us.json 原文）─────
    static final String MSG_FILL_SUCCESSFUL = "§aServux Task: Area filled§r";
    static final String MSG_FILL_INTERRUPTED = "§cServux Task: Area fill aborted or interrupted§r";
    static final String MSG_DELETE_SUCCESSFUL = "§aServux Task: Area deleted§r";
    static final String MSG_DELETE_INTERRUPTED = "§cServux Task: Area deletion aborted or interrupted§r";

    private final MinecraftServer server;
    private final ServerLevel level;
    private final UUID playerId;
    /** 构造时捕获的原始玩家引用：仅作区块排序参考点（上游冻结语义）；网络发送一律走 UUID 解析。 */
    private final ServerPlayer playerRef;
    private final String name;
    private final long startTime;

    private final BlockState fillState;
    @Nullable private final BlockState replaceState;
    private final boolean removeEntities;

    private final Map<ChunkPos, List<IntBoundingBox>> boxesInChunks = new HashMap<>();
    private final List<ChunkPos> pendingChunks = new ArrayList<>();
    private final PositionUtils.ChunkPosComparator chunkPosComparator = new PositionUtils.ChunkPosComparator();
    private final List<Component> feedbackBuffer = new ArrayList<>();

    private boolean initialInfoSync = true;
    private boolean finished;
    @Nullable private ChunkPos currentChunkPos;

    /** 重复执行周期（客户端 "Interval" 字段，26.1 恒 1）；初值 0 = 下一次 runTasks 即首启。 */
    private int tickCounter = 0;
    private int repeatInterval = 1;
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
        this.name = name;
        this.server = server;
        this.level = level;
        this.playerId = player.getUUID();
        this.playerRef = player;
        this.startTime = System.currentTimeMillis();
        this.fillState = fillState;
        this.replaceState = replaceState;
        this.removeEntities = removeEntities;

        this.chunkPosComparator.setReferencePosition(player.blockPosition());
        this.chunkPosComparator.setClosestFirst(true);
        this.addPerChunkBoxes(boxes);
    }

    public String getName() { return this.name; }
    public boolean isFinished() { return this.finished; }

    void setRepeatInterval(int interval) { this.repeatInterval = interval; }

    /** 定时器：--counter ≤ 0 触发并按重复周期复位（上游 TaskTimer + setNextDelay(0) 等价承载）。 */
    boolean isTimerTriggered()
    {
        if (--this.tickCounter <= 0)
        {
            this.tickCounter = this.repeatInterval;
            return true;
        }
        return false;
    }

    // ───── 执行主循环（上游 MultiPhase.executeMultiPhase 照抄，裁 sendCommand 维度）─────

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
            this.boxesInChunks.computeIfAbsent(pos, k -> new ArrayList<>()).add(clamped);
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

    private void sortChunkList()
    {
        if (this.pendingChunks.size() > 0)
        {
            this.pendingChunks.sort(this.chunkPosComparator);
        }
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

    // ───── 状态推送（TaskBase.updateInfoHudLinesPendingChunks :149-178 + InfoHudSync 组帧）─────

    private void updateInfoHudLines()
    {
        // TaskBase:151 守卫：无待处理区块则不发任何进度帧（全盒被钳出世界 → 直接走完成帧）
        if (this.pendingChunks.isEmpty())
        {
            return;
        }

        List<ChunkPos> list = new ArrayList<>(this.pendingChunks);

        // 参考点 = 构造时捕获的原引用的活位置（在线跟随、离线冻结——上游冻结语义）
        this.chunkPosComparator.setReferencePosition(BlockPos.containing(this.playerRef.position()));
        this.chunkPosComparator.setClosestFirst(true);
        list.sort(this.chunkPosComparator);

        ServerPlayer target = this.resolvePlayer();

        if (target != null)
        {
            LitematicsDataProvider.INSTANCE.onTaskStatusSync(target, InfoHudTaskSync.progressFrame(this.name, list));
        }
    }

    // ───── 完成链（TaskFillArea.onStop → TaskProcessChunkBase.onStop → notifyListener 顺序照抄）─────

    void stop()
    {
        // ① 完成/中断消息入缓冲（TaskFillArea.printCompletionMessage）
        this.feedbackBuffer.add(Component.literal(this.finished ? this.successMessage() : this.interruptedMessage()));

        ServerPlayer target = this.resolvePlayer();

        if (target == null)
        {
            // 玩家已退出（或 respawn 后 UUID 不可达）：上游"发死连接静默丢弃"等效——跳过帧与消息
            return;
        }

        // ② Complete 帧（InfoHudSync.onStop → onTaskCompleteInternal）：客户端移除 HUD renderer 的唯一信号
        LitematicsDataProvider.INSTANCE.onTaskStatusSync(target, InfoHudTaskSync.completeFrame());

        // ③ 冲刷缓冲 + completed/aborted 行（TaskFeedbackListener.onTaskCompleted/Aborted）——帧先于聊天
        boolean feedbackEnabled = LitematicsDataProvider.INSTANCE.shouldSendPlayerTaskFeedback();

        if (feedbackEnabled)
        {
            for (Component line : this.feedbackBuffer)
            {
                target.sendSystemMessage(line);
            }
        }
        this.feedbackBuffer.clear();

        if (feedbackEnabled)
        {
            target.sendSystemMessage(Component.literal("§aServux Task: §f'§d" + this.name + "§f' §ahas completed in: §b"
                    + (System.currentTimeMillis() - this.startTime) + " §fms§r"));
        }
    }

    private String successMessage()
    {
        return "Fill".equals(this.name) ? MSG_FILL_SUCCESSFUL : MSG_DELETE_SUCCESSFUL;
    }

    private String interruptedMessage()
    {
        return "Fill".equals(this.name) ? MSG_FILL_INTERRUPTED : MSG_DELETE_INTERRUPTED;
    }

    /** 发送路径玩家解析（respawn 安全；退出后 null → 跳过）。 */
    @Nullable
    private ServerPlayer resolvePlayer()
    {
        return this.server.getPlayerList().getPlayer(this.playerId);
    }
}
