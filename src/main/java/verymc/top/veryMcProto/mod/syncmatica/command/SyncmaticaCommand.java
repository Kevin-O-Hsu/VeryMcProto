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
import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaContext;
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
    private static final String USAGE = "§e/syncmatica §7load [file]";

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

        sender.sendMessage(USAGE);
        return true;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args)
    {
        final List<String> out = new ArrayList<>();
        if (args.length == 1)
        {
            if ("load".startsWith(args[0].toLowerCase()))
            {
                out.add("load");
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
