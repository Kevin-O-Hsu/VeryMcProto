package verymc.top.veryMcProto.mod.servux.schematic.selection;


/**
 * 区域选择模式枚举。移植自原版 {@code ORIGIN/schematic/selection/SelectionMode.java}（照抄改 package）。
 */
public enum SelectionMode
{
    NORMAL  ("litematica.gui.label.area_selection.mode.normal"),
    SIMPLE  ("litematica.gui.label.area_selection.mode.simple");

    private final String translationKey;

    private SelectionMode(String translationKey)
    {
        this.translationKey = translationKey;
    }

    public String getTranslationKey()
    {
        return this.translationKey;
    }

    public String getDisplayName()
    {
        return (this.translationKey);
    }

    public SelectionMode cycle(boolean forward)
    {
        int id = this.ordinal();

        if (forward)
        {
            if (++id >= values().length)
            {
                id = 0;
            }
        }
        else
        {
            if (--id < 0)
            {
                id = values().length - 1;
            }
        }

        return values()[id % values().length];
    }

    public static SelectionMode fromString(String name)
    {
        for (SelectionMode mode : SelectionMode.values())
        {
            if (mode.name().equalsIgnoreCase(name))
            {
                return mode;
            }
        }

        return SelectionMode.NORMAL;
    }
}
