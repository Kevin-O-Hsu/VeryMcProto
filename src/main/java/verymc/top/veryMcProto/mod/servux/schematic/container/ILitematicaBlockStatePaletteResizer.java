package verymc.top.veryMcProto.mod.servux.schematic.container;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 调色板扩容回调接口。移植自原版 {@code ORIGIN/schematic/container/ILitematicaBlockStatePaletteResizer.java}（照抄改 package）。
 */
public interface ILitematicaBlockStatePaletteResizer
{
    int onResize(int bits, BlockState state);
}
