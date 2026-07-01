package verymc.top.veryMcProto.mod.syncmatica.extended_core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * 单个子区域放置覆盖（移植自 {@code ch.endte.syncmatica.extended_core.SubRegionPlacementModification}）。
 *
 * <p>CORE_EX feature：placement 的子区域旋转/镜像修改。全部 public final 字段（无 getter）。
 * 构造器包级，仅 {@link SubRegionData}（同包）可创建实例。
 */
public class SubRegionPlacementModification {
    public final String name;
    public final BlockPos position;
    public final Rotation rotation;
    public final Mirror mirror;

    SubRegionPlacementModification(final String name, final BlockPos position, final Rotation rotation, final Mirror mirror) {
        this.name = name;
        this.position = position;
        this.rotation = rotation;
        this.mirror = mirror;
    }

    public JsonObject toJson() {
        final JsonObject obj = new JsonObject();

        final JsonArray arr = new JsonArray();
        arr.add(position.getX());
        arr.add(position.getY());
        arr.add(position.getZ());
        obj.add("position", arr);

        obj.add("name", new JsonPrimitive(name));
        obj.add("rotation", new JsonPrimitive(rotation.name()));
        obj.add("mirror", new JsonPrimitive(mirror.name()));

        return obj;
    }

    public static SubRegionPlacementModification fromJson(final JsonObject obj) {
        if (obj == null
                || !obj.has("name")
                || !obj.has("position")
                || !obj.has("rotation")
                || !obj.has("mirror")
        ) {

            return null;
        }
        // Paper 防御性增强：原版 Rotation.valueOf/Mirror.valueOf 对无效名会抛 IllegalArgumentException，
        // 此处 try/catch 避免单个损坏条目炸掉整张 placements.json 加载。
        try
        {
            final String name = obj.get("name").getAsString();

            final JsonArray arr = obj.get("position").getAsJsonArray();
            if (arr.size() != 3) {

                return null;
            }
            final BlockPos position = new BlockPos(
                    arr.get(0).getAsInt(),
                    arr.get(1).getAsInt(),
                    arr.get(2).getAsInt()
            );

            final Rotation rotation = Rotation.valueOf(obj.get("rotation").getAsString());
            final Mirror mirror = Mirror.valueOf(obj.get("mirror").getAsString());

            return new SubRegionPlacementModification(name, position, rotation, mirror);
        }
        catch (final Exception ignored)
        {
            return null;
        }
    }

    @Override
    public String toString() {
        return String.format("[name=%s, position=%s, rotation=%s, mirror=%s]", name, position, rotation, mirror);
    }
}
