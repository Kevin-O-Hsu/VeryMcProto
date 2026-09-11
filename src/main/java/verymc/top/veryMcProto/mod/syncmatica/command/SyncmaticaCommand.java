package verymc.top.veryMcProto.mod.syncmatica.command;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.apache.commons.lang3.tuple.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaDebug;
import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaContext;
import verymc.top.veryMcProto.mod.syncmatica.app.SyncmaticaModule;
import verymc.top.veryMcProto.mod.syncmatica.communication.ExchangeTarget;
import verymc.top.veryMcProto.mod.syncmatica.communication.ServerCommunicationManager;
import verymc.top.veryMcProto.mod.syncmatica.data.ServerPlacement;
import verymc.top.veryMcProto.mod.syncmatica.data.ServerPosition;
import verymc.top.veryMcProto.mod.syncmatica.data.litematica.SchematicMetadata;
import verymc.top.veryMcProto.mod.syncmatica.data.litematica.SchematicSchema;
import verymc.top.veryMcProto.mod.syncmatica.extended_core.PlayerIdentifier;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaLog;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaUtil;

/**
 * /syncmatica 命令（移植自 {@code ch.endte.syncmatica.command.SyncmaticaCommand}）。
 *
 * <p>原版用 Fabric/Mojang Brigadier（{@code CommandDispatcher<CommandSourceStack>}）→ Paper 用 Bukkit
 * {@link CommandExecutor}/{@link TabCompleter}（plugin.yml 注册），同 {@code ServuxCommand} 模式。
 *
 * <p>子命令仅 {@code load}（{@code load_all} + {@code load <file>}）：从 {@code syncmatics/} 目录把
 * {@code .litematic} 注册为 placement 并广播。上传/下载/修改/删除全走协议 exchange。
 *
 * <p><b>权限</b>：{@code syncmatica.command} / {@code .load} / {@code .load_each}（plugin.yml default: true）。
 */
public class SyncmaticaCommand implements CommandExecutor, TabCompleter
{
    private static final String USAGE = "§e/syncmatica §7status|save|reload|enable|disable|load [file]|debug [on|off|cat|status|s2c]";

    private final SyncmaticaContext context;
    private final HashMap<Path, Pair<SchematicMetadata, SchematicSchema>> files = new HashMap<>();

