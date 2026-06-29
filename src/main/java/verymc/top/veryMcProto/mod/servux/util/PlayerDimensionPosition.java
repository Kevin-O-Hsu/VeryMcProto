package verymc.top.veryMcProto.mod.servux.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * 记录玩家所在维度 + 位置快照（mod 层工具）。照抄原版 {@code PlayerDimensionPosition}。
 *
 * <p>用于 {@code StructureDataProvider} 检测玩家跨维度切换 / 位置漂移，触发结构数据重发。
 */
public class PlayerDimensionPosition
{
    protected DimensionType dimensionType;
    protected BlockPos pos;

    public PlayerDimensionPosition(Player player)
    {
        this.setPosition(player);
    }

    public boolean dimensionChanged(Player player)
    {
        return this.dimensionType != player.level().dimensionType();
    }

    public boolean needsUpdate(Player player, int distanceThreshold)
    {
        if (player.level().dimensionType() != this.dimensionType)
        {
            return true;
        }

        BlockPos pos = player.blockPosition();

        return Math.abs(pos.getX() - this.pos.getX()) > distanceThreshold ||
               Math.abs(pos.getY() - this.pos.getY()) > distanceThreshold ||
               Math.abs(pos.getZ() - this.pos.getZ()) > distanceThreshold;
    }

    public void setPosition(Player player)
    {
        this.dimensionType = player.level().dimensionType();
        this.pos = player.blockPosition();
    }
}
