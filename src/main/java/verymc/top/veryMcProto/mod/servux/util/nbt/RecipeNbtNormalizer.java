package verymc.top.veryMcProto.mod.servux.util.nbt;

import net.minecraft.nbt.*;

/**
 * RecipeManager wire 预处理（servux:hud_metadata 通道专用兼容层）。
 *
 * <p>26.1 wire 病灶：{@code Recipe.CODEC.encodeStart(NbtOps.INSTANCE, ...)} 的 ingredient 元素域为
 * 「单物品 → 裸 StringTag（ExtraCodecs.compactListCodec 单元素裸出）；多物品/tag → ListTag」——
 * 两者混装的 ingredients 列表（全量普查恰 66 条 vanilla 配方）在 {@code ListTag.write} 逐元素写出时
 * 被包装为 {@code {"": x}} Compound 并按声明类型 TAG_Compound(10) 上线；malilib 客户端读端
 * （ListData.read，同构语法，全库无解包逻辑）读回包装 Compound 后，客户端 Ingredient.CODEC
 * 的「串/列表」两分支皆拒 → DFU 报 {@code List is too short: 0, expected range [1-9]}，
 * 即 minihud HudDataManager 的 66 条 receiveRecipeManager 刷屏报错。
 *
 * <p>本类规范化：混合列表内裸 StringTag 包成单元素 ListTag → 整表同构 → wire 不再包装，
 * 客户端解码成功且物品集语义等价（已用真 vanilla jar + 真数据包配方离线逐字验证）。
 * 同构列表（全串/全列表）原样返回——wire 逐字节不变。
 *
 * <p>注：tag 形 ingredient 在 26.1 plain NbtOps 编码下不存在 "#tag" 串形态——
 * HolderSetCodec 非 RegistryOps 恒走 encodeWithoutRegistry 把 Named 集展开为物品列表
 * （HolderSetCodec.java:72-105）；故此处无需 #tag 特判。若未来编码路径变化出现 # 串，
 * 包裹后客户端解码失败的行为与现状等价，不会更糟。
 */
public class RecipeNbtNormalizer
{
    /**
     * 规范化配方 NBT 树的 ingredients 混合列表（原地替换，返回同一 CompoundTag 引用）。
     *
     * @param recipe ()
     * @return ()
     */
    public static Tag normalizeIngredients(Tag recipe)
    {
        if (!(recipe instanceof CompoundTag compound))
        {
            return recipe;
        }

        ListTag ingredients = compound.getListOrEmpty("ingredients");

        if (ingredients.isEmpty())
        {
            return recipe;
        }

        // 同构短路：全部元素 NBT 类型 id 一致（与 ListTag.identifyRawElementType 同源语义）
        // → vanilla 写端不包装 → wire 逐字节保持原样
        byte firstType = ingredients.get(0).getId();
        boolean mixed = false;

        for (Tag element : ingredients)
        {
            if (element.getId() != firstType)
            {
                mixed = true;
                break;
            }
        }

        if (!mixed)
        {
            return recipe;
        }

        ListTag normalized = new ListTag();

        for (Tag element : ingredients)
        {
            if (element instanceof StringTag)
            {
                ListTag single = new ListTag();
                single.add(element);
                normalized.add(single);
            }
            else
            {
                normalized.add(element);
            }
        }

        compound.put("ingredients", normalized);
        return compound;
    }
}
