package verymc.top.veryMcProto.mod.jeirecipebridge.payload;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * NeoForge 客户端配方内容 payload（mod 层）。照抄原版 {@code com.mrbysco.jeicompat.compat.neoforge.NeoforgeRecipeSyncPayload}。
 *
 * <p>通道 {@code neoforge:recipe_content}。下发 {@code (recipeTypes, 全部 recipes)}。空 recipeTypes 走 fast-path。
 *
 * <p>原版 {@code @NonNull}（{@code org.jspecify.annotations}）注解已移除——本项目无 jspecify 依赖，
 * 返回值非空约束由 {@link #create} 语义保证（恒返回非 null）。
 */
public record NeoforgeRecipeSyncPayload(
        Set<RecipeType<?>> recipeTypes,
        List<RecipeHolder<?>> recipes) implements CustomPacketPayload
{
    public static final Type<NeoforgeRecipeSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("neoforge", "recipe_content"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NeoforgeRecipeSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.registry(Registries.RECIPE_TYPE).apply(ByteBufCodecs.collection(HashSet::new)), NeoforgeRecipeSyncPayload::recipeTypes,
            RecipeHolder.STREAM_CODEC.apply(ByteBufCodecs.list()), NeoforgeRecipeSyncPayload::recipes,
            NeoforgeRecipeSyncPayload::new);

    public static NeoforgeRecipeSyncPayload create(Collection<RecipeType<?>> recipeTypes, RecipeMap recipes)
    {
        var recipeTypeSet = Set.copyOf(recipeTypes);
        // Fast-path for empty recipe type set (if no mod wants to sync anything)
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

    @Override
    public Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }
}
