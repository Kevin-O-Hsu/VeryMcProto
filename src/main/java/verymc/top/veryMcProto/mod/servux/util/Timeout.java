package verymc.top.veryMcProto.mod.servux.util;

/**
 * 简单 tick 计时器（mod 层工具）。照抄原版 {@code Timeout}。
 *
 * <p>用于 {@code StructureDataProvider} 判定某区块的结构数据是否需要重新下发。
 */
public class Timeout
{
    protected int lastSync;

    public Timeout(int currentTick)
    {
        this.lastSync = currentTick;
    }

    public boolean needsUpdate(int currentTick, int timeout)
    {
        return currentTick - this.lastSync >= timeout;
    }

    public void setLastSync(int tickCounter)
    {
        this.lastSync = tickCounter;
    }
}
