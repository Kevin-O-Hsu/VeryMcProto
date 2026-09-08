package verymc.top.veryMcProto.mod.servux.scheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * Litematica task 组服务端任务调度器（26.1 移植）。
 *
 * <p>对照上游 {@code OriginImpl/servux-LTS-26.1 scheduler/TaskScheduler.java:17-74}（scheduler 20 类的
 * v3 极简合并形态——见 docs/09 §26.1.5）：单列表 + 每 tick {@link #runTasks()} 驱动。
 * 任务面 = {@link LitematicaTask} 基类（Fill/Delete 与 Paste 共居；上游 {@code List<ITask>} 的对应物，
 * 预算模型任务自带——Fill/Delete 固定 25ms、Paste 为 vanillaTickTime+60ms 动态预算，调度器零预算知识）。
 *
 * <p><b>有意偏差（相对上游，B 轮终审裁定）</b>：
 * <ul>
 *   <li><b>无 synchronized</b>：上游锁防 Fabric netty 线程注入；Paper 上 C2S custom payload 与
 *       BukkitRunnable tick 均在主线程，scheduleTask/runTasks 单写者不变式成立（B2-B3 实证）；</li>
 *   <li><b>无双列表 tasksToAdd</b>：上游双列表唯一效果是"当 tick 注册、同/下 tick 启动"；此处任务内
 *       timer 初值 0（首次 runTasks 即触发）等价承载，另因 Bukkit 心跳先于网络包处理，实际启动恒为
 *       <b>收到请求的下一 tick（≤1 tick 偏移）</b>，不得声称与上游逐 tick 等价；</li>
 *   <li><b>无 hasTask/getAllTasks/removeTask</b>：上游全库零调用点（B/CF 轮调用图实证）。</li>
 * </ul>
 */
public class TaskScheduler
{
    private static final TaskScheduler INSTANCE = new TaskScheduler();

    public static TaskScheduler getInstance() { return INSTANCE; }

    private final List<LitematicaTask> tasks = new ArrayList<>();

    private TaskScheduler() { }

    /**
     * 登记任务（interval = 重复执行周期，来自客户端 "Interval" 字段——26.1 客户端恒发 1，
     * 但协议字段存在即须生效）。任务首启延迟 1 tick（timer 初值 0，下一次 runTasks 触发）。
     */
    public void scheduleTask(LitematicaTask task, int interval)
    {
        task.setRepeatInterval(Math.max(1, interval));
        this.tasks.add(task);
    }

    /** 每 tick 驱动（由 LitematicsDataProvider.onTickEndPre 调用，对应上游 MixinMinecraftServer tickServer RETURN）。 */
    public void runTasks()
    {
        if (this.tasks.isEmpty() == false)
        {
            for (int i = 0; i < this.tasks.size(); ++i)
            {
                LitematicaTask task = this.tasks.get(i);

                if (task.isTimerTriggered())
                {
                    boolean finished = task.execute();

                    if (finished)
                    {
                        task.stop();
                        this.tasks.remove(i);
                        --i;
                    }
                }
            }
        }
    }

    /** 清空并逐任务走 stop() 完成链（发 Complete 帧 + gamerule 无涉）。仅 VeryMcProto.onDisable 调用。 */
    public void clearTasks()
    {
        for (int i = 0; i < this.tasks.size(); ++i)
        {
            this.tasks.get(i).stop();
        }

        this.tasks.clear();
    }

    public boolean isEmpty() { return this.tasks.isEmpty(); }
}
