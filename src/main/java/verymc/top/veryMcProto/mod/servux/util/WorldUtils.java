package verymc.top.veryMcProto.mod.servux.util;

import net.minecraft.world.level.Level;

/**
 * 方块更新抑制工具。移植自原版 {@code ORIGIN/util/WorldUtils.java}。
 *
 * <p>原版通过 Mixin {@code IWorldUpdateSuppressor} 给 {@link Level} 注入 {@code servux_shouldPreventBlockUpdates}
 * 标志位实现粘贴期间的方块更新抑制。Paper 无 Mixin 运行时，<b>降级为 no-op</b>：本方法不实际抑制更新。
 * 粘贴时由 {@code SchematicPlacingUtils} 的 {@code world.setBlock(pos, state, flags)} 的 flags 参数
 * 控制邻居更新行为（flags 不含 {@code Block.UPDATE_NEIGHBORS} 即可避免级联更新）。
 */
public class WorldUtils
{
    public static boolean shouldPreventBlockUpdates(Level world)
    {
        // Mixin 降级：Paper 无 IWorldUpdateSuppressor，恒返回 false。
        return false;
    }

    public static void setShouldPreventBlockUpdates(Level world, boolean preventUpdates)
    {
        // no-op: Mixin IWorldUpdateSuppressor 降级。粘贴靠 setBlock flags 控制更新。
    }
}
