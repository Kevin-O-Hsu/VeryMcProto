package verymc.top.veryMcProto.mod.syncmatica.data.litematica;

import javax.annotation.Nonnull;

/**
 * 投影 schema 版本（移植自 {@code ch.endte.syncmatica.litematica.schematic.SchematicSchema}）。
 *
 * <p>Cloned from Litematica 1.21.5 -- Sakura。
 * 用于 VERSION feature 的 metadata 字段（litematicVersion + minecraftDataVersion）。
 */
public record SchematicSchema(int litematicVersion, int minecraftDataVersion)
{
    @Override
    public @Nonnull String toString()
    {
        return "V" + this.litematicVersion() + " / DataVersion " + this.minecraftDataVersion();
    }
}
