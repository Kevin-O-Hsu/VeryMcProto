package verymc.top.veryMcProto.mod.servux.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import verymc.top.veryMcProto.mod.servux.dataproviders.LitematicsDataProvider;
import verymc.top.veryMcProto.mod.servux.util.position.PositionUtils;

/**
 * Litematica task 组服务端任务基类（26.1 移植；paste 任务化时自 FillDeleteTask 上提的共享面）。
 *
 * <p>对照上游 {@code TaskBase + TaskProcessChunkBase} 的共享骨架，按本仓 v3 合并形态单点承载
 * （Fill/Delete 与 Paste 仅在 {@link #execute()} 执行体、预算模型、区块加载判定半径上分叉）：
 * <ul>
 *   <li>timer：初值 0 = 下一次 runTasks 即首启（上游 {@code createTimer + setNextDelay(0)} 等价承载）；</li>
 *   <li>排序参考点 = <b>构造时捕获的原 ServerPlayer 引用</b>（上游 TaskBase 冻结语义：在线时活位置、
 *       重生/退出后冻结）；网络发送一律走 UUID 解析（respawn 安全，退出后解析 null 即跳过 = 上游
 *       "发死连接静默丢弃"等效）；</li>
 *   <li>完成链 {@link #stop()}（帧先于聊天）：resolvePlayer()==null 早退 → Complete 帧（type 16
 *       {@code InfoHudComplete=true}，客户端移除 HUD renderer 的唯一信号）→ {@code player_task_feedback}
 *       门控下成功/失败文案 + 终行——终行按 finished 条件分 completed / aborted 两态（上游
 *       {@code TaskFeedbackListener:56/:75} 双终行，文案 en_us.json:154-155 逐字）。</li>
 * </ul>
 *
 * <p><b>有意偏差（相对上游，B/CF 轮终审裁定）</b>：
 * <ul>
 *   <li>无 TaskContext / ITaskCompletionListener 双对象——上游 listener 的缓冲仅在 stop 同栈写读，直接内联
 *       （原 FillDeleteTask 的 feedbackBuffer 仪式随基类化消除）；</li>
 *   <li>构造器 player 判空守卫：<b>生产路径（C2S 受理上下文）恒非空</b>，判空仅纯 JVM 测试桩兼容
 *       （comparator 参考点另有 BlockPos.ZERO 兜底初值，PositionUtils:1231；桩路径下 stop/updateInfoHudLines
 *       结构性不可达——见 TaskSchedulerTest）；</li>
 *   <li>Fill/Delete 的中断终行文案随本次基类化由恒 completed 修正为 finished 条件（对齐上游 aborted 行）——
 *       Fill/Delete 的中断仅在插件停用 clearTasks 路径可达，行为变化已声明 docs/09 §26.1.5。</li>
 * </ul>
 */
abstract class LitematicaTask
{
    protected final MinecraftServer server;
    protected final ServerLevel level;
    protected final UUID playerId;
    /** 构造时捕获的原始玩家引用：仅作区块排序参考点（上游冻结语义）；网络发送一律走 UUID 解析。 */
    protected final ServerPlayer playerRef;
    protected final String name;
    protected final long startTime;

    protected final List<ChunkPos> pendingChunks = new ArrayList<>();
    protected final PositionUtils.ChunkPosComparator chunkPosComparator = new PositionUtils.ChunkPosComparator();

    protected boolean finished;

    /** 重复执行周期（客户端 "Interval" 字段，26.1 恒 1）；初值 0 = 下一次 runTasks 即首启。 */
    private int tickCounter = 0;
    private int repeatInterval = 1;

    protected LitematicaTask(String name, MinecraftServer server, ServerLevel level, @Nullable ServerPlayer player, long startTime)
    {
        this.name = name;
        this.server = server;
        this.level = level;
        this.playerId = player != null ? player.getUUID() : null;
        this.playerRef = player;
        this.startTime = startTime;

        if (player != null)
        {
            this.chunkPosComparator.setReferencePosition(player.blockPosition());
        }

        this.chunkPosComparator.setClosestFirst(true);
    }

    /** 登记时由调度器调用（interval = 重复执行周期，钳制 ≥1）。 */
    void setRepeatInterval(int interval) { this.repeatInterval = Math.max(1, interval); }

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

    /** 单 tick 执行体；返回 true = 任务完结（调度器将调 {@link #stop()} 并移除）。 */
    abstract boolean execute();

    abstract String successMessage();

    abstract String interruptedMessage();

    /**
     * 完成链（上游 TaskProcessChunkBase.stop → onStop → InfoHudSync.onStop → notifyListener 顺序塌缩）。
     * <b>帧先于聊天</b>：客户端先移除 HUD renderer 再见到消息。
     */
    void stop()
    {
        ServerPlayer target = this.resolvePlayer();

        if (target == null)
        {
            // 玩家已退出（或 respawn 后 UUID 不可达）：上游"发死连接静默丢弃"等效——跳过帧与消息
            return;
        }

        // ① Complete 帧（InfoHudSync.onStop → onTaskCompleteInternal）：客户端移除 HUD renderer 的唯一信号
        LitematicsDataProvider.INSTANCE.onTaskStatusSync(target, InfoHudTaskSync.completeFrame());

        // ② 反馈（TaskFeedbackListener.onTaskCompleted/Aborted）——门控读活值
        if (LitematicsDataProvider.INSTANCE.shouldSendPlayerTaskFeedback())
        {
            target.sendSystemMessage(Component.literal(this.finished ? this.successMessage() : this.interruptedMessage()));

            long elapsed = System.currentTimeMillis() - this.startTime;
            target.sendSystemMessage(Component.literal(this.finished
                    ? "§aServux Task: §f'§d" + this.name + "§f' §ahas completed in: §b" + elapsed + " §fms§r"
                    : "§cServux Task: §f'§d" + this.name + "§f' has been aborted; ran for: §b" + elapsed + " §fms§r"));
        }
    }

    /** 进度帧（TaskBase.updateInfoHudLinesPendingChunks:149-178）：无待处理区块则零帧；最近优先前 10 区块。 */
    protected void updateInfoHudLines()
    {
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

    protected void sortChunkList()
    {
        if (this.pendingChunks.size() > 0)
        {
            this.pendingChunks.sort(this.chunkPosComparator);
        }
    }

    /** 发送路径玩家解析（respawn 安全；退出后 null → 跳过）。 */
    @Nullable
    protected ServerPlayer resolvePlayer()
    {
        return this.server.getPlayerList().getPlayer(this.playerId);
    }
}
