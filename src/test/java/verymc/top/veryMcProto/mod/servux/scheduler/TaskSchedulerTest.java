package verymc.top.veryMcProto.mod.servux.scheduler;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TaskScheduler} 基类化泛化（{@link LitematicaTask} 承载 Fill/Delete 与 Paste）回归单测——纯 JVM stub，
 * 无 MC 服务端依赖。覆盖：
 * <ul>
 *   <li>timer 首启语义（tickCounter 初值 0 = 下一次 runTasks 即触发）；</li>
 *   <li>interval 钳制 ≥1——<b>黑盒不可判定</b>（interval=0 未钳制时序列与 1 相同），须反射读私有字段断言
 *       （{@code getDeclaredField} 不查父类，必须字面作用于 {@code LitematicaTask.class} + setAccessible）；</li>
 *   <li>周期复位（interval&gt;1 的门控行为）；</li>
 *   <li>完成移除（execute true → stop() 完成链 + 出队）与同 tick 双任务的索引回退（runTasks 的 --i 分支）；</li>
 *   <li>未完成保留 + clearTasks 逐任务 stop 兜底。</li>
 * </ul>
 *
 * <p>stub 以 null 上下文构造（基类 player 判空守卫 = 测试桩兼容，见 LitematicaTask javadoc——生产路径 C2S 受理
 * 上下文恒非空）；覆写 stop() 仅记录调用（基类 stop → resolvePlayer → server 解析路径对 null server 不可达）。
 */
class TaskSchedulerTest
{
    /** 记录型 stub：executeReturn 控制完结语义；stop 覆写避开 server 解析路径。 */
    private static class StubTask extends LitematicaTask
    {
        final boolean executeReturn;
        int executeCalls;
        int stopCalls;

        StubTask(boolean executeReturn)
        {
            super("stub", null, null, null, System.currentTimeMillis());
            this.executeReturn = executeReturn;
        }

        @Override boolean execute() { ++this.executeCalls; return this.executeReturn; }
        @Override String successMessage() { return "success"; }
        @Override String interruptedMessage() { return "interrupted"; }
        @Override void stop() { ++this.stopCalls; }
    }

    @BeforeAll
    static void bootstrapMinecraft()
    {
        // 与 TaskGroupTest 同款引导：防 PositionUtils 类链静态初始化触碰 MC 注册表
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    void resetScheduler()
    {
        // 饿汉单例清态，防 stub 残留跨用例污染
        TaskScheduler.getInstance().clearTasks();
    }

    private static int repeatIntervalOf(LitematicaTask task) throws ReflectiveOperationException
    {
        Field field = LitematicaTask.class.getDeclaredField("repeatInterval");
        field.setAccessible(true);
        return field.getInt(task);
    }

    @Test
    void scheduleTaskClampsIntervalToOne() throws Exception
    {
        StubTask task = new StubTask(true);
        TaskScheduler.getInstance().scheduleTask(task, 0);
        assertEquals(1, repeatIntervalOf(task), "interval=0 必须钳制为 1");
    }

    @Test
    void scheduleTaskPassesThroughValidInterval() throws Exception
    {
        StubTask task = new StubTask(true);
        TaskScheduler.getInstance().scheduleTask(task, 3);
        assertEquals(3, repeatIntervalOf(task));
    }

    @Test
    void timerTriggersOnFirstRunTasks()
    {
        StubTask task = new StubTask(false);
        TaskScheduler.getInstance().scheduleTask(task, 1);

        TaskScheduler.getInstance().runTasks();

        assertEquals(1, task.executeCalls, "timer 初值 0 = 下一次 runTasks 即首启（上游 setNextDelay(0) 等价）");
    }

    @Test
    void timerResetsToRepeatInterval()
    {
        StubTask task = new StubTask(false);
        TaskScheduler.getInstance().scheduleTask(task, 2);

        assertTrue(task.isTimerTriggered(), "首启（初值 0）");
        assertFalse(task.isTimerTriggered(), "周期内不触发");
        assertTrue(task.isTimerTriggered(), "复位后再触发");
    }

    @Test
    void finishedTaskStopsAndIsRemoved()
    {
        StubTask done = new StubTask(true);
        TaskScheduler.getInstance().scheduleTask(done, 1);

        TaskScheduler.getInstance().runTasks();

        assertEquals(1, done.executeCalls);
        assertEquals(1, done.stopCalls, "execute 返回 true → stop() 完成链");
        assertTrue(TaskScheduler.getInstance().isEmpty());
    }

    @Test
    void twoTasksFinishingSameTickBothRemoved()
    {
        StubTask first = new StubTask(true);
        StubTask second = new StubTask(true);
        TaskScheduler.getInstance().scheduleTask(first, 1);
        TaskScheduler.getInstance().scheduleTask(second, 1);

        TaskScheduler.getInstance().runTasks();

        assertEquals(1, first.executeCalls);
        assertEquals(1, second.executeCalls, "同 tick 双完成：runTasks 索引回退（--i）不得跳过第二个任务");
        assertEquals(1, first.stopCalls);
        assertEquals(1, second.stopCalls);
        assertTrue(TaskScheduler.getInstance().isEmpty());
    }

    @Test
    void unfinishedTaskStaysAndClearTasksStopsIt()
    {
        StubTask task = new StubTask(false);
        TaskScheduler.getInstance().scheduleTask(task, 1);

        TaskScheduler.getInstance().runTasks();
        assertFalse(TaskScheduler.getInstance().isEmpty(), "未完成任务保留在队列");

        TaskScheduler.getInstance().clearTasks();
        assertEquals(1, task.stopCalls, "clearTasks 逐任务走 stop() 完成链");
        assertTrue(TaskScheduler.getInstance().isEmpty());
    }
}
