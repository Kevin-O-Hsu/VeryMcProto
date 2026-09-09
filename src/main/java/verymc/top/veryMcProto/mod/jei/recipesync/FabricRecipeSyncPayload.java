package verymc.top.veryMcProto.mod.jei.recipesync;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.SkipPacketDecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;

/**
 * Fabric 客户端配方同步 payload（mod 层，S2C 通道 {@code fabric:recipe_sync}）。
 * wire 权威 = Fabric API 26.1 {@code fabric-recipe-api-v1} 的
 * {@code net.fabricmc.fabric.impl.recipe.sync.ClientboundRecipeSyncPayload}（已逐字段核对一致；
 * 首个 Paper 移植来自 Mrbysco/JEIRecipeBridge，26.1 线承袭）。
 *
 * <p>按 {@link RecipeSerializer} 分组：顶层 = VarInt(entryCount) + entries；每个 {@link Entry} =
 * {@code Identifier(serializer id) + VarInt(count) + count × (ResourceKey&lt;Recipe&gt; + serializer.streamCodec(recipe))}。
 * 客户端 Fabric API 展平排序后经 {@code ClientRecipeSynchronizedEvent} 交给 JEI。
 *
 * <p>本模块仅用 S2C 编码方向（{@link Entry#write}）；{@link Entry#read} 保留对称（供测试往返），
 * 与 Fabric API 版差异一处：上游 read 端额外校验 {@code RecipeSyncImpl.isSynced(serializer)}
 * （客户端侧标记集合，Paper 服务端无对应物，不影响我们只发不收）。
 */
public record FabricRecipeSyncPayload(List<Entry> entries)
{
    public static final StreamCodec<RegistryFriendlyByteBuf, FabricRecipeSyncPayload> CODEC = Entry.CODEC.apply(ByteBufCodecs.list())
            .map(FabricRecipeSyncPayload::new, FabricRecipeSyncPayload::entries);

    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("fabric", "recipe_sync");

    /**
     * 单个序列化器分组：{@code (serializer, recipes[])}。线序 = id + VarInt(count) + count × (resourceKey + recipe)。
     */
    public record Entry(RecipeSerializer<?> serializer, List<RecipeHolder<?>> recipes)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.ofMember(
                Entry::write,
                Entry::read);

        static Entry read(RegistryFriendlyByteBuf buf)
        {
            Identifier recipeSerializerId = buf.readIdentifier();
            RecipeSerializer<?> recipeSerializer = BuiltInRegistries.RECIPE_SERIALIZER.getValue(recipeSerializerId);

            if (recipeSerializer == null)
            {
                throw new SkipPacketDecoderException("Tried syncing unsupported packet serializer '" + recipeSerializerId + "'!");
            }

            int count = buf.readVarInt();
            var list = new ArrayList<RecipeHolder<?>>();

            for (int i = 0; i < count; i++)
            {
                ResourceKey<Recipe<?>> id = buf.readResourceKey(Registries.RECIPE);
                //noinspection deprecation
                Recipe<?> recipe = recipeSerializer.streamCodec().decode(buf);
                list.add(new RecipeHolder<>(id, recipe));
            }

            return new Entry(recipeSerializer, list);
        }

        void write(RegistryFriendlyByteBuf buf)
        {
            buf.writeIdentifier(BuiltInRegistries.RECIPE_SERIALIZER.getKey(this.serializer));

            buf.writeVarInt(this.recipes.size());

            //noinspection unchecked,deprecation
            StreamCodec<RegistryFriendlyByteBuf, Recipe<?>> serializer =
                    ((StreamCodec<RegistryFriendlyByteBuf, Recipe<?>>) this.serializer.streamCodec());

            for (RecipeHolder<?> recipe : this.recipes)
            {
                buf.writeResourceKey(recipe.id());
                serializer.encode(buf, recipe.value());
            }
        }
    }
}
