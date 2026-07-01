package verymc.top.veryMcProto.mod.jeirecipebridge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.nms.Nms;
import verymc.top.veryMcProto.mod.jeirecipebridge.config.JeiConfiguration;
import verymc.top.veryMcProto.mod.jeirecipebridge.payload.FabricRecipeSyncPayload;
import verymc.top.veryMcProto.mod.jeirecipebridge.payload.NeoforgeRecipeSyncPayload;

/**
 * 玩家进服配方同步监听器（mod 层）。照抄原版 {@code com.mrbysco.jeicompat.RecipeHandler}，
 * 适配框架：复用 {@link Nms#toNms}、{@link Reference#logger()}（JUL）、单玩家 try-catch 隔离、buffer {@code finally release}。
 *
 * <p>玩家 join 时读取服务端 {@link RecipeManager} 全配方表，按客户端 brand 序列化为 fabric / neoforge 的
 * 原版 custom payload，经 NMS {@link ClientboundCustomPayloadPacket} 直发（绕过 plugin messaging size 上限——
 * 配方包通常远超 32KiB，走 Bukkit {@code sendPluginMessage} 会被拒）。
 *
 * <p><b>brand 时机</b>：client brand 由客户端在登录阶段发出，{@link Player#getClientBrandName()} 在
 * {@link PlayerJoinEvent} 时通常已就绪；若为 {@code null}（极少数异步时序）则跳过本次同步——与原版一致。
 *
 * <p><b>隔离</b>：单个玩家同步异常被 {@code try-catch} 吞掉并记日志，绝不影响其他玩家或服务端运行。
 */
public class RecipeSyncHandler implements Listener
{
    @EventHandler
    public void onJoin(PlayerJoinEvent event)
    {
        try
        {
            // 模块门控：/jei disable 后跳过进服同步（通道仍声明、不踢人）。
            final JeiConfiguration config = JeiConfiguration.getInstance();
            if (config == null)
            {
                Reference.logger().warning("[JEIRecipeBridge] 配置未初始化，跳过进服同步: " + event.getPlayer().getName());
                return;
            }
            if (!config.isEnabled())
            {
                return;
            }
            handle(event.getPlayer());
        }
        catch (Exception e)
        {
            Reference.logger().warning("[JEIRecipeBridge] 同步配方失败 (" + event.getPlayer().getName() + "): " + e.getMessage());
        }
    }

    private static void handle(Player originalPlayer)
    {
        final ServerPlayer player = Nms.toNms(originalPlayer);
        final MinecraftServer server = player.level().getServer();
        String brand = originalPlayer.getClientBrandName();
        if (brand == null)
        {
            return; // 客户端品牌未知，不发任何 custom payload（避免误发被客户端丢弃）
        }

        final RecipeManager recipeManager = server.getRecipeManager();
        final RecipeMap recipeMap = recipeManager.recipes;

        originalPlayer.sendMessage("§6VeryMcProto JEI Compat: Syncing Recipes...§r");

        if (brand.equalsIgnoreCase("fabric"))
        {
            sendFabricPayload(player, server, recipeMap);
        }
        else if (brand.equalsIgnoreCase("neoforge"))
        {
            sendNeoForgePayload(player, server, recipeMap);
        }
    }

    private static void sendNeoForgePayload(ServerPlayer player, MinecraftServer server, RecipeMap recipeMap)
    {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try
        {
            List<RecipeType<?>> allRecipeTypes = BuiltInRegistries.RECIPE_TYPE.stream().toList();
            var payload = NeoforgeRecipeSyncPayload.create(allRecipeTypes, recipeMap);
            NeoforgeRecipeSyncPayload.STREAM_CODEC.encode(buffer, payload);

            byte[] bytes = new byte[buffer.writerIndex()];
            buffer.getBytes(0, bytes);

            sendPayload(player, JeiRecipeBridgeReference.CHANNEL_NEOFORGE, bytes);

            // neoforge 客户端除配方外还需 tag 表（原版行为）
            player.connection.send(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(server.registries())));
        }
        finally
        {
            buffer.release();
        }
    }

    private static void sendFabricPayload(ServerPlayer player, MinecraftServer server, RecipeMap recipeMap)
    {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try
        {
            var list = new ArrayList<FabricRecipeSyncPayload.Entry>();
            var seen = new HashSet<RecipeSerializer<?>>();

            for (RecipeSerializer<?> serializer : BuiltInRegistries.RECIPE_SERIALIZER)
            {
                if (!seen.add(serializer)) continue; // skip duplicates

                List<RecipeHolder<?>> recipes = new ArrayList<>();
                for (RecipeHolder<?> holder : recipeMap.values())
                {
                    if (holder.value().getSerializer() == serializer)
                    {
                        recipes.add(holder);
                    }
                }

                if (!recipes.isEmpty())
                {
                    RecipeSerializer<?> entrySerializer = recipes.get(0).value().getSerializer();
                    list.add(new FabricRecipeSyncPayload.Entry(entrySerializer, recipes));
                }
            }

            var payload = new FabricRecipeSyncPayload(list);
            FabricRecipeSyncPayload.CODEC.encode(buffer, payload);

            byte[] bytes = new byte[buffer.writerIndex()];
            buffer.getBytes(0, bytes);

            sendPayload(player, JeiRecipeBridgeReference.CHANNEL_FABRIC, bytes);
        }
        finally
        {
            buffer.release();
        }
    }

    private static void sendPayload(ServerPlayer player, Identifier id, byte[] bytes)
    {
        player.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes)));
    }
}
