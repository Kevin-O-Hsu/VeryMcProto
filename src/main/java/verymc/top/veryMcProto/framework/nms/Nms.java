package verymc.top.veryMcProto.framework.nms;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Bukkit ↔ NMS 转换工具（框架层）。
 *
 * <p>paperweight userdev 提供 Mojang 全映射，CraftBukkit 实现类以 {@code org.bukkit.craftbukkit.*}
 * （无 MC 版本段，paperweight remap 处理）可见；{@code getHandle()}/{@code getServer()} 返回 NMS 对象。
 */
public final class Nms
{
    private Nms() { }

    /** Bukkit Player → NMS ServerPlayer。 */
    public static ServerPlayer toNms(Player player)
    {
        return ((CraftPlayer) player).getHandle();
    }

    /** Bukkit World → NMS ServerLevel。 */
    public static ServerLevel toNms(World world)
    {
        return ((CraftWorld) world).getHandle();
    }

    /** 取 NMS MinecraftServer（CraftServer.getServer()）。 */
    public static MinecraftServer server()
    {
        return ((CraftServer) Bukkit.getServer()).getServer();
    }

    /** NMS ServerPlayer → Bukkit Player。 */
    public static Player toBukkit(ServerPlayer player)
    {
        return player.getBukkitEntity();
    }

    /** NMS ServerLevel → Bukkit World。 */
    public static World toBukkit(ServerLevel level)
    {
        return level.getWorld();
    }
}
