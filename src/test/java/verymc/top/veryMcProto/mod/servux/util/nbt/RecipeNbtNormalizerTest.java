package verymc.top.veryMcProto.mod.servux.util.nbt;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecipeNbtNormalizer} 纯函数单测（无需起服务端；NMS 类经 paperDevBundle 在纯 JVM 可用）。
 *
 * <p>核心回归面（用例 {@link #mixedListWritesHomogeneousOnWire()}）：病灶在 <b>write 侧</b>——
 * vanilla {@code ListTag.write} 把混合列表包装为 {@code {"": x}} 按声明类型 10 写出，而 malilib
 * 客户端读端（ListData.read）不解包。因此断言必须走 <b>流内原始字节</b>（声明类型字节 + 包装签名计数），
 * 不能用 vanilla {@code NbtIo.read} 回读树——vanilla 读端 addAndUnwrap 会自动解包，
 * 回读断言恒过、无区分力（这正是 DataTagIo round-trip 单测当年没拦住病灶的结构性原因）。
 */
class RecipeNbtNormalizerTest
{
    /** fire_charge.json 的真实编码形态：单物品 → 裸串（×2），多物品 → 子列表（×1），混合列表。 */
    private static CompoundTag fireChargeShape()
    {
        ListTag alternatives = new ListTag();
        alternatives.add(StringTag.valueOf("minecraft:coal"));
        alternatives.add(StringTag.valueOf("minecraft:charcoal"));

        ListTag ingredients = new ListTag();
        ingredients.add(StringTag.valueOf("minecraft:gunpowder"));
        ingredients.add(StringTag.valueOf("minecraft:blaze_powder"));
        ingredients.add(alternatives);

        CompoundTag recipe = new CompoundTag();
        recipe.putString("type", "minecraft:crafting_shapeless");
        recipe.put("ingredients", ingredients);
        return recipe;
    }

    /** 染色族的真实编码形态：tag 集（≥2 物品）→ 子列表，单物品 → 裸串。 */
    private static CompoundTag dyeShape()
    {
        ListTag woolSet = new ListTag();
        woolSet.add(StringTag.valueOf("minecraft:white_wool"));
        woolSet.add(StringTag.valueOf("minecraft:orange_wool"));

        ListTag ingredients = new ListTag();
        ingredients.add(woolSet);
        ingredients.add(StringTag.valueOf("minecraft:orange_dye"));

        CompoundTag recipe = new CompoundTag();
        recipe.putString("type", "minecraft:crafting_shapeless");
        recipe.put("ingredients", ingredients);
        return recipe;
    }

    private static byte[] wireBytes(CompoundTag recipe) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.write(recipe, new DataOutputStream(out));
        return out.toByteArray();
    }

    /** 定位流内 "ingredients" 条目声明元素类型字节（[09][UTF "ingredients"][声明类型]…）。 */
    private static byte declaredElementType(byte[] wire)
    {
        byte[] key = { 0x00, 0x0B };                       // writeUTF 长度 11
        byte[] name = "ingredients".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i <= wire.length - 2 - name.length; i++)
        {
            boolean hit = wire[i] == key[0] && wire[i + 1] == key[1];

            for (int j = 0; hit && j < name.length; j++)
            {
                hit = wire[i + 2 + j] == name[j];
            }

            if (hit) { return wire[i + 2 + name.length]; }
        }

        throw new AssertionError("wire 中未找到 ingredients 条目");
    }

    /** 朴素字节序列包含判定。 */
    private static boolean contains(byte[] haystack, byte[] needle)
    {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++)
        {
            for (int j = 0; j < needle.length; j++)
            {
                if (haystack[i + j] != needle[j]) { continue outer; }
            }

            return true;
        }

        return false;
    }

    /** ① fire_charge 形态：混合列表 → 全 ListTag，每个裸串各成单元素列表。 */
    @Test
    void mixedListNormalizedToAllLists() throws Exception
    {
        CompoundTag recipe = fireChargeShape();
        Tag result = RecipeNbtNormalizer.normalizeIngredients(recipe);

        assertSame(recipe, result);
        ListTag ingredients = recipe.getListOrEmpty("ingredients");
        assertEquals(3, ingredients.size());

        for (Tag element : ingredients)
        {
            assertTrue(element instanceof ListTag, "元素应为 ListTag，实为 " + element.getClass().getSimpleName());
        }

        ListTag first = (ListTag) ingredients.get(0);
        assertEquals(1, first.size());
        assertTrue(first.get(0) instanceof StringTag);
    }

    /** ② 染色族形态：子列表原样保留，裸串被包裹，物品集语义不变。 */
    @Test
    void dyeShapeSetPreservedBareStringWrapped() throws Exception
    {
        CompoundTag recipe = dyeShape();
        RecipeNbtNormalizer.normalizeIngredients(recipe);

        ListTag ingredients = recipe.getListOrEmpty("ingredients");
        assertEquals(2, ingredients.size());

        ListTag woolSet = (ListTag) ingredients.get(0);
        assertEquals(2, woolSet.size());                       // tag 集不动
        assertEquals("minecraft:white_wool", ((StringTag) woolSet.get(0)).asString().orElseThrow());

        ListTag dye = (ListTag) ingredients.get(1);
        assertEquals(1, dye.size());
        assertEquals("minecraft:orange_dye", ((StringTag) dye.get(0)).asString().orElseThrow());
    }

    /** ③ 同构全串（如 fermented_spider_eye）→ 同一实例原样返回，wire 零字节变化。 */
    @Test
    void homogeneousStringsUntouched()
    {
        CompoundTag recipe = new CompoundTag();
        ListTag ingredients = new ListTag();
        ingredients.add(StringTag.valueOf("minecraft:spider_eye"));
        ingredients.add(StringTag.valueOf("minecraft:sugar"));
        recipe.put("ingredients", ingredients);

        assertSame(recipe, RecipeNbtNormalizer.normalizeIngredients(recipe));
        assertEquals(2, recipe.getListOrEmpty("ingredients").size());
        assertTrue(recipe.getListOrEmpty("ingredients").get(0) instanceof StringTag);
    }

    /** ④ 同构全列表 → 同一实例原样返回。 */
    @Test
    void homogeneousListsUntouched()
    {
        CompoundTag recipe = new CompoundTag();
        ListTag ingredients = new ListTag();
        ListTag a = new ListTag();
        a.add(StringTag.valueOf("minecraft:coal"));
        ListTag b = new ListTag();
        b.add(StringTag.valueOf("minecraft:charcoal"));
        ingredients.add(a);
        ingredients.add(b);
        recipe.put("ingredients", ingredients);

        assertSame(recipe, RecipeNbtNormalizer.normalizeIngredients(recipe));
    }

    /**
     * ⑤ 病灶层字节断言（N1 强制路径）：混合列表写出时声明类型 0x0A 且流内出现空键包装条目
     * （{@code [08][00 00][00 13]…} = {"":"minecraft:gunpowder"}、{@code [09][00 00][08]…} = {"":[…]}）；
     * 规范化后声明类型 0x09、两类包装签名全部消失——即 malilib 视角不再出现包装 Compound。
     */
    @Test
    void mixedListWritesHomogeneousOnWire() throws Exception
    {
        CompoundTag recipe = fireChargeShape();

        byte[] before = wireBytes(recipe);
        assertEquals(0x0A, declaredElementType(before), "规范化前混合列表应声明 TAG_Compound(10)");
        assertTrue(contains(before, new byte[] { 0x08, 0x00, 0x00, 0x00, 0x13 }), "串值包装条目应在场");
        assertTrue(contains(before, new byte[] { 0x09, 0x00, 0x00, 0x08 }), "列表值包装条目应在场");

        RecipeNbtNormalizer.normalizeIngredients(recipe);

        byte[] after = wireBytes(recipe);
        assertEquals(0x09, declaredElementType(after), "规范化后应声明 TAG_List(9)");
        assertTrue(!contains(after, new byte[] { 0x08, 0x00, 0x00, 0x00, 0x13 }), "串值包装条目应消失");
        assertTrue(!contains(after, new byte[] { 0x09, 0x00, 0x00, 0x08 }), "列表值包装条目应消失");
    }

    /** ⑥ 幂等：每玩家请求全量重跑，二次调用必须同构短路（同一实例、字节稳定）。 */
    @Test
    void idempotentOnSecondPass() throws Exception
    {
        CompoundTag recipe = fireChargeShape();
        Tag once = RecipeNbtNormalizer.normalizeIngredients(recipe);

        Tag twice = RecipeNbtNormalizer.normalizeIngredients(once);
        assertSame(once, twice);
        assertArrayEquals(wireBytes((CompoundTag) once), wireBytes((CompoundTag) twice));
    }

    /** ⑦ 非 ingredients 数据不触碰：shaped key（Compound 值混装）/pattern（串列表）零改动；非 Compound 原样。 */
    @Test
    void shapedFieldsAndNonCompoundUntouched() throws Exception
    {
        CompoundTag shaped = new CompoundTag();
        CompoundTag key = new CompoundTag();
        key.put("a", StringTag.valueOf("minecraft:stone"));
        ListTag bSet = new ListTag();
        bSet.add(StringTag.valueOf("minecraft:oak_planks"));
        bSet.add(StringTag.valueOf("minecraft:stone"));
        key.put("b", bSet);
        shaped.put("key", key);
        ListTag pattern = new ListTag();
        pattern.add(StringTag.valueOf("ab"));
        pattern.add(StringTag.valueOf("ba"));
        shaped.put("pattern", pattern);

        byte[] before = wireBytes(shaped);
        assertSame(shaped, RecipeNbtNormalizer.normalizeIngredients(shaped));
        assertArrayEquals(before, wireBytes(shaped));

        Tag notCompound = StringTag.valueOf("whatever");
        assertSame(notCompound, RecipeNbtNormalizer.normalizeIngredients(notCompound));
    }
}