    public SyncmaticaCommand(final SyncmaticaContext context)
    {
        this.context = context;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args)
    {
        if (!sender.hasPermission("syncmatica.command"))
        {
            sender.sendMessage("§c权限不足。");
            return true;
        }
        if (args.length == 0)
        {
            sender.sendMessage(USAGE);
            return true;
        }

        if (args[0].equalsIgnoreCase("load"))
        {
            if (!sender.hasPermission("syncmatica.command.load"))
            {
                sender.sendMessage("§c权限不足。");
                return true;
            }
            if (args.length >= 2)
            {
                if (!sender.hasPermission("syncmatica.command.load_each"))
                {
                    sender.sendMessage("§c权限不足。");
                    return true;
                }
                doLoadEach(sender, args[1]);
            }
            else
            {
                doLoadAll(sender);
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("debug"))
        {
            if (!sender.hasPermission("syncmatica.command.debug"))
            {
                sender.sendMessage("§c权限不足。");
                return true;
            }
            handleDebug(sender, args);
            return true;
        }

        if (args[0].equalsIgnoreCase("status"))
        {
            if (requireAdmin(sender)) { handleStatus(sender); }
            return true;
        }
        if (args[0].equalsIgnoreCase("save"))
        {
            if (requireAdmin(sender)) { context.saveConfiguration(); sender.sendMessage("§aSyncmatica 配置已保存。"); }
            return true;
        }
        if (args[0].equalsIgnoreCase("reload"))
        {
            if (requireAdmin(sender)) { context.loadConfiguration(); sender.sendMessage("§aSyncmatica 配置已重载。"); }
            return true;
        }
        if (args[0].equalsIgnoreCase("enable") || args[0].equalsIgnoreCase("disable"))
        {
            if (requireAdmin(sender)) { handleToggleProtocol(sender, args[0].equalsIgnoreCase("enable")); }
            return true;
        }

        sender.sendMessage(USAGE);
        return true;
    }

    /** 检查 admin 权限（save/reload/enable/disable/status），不足则提示并返回 false。 */
    private boolean requireAdmin(final CommandSender sender)
    {
        if (!sender.hasPermission("syncmatica.command.admin"))
        {
            sender.sendMessage("§c权限不足。");
            return false;
        }
        return true;
    }

    /** /syncmatica status —— 显示模块状态（协议启停 + 调试状态 + 配置文件）。 */
    private void handleStatus(final CommandSender sender)
    {
        sender.sendMessage("§6Syncmatica 状态:");
        sender.sendMessage(" §7协议: §f" + (context.isProtocolEnabled() ? "§a启用 (ON)" : "§c禁用 (OFF)"));
        sender.sendMessage(" §7调试: §f" + SyncmaticaDebug.statusLine());
        sender.sendMessage(" §7配置: §f" + context.getConfigFile().getFileName());
    }

    /** /syncmatica enable|disable —— 软禁用/启用整个协议（通道保留，不踢人）。 */
    private void handleToggleProtocol(final CommandSender sender, final boolean enable)
    {
        final SyncmaticaModule module = SyncmaticaModule.getInstance();
        if (module == null)
        {
            sender.sendMessage("§cSyncmatica 模块未加载。");
            return;
        }
        if (enable)
        {
            if (context.isProtocolEnabled()) { sender.sendMessage("§eSyncmatica 协议已处于启用状态。"); return; }
            context.resumeProtocol();
            module.reconnectOnlinePlayers();
            sender.sendMessage("§aSyncmatica 协议已启用 §7（在线玩家将重新握手）");
        }
        else
        {
            if (!context.isProtocolEnabled()) { sender.sendMessage("§eSyncmatica 协议已处于禁用状态。"); return; }
            context.suspendProtocol();
            sender.sendMessage("§eSyncmatica 协议已禁用 §7（软禁用：通道保留、玩家不会被踢；进行中的传输已中断）");
        }
    }

    /**
     * /syncmatica debug —— 调试日志宏开关热切换（运行时即时生效，使用 syncmatica 独立的 SyncmaticaDebug，与 /servux debug 互不影响）。
     *
     * <p>用法（master 总开关与分类正交，两者皆开才输出）：
     * <ul>
     *   <li>{@code /syncmatica debug} / {@code status} —— 查看状态；</li>
     *   <li>{@code /syncmatica debug on|off} —— 总开关死活（仅 master，不碰分类）；</li>
     *   <li>{@code /syncmatica debug cat all|none} —— 全开/清空分类；</li>
     *   <li>{@code /syncmatica debug cat <name>} —— 切换单个分类（lifecycle/handshake/network/packet/exchange/data）。</li>
     * </ul>
     * <p>诊断 syncmatica 不可用：先 {@code /syncmatica debug on}，再 {@code /syncmatica debug cat all}（或单独 handshake/network/packet），
     * 观察握手链路：声明通道 → tryStartHandshake → init 推 REGISTER_VERSION → 客户端回版本 → FeatureSet → CONFIRM_USER → broadcastTargets。
     * 命令切换即时持久化到 {@code syncmatica-config.json}（master + 分类各自独立保存），重启后完全恢复。
     */
    private void handleDebug(final CommandSender sender, final String[] args)
    {
        if (args.length < 2)
        {
            sender.sendMessage("§6调试状态: §f" + SyncmaticaDebug.statusLine());
            sender.sendMessage("§7用法: §f/syncmatica debug <on|off|status>§7 —— master 总开关 / 状态");
            sender.sendMessage("§7用法: §f/syncmatica debug cat <all|none|分类名>§7 —— 分类（master 与分类正交，两者皆开才输出）");
            sender.sendMessage("§7用法: §f/syncmatica debug s2c <nms|msg>§7 —— S2C 路径（NMS 直发 / plugin messaging）");
            sender.sendMessage("§7分类: §flifecycle handshake network packet exchange data");
            return;
        }

        final String sub = args[1].toLowerCase();
        switch (sub)
        {
            case "on" ->
            {
                SyncmaticaDebug.setMaster(true);
                final String tip = SyncmaticaDebug.active().isEmpty() ? " §7(分类为空，用 §f/syncmatica debug cat all§7 开全分类)" : "";
                sender.sendMessage("§a调试总开关已开启 §7(仅 master): §f" + SyncmaticaDebug.statusLine() + tip);
                context.saveConfiguration();
            }
            case "off" -> { SyncmaticaDebug.setMaster(false); sender.sendMessage("§e调试总开关已关闭 §7(仅 master): §f" + SyncmaticaDebug.statusLine()); context.saveConfiguration(); }
            case "status" -> sender.sendMessage("§6调试状态: §f" + SyncmaticaDebug.statusLine());
            case "s2c" ->
            {
                // S2C 发送路径切换（诊断 plugin messaging vs NMS DiscardedPayload 直发哪条客户端能响应）。不持久化（路由诊断开关，非 debug 日志开关）。
                final boolean now = ExchangeTarget.S2C_VIA_NMS;
                if (args.length < 3)
                {
                    sender.sendMessage("§6S2C 路径: §f" + (now ? "NMS-DiscardedPayload" : "plugin-messaging"));
                    sender.sendMessage("§7用法: §f/syncmatica debug s2c <nms|msg>");
                    return;
                }
                final String mode = args[2].toLowerCase();
                if (mode.equals("nms")) { ExchangeTarget.S2C_VIA_NMS = true; }
                else if (mode.equals("msg")) { ExchangeTarget.S2C_VIA_NMS = false; }
                else { sender.sendMessage("§c未知: " + mode + " §7(nms|msg)"); return; }
                sender.sendMessage("§aS2C 路径 → §f" + (ExchangeTarget.S2C_VIA_NMS ? "NMS-DiscardedPayload" : "plugin-messaging"));
            }
            case "cat" ->
            {
                if (args.length < 3) { sender.sendMessage("§e/syncmatica debug cat <all|none|分类名>"); return; }
                final String catName = args[2].toLowerCase();
                if (catName.equals("all")) { SyncmaticaDebug.enableAll(); sender.sendMessage("§a已开启全分类: §f" + SyncmaticaDebug.statusLine()); context.saveConfiguration(); return; }
                if (catName.equals("none")) { SyncmaticaDebug.clearCats(); sender.sendMessage("§e已清空全分类: §f" + SyncmaticaDebug.statusLine()); context.saveConfiguration(); return; }
                final SyncmaticaDebug.Cat cat = SyncmaticaDebug.parseCat(args[2]);
                if (cat == null) { sender.sendMessage("§c未知分类: " + args[2] + " §7(all|none|分类名)"); return; }
                final boolean now = SyncmaticaDebug.toggle(cat);
                sender.sendMessage("§a分类 " + cat.name().toLowerCase() + " → " + (now ? "§aON" : "§cOFF"));
                sender.sendMessage("§7当前: §f" + SyncmaticaDebug.statusLine());
                context.saveConfiguration();
            }
            default -> sender.sendMessage("§c未知子命令: " + sub + " §7(on/off/cat/status/s2c)");
        }
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args)
    {
        final List<String> out = new ArrayList<>();
        if (args.length == 1)
        {
            for (final String s : List.of("status", "save", "reload", "enable", "disable", "load", "debug"))
            {
                if (s.startsWith(args[0].toLowerCase())) { out.add(s); }
            }
        }
        else if (args.length == 2 && args[0].equalsIgnoreCase("load"))
        {
            if (files.isEmpty())
            {
                updateSyncmaticDir();
            }
            for (final Path p : files.keySet())
            {
                final String name = ServerPlacement.removeExtension(p.getFileName());
                if (name.toLowerCase().startsWith(args[1].toLowerCase()))
                {
                    out.add(name);
                }
            }
        }
        else if (args.length == 2 && args[0].equalsIgnoreCase("debug"))
        {
            for (final String s : List.of("on", "off", "cat", "status", "s2c"))
            {
                if (s.startsWith(args[1].toLowerCase())) { out.add(s); }
            }
        }
        else if (args.length == 3 && args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("s2c"))
        {
            for (final String s : List.of("nms", "msg"))
            {
                if (s.startsWith(args[2].toLowerCase())) { out.add(s); }
            }
        }
        else if (args.length == 3 && args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("cat"))
        {
            for (final String s : List.of("all", "none"))
            {
                if (s.startsWith(args[2].toLowerCase())) { out.add(s); }
            }
            for (final SyncmaticaDebug.Cat c : SyncmaticaDebug.Cat.values())
            {
                final String n = c.name().toLowerCase();
                if (n.startsWith(args[2].toLowerCase())) { out.add(n); }
            }
        }
        return out;
    }

    public void updateSyncmaticDir()
    {
        final List<Path> list = new ArrayList<>();
        this.files.clear();

        final Path dir = context.getLitematicFolder();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir))
        {
            for (final Path file : stream)
            {
                if (!Files.isDirectory(file))
                {
                    list.add(file);
                }
            }
        }
        catch (final Exception e)
        {
            SyncmaticaLog.error("updateSyncmaticDir(): Exception reading directory '{}'; {}", dir, e.getLocalizedMessage());
        }

