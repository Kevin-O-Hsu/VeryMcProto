package verymc.top.veryMcProto.mod.jei.recipesync;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.mod.jei.JeiReference;
import verymc.top.veryMcProto.mod.jei.network.JeiPacketSender;

/**
 * 配方同步发送服务（mod 层）。两条 loader 腿：
 * <ul>
 *   <li><b>fabric 腿</b>（{@link #sendFabric}）：触发 = 客户端经 {@code minecraft:register} 声明
 *       {@code fabric:recipe_sync}（Paper {@code PlayerRegisterChannelEvent}）——对齐上游 Fabric API
 *       {@code RecipeSyncImpl.sendRecipes} 的 {@code canSend(player)} 门控（只发给声明过能收的客户端，
 *       vanilla/未装 Fabric API 的客户端零打扰）。分组为<b>单遍 O(R)</b>（按 serializer 身份聚合，
 *       替换旧实现的 serializers × recipes 双重扫描）。</li>
 *   <li><b>neoforge 腿</b>（{@link #sendNeoForge}）：触发 = join + brand 判定（保持已实机验证的旧行为——
 *       NeoForge 客户端连 vanilla/Paper 服处于 vanilla 模式，其 {@code minecraft:register} 声明行为不可依赖）；
 *       发配方后补发 {@code ClientboundUpdateTagsPacket}（原版行为）。</li>
 * </ul>
 * 发送路径统一走 {@link JeiPacketSender}（NMS {@code DiscardedPayload} 直发——配方包常超 1MiB，
 * 不可走 {@code ProtocolChannel.send} 的 Bukkit 上限拒绝路径）。
 */
public final class RecipeSyncService
{
    private RecipeSyncService() { }

    /** fabric 腿：单遍分组 + {@code fabric:recipe_sync} 单包直发。 */
    public static void sendFabric(ServerPlayer player, MinecraftServer server)
    {
        RecipeMap recipeMap = server.getRecipeManager().recipes;
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try
        {
            // 单遍 O(R)：按 serializer 身份聚合（IdentityHashMap——serializer 无 equals 语义，身份即语义）。
            // minecraft 命名空间过滤（对齐上游 JustEnoughItems.java(fabric):38-48 :40）：上游遍历
            // RECIPE_SERIALIZER 注册表仅同步 minecraft 命名空间的 serializer（JEI 自有 jei:jei_shaped 等
            // 被排除）——此处按 registry key 反查实现等价语义；key==null（未注册 serializer）跳过不放行
            //（上游按 registry 遍历天然不含未注册项）。
            Map<RecipeSerializer<?>, List<RecipeHolder<?>>> grouped = new IdentityHashMap<>();
            int vanillaRecipes = 0;
            for (RecipeHolder<?> holder : recipeMap.values())
            {
                Identifier serializerKey = BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer());
                if (serializerKey == null || !serializerKey.getNamespace().equals("minecraft"))
                {
                    continue;
                }
                grouped.computeIfAbsent(holder.value().getSerializer(), k -> new ArrayList<>()).add(holder);
                vanillaRecipes++;
            }

            List<FabricRecipeSyncPayload.Entry> entries = new ArrayList<>(grouped.size());
            for (Map.Entry<RecipeSerializer<?>, List<RecipeHolder<?>>> group : grouped.entrySet())
            {
                if (!group.getValue().isEmpty())
                {
                    entries.add(new FabricRecipeSyncPayload.Entry(group.getKey(), group.getValue()));
                }
            }

            if (entries.isEmpty())
            {
                return; // 空配方表：上游 sendRecipes 同样跳过（list.isEmpty() return）
            }

            FabricRecipeSyncPayload.CODEC.encode(buffer, new FabricRecipeSyncPayload(entries));

            byte[] bytes = new byte[buffer.writerIndex()];
            buffer.getBytes(0, bytes);
            JeiPacketSender.send(player, JeiReference.CHANNEL_FABRIC_RECIPE_SYNC, bytes);
            Reference.logger().info("[JEI] fabric 配方同步 → " + player.getName().getString()
                    + "（" + vanillaRecipes + "/" + recipeMap.values().size() + " 配方（minecraft 命名空间）/ "
                    + entries.size() + " 组 / " + bytes.length + " 字节）");
        }
        finally
        {
            buffer.release();
        }
    }

    /** neoforge 腿：全类型集 + 全量配方 + tag 表。 */
    public static void sendNeoForge(ServerPlayer player, MinecraftServer server)
    {
        RecipeMap recipeMap = server.getRecipeManager().recipes;
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try
        {
            List<RecipeType<?>> allRecipeTypes = BuiltInRegistries.RECIPE_TYPE.stream().toList();
            var payload = NeoforgeRecipeSyncPayload.create(allRecipeTypes, recipeMap);
            NeoforgeRecipeSyncPayload.STREAM_CODEC.encode(buffer, payload);

            byte[] bytes = new byte[buffer.writerIndex()];
            buffer.getBytes(0, bytes);
            JeiPacketSender.send(player, JeiReference.CHANNEL_NEOFORGE_RECIPE_CONTENT, bytes);

            // neoforge 客户端除配方外还需 tag 表（原版行为）
            player.connection.send(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(server.registries())));
            Reference.logger().info("[JEI] neoforge 配方同步 → " + player.getName().getString()
                    + "（" + payload.recipes().size() + " 配方 / " + bytes.length + " 字节）");
        }
        finally
        {
            buffer.release();
        }
    }
}
