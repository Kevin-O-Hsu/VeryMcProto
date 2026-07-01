package verymc.top.veryMcProto.mod.syncmatica.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;

/**
 * 投影放置原点坐标（移植自 {@code ch.endte.syncmatica.data.ServerPosition}）。
 *
 * <p>仅用于 placement 的 origin（BlockPos + dimensionId）。注意原版未定义 END 维度常量。
 */
public class ServerPosition
{
    private final BlockPos position;
    private final String dimensionId;

    public static final String NETHER_DIMENSION_ID = "minecraft:the_nether";
    public static final String OVERWORLD_DIMENSION_ID = "minecraft:overworld";

    public ServerPosition(final BlockPos pos, final String dim)
    {
        position = pos;
        dimensionId = dim;
    }

    public static ServerPosition fromGlobalPos(GlobalPos pos)
    {
        return new ServerPosition(pos.pos(), pos.dimension().identifier().toString());
    }

    public BlockPos getBlockPosition()
    {
        return position;
    }

    public String getDimensionId()
    {
        return dimensionId;
    }

    public JsonObject toJson()
    {
        final JsonObject obj = new JsonObject();
        final JsonArray arr = new JsonArray();
        arr.add(new JsonPrimitive(position.getX()));
        arr.add(new JsonPrimitive(position.getY()));
        arr.add(new JsonPrimitive(position.getZ()));
        obj.add("position", arr);
        obj.add("dimension", new JsonPrimitive(dimensionId));
        return obj;
    }

    public static ServerPosition fromJson(final JsonObject obj)
    {
        if (obj == null || !obj.has("position") || !obj.has("dimension"))
        {
            return null;
        }
        // Paper 防御性增强：原版直接 arr.get(0).getAsInt()，损坏 JSON 会抛异常导致整表加载失败。
        try
        {
            final JsonArray arr = obj.get("position").getAsJsonArray();
            if (arr.size() != 3)
            {
                return null;
            }
            final int x = arr.get(0).getAsInt();
            final int y = arr.get(1).getAsInt();
            final int z = arr.get(2).getAsInt();
            final BlockPos pos = new BlockPos(x, y, z);
            return new ServerPosition(pos, obj.get("dimension").getAsString());
        }
        catch (final Exception ignored)
        {
            return null;
        }
    }
}
