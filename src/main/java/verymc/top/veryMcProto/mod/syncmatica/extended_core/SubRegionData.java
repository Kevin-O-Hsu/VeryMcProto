package verymc.top.veryMcProto.mod.syncmatica.extended_core;


import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * 子区域修改集合（移植自 {@code ch.endte.syncmatica.extended_core.SubRegionData}）。
 *
 * <p>CORE_EX feature：placement 的全部子区域覆盖。{@code modificationData} 在 {@code isModified=false} 时为 null。
 * {@code toJson} 仅在 {@code isModified()} 时调用（由 ServerPlacement.toJson 守卫），否则 modificationData 为 null 会 NPE。
 */
public class SubRegionData {
    private boolean isModified;
    private Map<String, SubRegionPlacementModification> modificationData; // is null when isModified is false

    public SubRegionData() {
        this(false, null);
    }

    public SubRegionData(final boolean isModified, final Map<String, SubRegionPlacementModification> modificationData) {
        this.isModified = isModified;
        this.modificationData = modificationData;
    }

    public void reset() {
        isModified = false;
        modificationData = null;
    }

    public void modify(
            final String name,
            final BlockPos position,
            final Rotation rotation,
            final Mirror mirror
    ) {
        modify(
                new SubRegionPlacementModification(
                        name,
                        position,
                        rotation,
                        mirror
                )
        );
    }

    public void modify(final SubRegionPlacementModification subRegionPlacementModification) {
        if (subRegionPlacementModification == null) {

            return;
        }
        isModified = true;
        if (modificationData == null) {
            modificationData = new HashMap<>();
        }
        modificationData.put(subRegionPlacementModification.name, subRegionPlacementModification);
    }

    public boolean isModified() {
        return isModified;
    }

    public Map<String, SubRegionPlacementModification> getModificationData() {
        return modificationData;
    }

    public JsonElement toJson() {

        return modificationDataToJson();
    }

    private JsonElement modificationDataToJson() {
        final JsonArray arr = new JsonArray();

        for (final Map.Entry<String, SubRegionPlacementModification> entry : modificationData.entrySet()) {
            arr.add(entry.getValue().toJson());
        }

        return arr;
    }

    public static SubRegionData fromJson(final JsonElement obj) {
        final SubRegionData newSubRegionData = new SubRegionData();

        newSubRegionData.isModified = true;

        // Paper 防御性增强：原版 obj.getAsJsonArray() 在 obj 不是数组时会抛异常。
        try
        {
            for (final JsonElement modification : obj.getAsJsonArray()) {
                newSubRegionData.modify(SubRegionPlacementModification.fromJson(modification.getAsJsonObject()));
            }
        }
        catch (final Exception ignored)
        {
            // 损坏条目跳过；已 modify 的保留
        }

        return newSubRegionData;
    }

    @Override
    public String toString() {
        if (!isModified) {

            return "[]";
        }

        return modificationData == null ? "[ERROR:null]" : modificationData.toString();
    }
}
