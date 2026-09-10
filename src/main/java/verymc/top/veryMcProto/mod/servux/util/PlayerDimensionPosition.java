package verymc.top.veryMcProto.mod.servux.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.dimension.DimensionType;

import verymc.top.veryMcProto.framework.network.PacketSplitter;

/**
 * 记录玩家所在维度 + 位置快照（mod 层工具）。照抄原版 {@code PlayerDimensionPosition}。
 *
 * <p>用于 {@code StructureDataProvider} 检测玩家跨维度切换 / 位置漂移，触发结构数据重发。
 */
public class PlayerDimensionPosition
{
    protected DimensionType dimensionType;
    protected BlockPos pos;

    /**
     * 客户端 register 申报的 S2C 单帧接收上限（{@code tags.max_receive_s2c}，TAG_INT，默认 16MB——
     * 与 26.1 客户端重组上限同源，纠偏上游 sendStructures 侧 C2S 命名误用，两常量数值同为 16777216）。
     * 26.1 起内联承载于名册 entry（上游为独立 maxPacketSize Map + 4 处 remove 同步点——生命周期随
     * entry 归一，无平行清理路径），sendStructures 据此做条目级分批。
     */
    public int maxReceiveS2c = PacketSplitter.MAX_REASSEMBLY_SIZE_S2C;

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