        // 仅列出未加载的文件（文件名 = hash UUID，去掉 .litematic 后缀）
        list.forEach(p ->
        {
            final String name = ServerPlacement.removeExtension(p.getFileName());
            try
            {
                final UUID hash = UUID.fromString(name);
                if (!context.getSyncmaticManager().hasPlacementHash(hash))
                {
                    final Pair<SchematicMetadata, SchematicSchema> pair = SyncmaticaUtil.litematicPeek(p);
                    if (pair.getLeft() != null)
                    {
                        this.files.put(p, pair);
                    }
                }
            }
            catch (final IllegalArgumentException ignored)
            {
                // 文件名非 UUID（非 <hash>.litematic 命名），跳过
            }
        });
    }

    private void doLoadAll(final CommandSender sender)
    {
        if (this.files.isEmpty())
        {
            this.updateSyncmaticDir();
        }
        if (this.files.isEmpty())
        {
            sender.sendMessage("§7No Syncmatic file(s) found that needs to be loaded.");
            return;
        }

        final PlayerIdentifier owner;
        final ServerPosition origin;
        if (sender instanceof final Player p)
        {
            owner = context.getPlayerIdentifierProvider().createOrGet(p.getUniqueId(), p.getName());
            origin = playerOrigin(p);
        }
        else
        {
            owner = context.getPlayerIdentifierProvider().createOrGet(UUID.randomUUID(), "Unknown");
            origin = new ServerPosition(new BlockPos(0, 0, 0), ServerPosition.OVERWORLD_DIMENSION_ID);
        }

        int count = 0;
        for (final var entry : files.entrySet())
        {
            if (loadEach(sender, entry.getKey(), entry.getValue().getLeft(), entry.getValue().getRight(), owner, origin))
            {
                count++;
            }
        }

        sender.sendMessage("§b" + String.format("%02d", count) + "§r Syncmatic file(s) found / loaded.");
        this.updateSyncmaticDir();
    }

    private void doLoadEach(final CommandSender sender, final String result)
    {
        if (this.files.isEmpty())
        {
            this.updateSyncmaticDir();
        }

        final Path dir = context.getLitematicFolder();
        final Path file = dir.resolve(result + ".litematic");
        final Pair<SchematicMetadata, SchematicSchema> pair = SyncmaticaUtil.litematicPeek(file);
        if (pair.getLeft() == null)
        {
            sender.sendMessage("§cFailed to peek file: " + result);
            return;
        }

        final PlayerIdentifier owner;
        final ServerPosition origin;
        if (sender instanceof final Player p)
        {
            owner = context.getPlayerIdentifierProvider().createOrGet(p.getUniqueId(), p.getName());
            origin = playerOrigin(p);
        }
        else
        {
            owner = context.getPlayerIdentifierProvider().createOrGet(UUID.randomUUID(), "Unknown");
            origin = new ServerPosition(new BlockPos(0, 0, 0), ServerPosition.OVERWORLD_DIMENSION_ID);
        }

        if (loadEach(sender, file, pair.getLeft(), pair.getRight(), owner, origin))
        {
            sender.sendMessage("§b01§r Syncmatic file(s) found / loaded.");
        }

        this.updateSyncmaticDir();
    }

    private boolean loadEach(final CommandSender sender, final Path p, final SchematicMetadata meta,
                             final SchematicSchema schema, final PlayerIdentifier owner, final ServerPosition origin)
    {
        final ServerPlacement placement = new ServerPlacement(UUID.randomUUID(), p.normalize(), p.getFileName().toString(), owner);
        placement.move(origin, Rotation.NONE, Mirror.NONE);
        placement.setMetadata(meta);
        placement.setSchema(schema);

        final ServerCommunicationManager comms = (ServerCommunicationManager) context.getCommunicationManager();
        final ExchangeTarget target = (sender instanceof final Player player) ? comms.getOrCreateTarget(player) : null;
        if (target != null)
        {
            comms.addPlacement(target, placement);
        }
        else
        {
            // 控制台执行：无发起方 target，直接注册（玩家进服握手时 CONFIRM_USER 会下发全部 placement）
            context.getSyncmaticManager().addPlacement(placement);
        }

        sender.sendMessage("Loaded Server Placement '§d" + placement.getName() + "§r'");
        return true;
    }

    private ServerPosition playerOrigin(final Player player)
    {
        final var loc = player.getLocation();
        return new ServerPosition(
                new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()),
                player.getWorld().getKey().toString()
        );
    }
}
