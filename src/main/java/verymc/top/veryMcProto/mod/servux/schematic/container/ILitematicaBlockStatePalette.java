package verymc.top.veryMcProto.mod.servux.schematic.container;

import javax.annotation.Nullable;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;

/**
 * BlockState 调色板接口。移植自原版 {@code ORIGIN/schematic/container/ILitematicaBlockStatePalette.java}（照抄改 package）。
 */
public interface ILitematicaBlockStatePalette
{
    /**
     * Gets the palette id for the given block state and adds
     * the state to the palette if it doesn't exist there yet.
     */
    int idFor(BlockState state);

    /**
     * Gets the block state by the palette id.
     */
    @Nullable
    BlockState getBlockState(int indexKey);

    int getPaletteSize();

    void readFromNBT(ListTag tagList);

    ListTag writeToNBT();

    /**
     * Sets the current mapping of the palette.
     * This is meant for reading the palette from file.
     * @param list ()
     * @return true if the mapping was set successfully, false if it failed
     */
    boolean setMapping(List<BlockState> list);
}
