package verymc.top.veryMcProto.mod.jei.recipesync;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * NeoForge 客户端配方内容 payload（mod 层，S2C 通道 {@code neoforge:recipe_content`}）。
 * wire 归属 = NeoForge 加载器层配方同步（Paper 移植参考 Mrbysco/JEIRecipeBridge 26.1，
 * {@code OriginImpl/JEIRecipeBridge-26.1}——mezz/JEI 上游不含此层）。
 *
 * <p>下发 {@code (recipeTypes, 全部 recipes)}：recipeTypes = RECIPE_TYPE 注册表的 id 集合
 * （VarInt count + registry id 串）；recipes = {@code RecipeHolder.STREAM_CODEC} 列表
 * （VarInt count + [ResourceKey + recipe codec]）。空类型集走 fast-path（空配方列表）。
 */
public record NeoforgeRecipeSyncPayload(
        Set<RecipeType<?>> recipeTypes,
        List<RecipeHolder<?>> recipes)
{
    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("neoforge", "recipe_content");

    public static final StreamCodec<RegistryFriendlyByteBuf, NeoforgeRecipeSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.registry(Registries.RECIPE_TYPE).apply(ByteBufCodecs.collection(HashSet::new)), NeoforgeRecipeSyncPayload::recipeTypes,
            RecipeHolder.STREAM_CODEC.apply(ByteBufCodecs.list()), NeoforgeRecipeSyncPayload::recipes,
            NeoforgeRecipeSyncPayload::new);

    public static NeoforgeRecipeSyncPayload create(Collection<RecipeType<?>> recipeTypes, RecipeMap recipes)
    {
        var recipeTypeSet = Set.copyOf(recipeTypes);
        // 空类型集 fast-path（上游原行为）
        if (recipeTypeSet.isEmpty())
        {
            return new NeoforgeRecipeSyncPayload(recipeTypeSet, List.of());
        }
        else
        {
            var recipeSubset = recipes.values().stream().filter(h -> recipeTypeSet.contains(h.value().getType())).toList();
            return new NeoforgeRecipeSyncPayload(recipeTypeSet, recipeSubset);
        }
    }
}
