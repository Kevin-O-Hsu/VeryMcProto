package verymc.top.veryMcProto.mod.carpet.command;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.GameModeArgument;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.OldUsersConverter;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import verymc.top.veryMcProto.mod.carpet.CarpetSettings;
import verymc.top.veryMcProto.mod.carpet.helpers.ActionPackManager;
import verymc.top.veryMcProto.mod.carpet.helpers.EntityPlayerActionPack;
import verymc.top.veryMcProto.mod.carpet.helpers.EntityPlayerActionPack.Action;
import verymc.top.veryMcProto.mod.carpet.helpers.EntityPlayerActionPack.ActionType;
import verymc.top.veryMcProto.mod.carpet.patches.EntityPlayerMPFake;
import verymc.top.veryMcProto.mod.carpet.util.CommandHelper;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

/**
 * /player 命令族（克隆上游 {@code carpet/commands/PlayerCommand.java} 1.21.11 树 362 行 brigadier 树
 * 逐字移植；Paper Brigadier API 载体——上游 Fabric Dispatcher 注册面在 B 侧的等价物，
 * {@code LifecycleEvents.COMMANDS} 注册，偏差登记 docs/carpet/architecture.md）。
 *
 * <p><b>source 类型层</b>：Paper Brigadier 树泛型 = {@link io.papermc.paper.command.brigadier.CommandSourceStack}
 * （Paper 接口；NMS {@code net.minecraft.commands.CommandSourceStack} 实现之）——上游逻辑全部操作 NMS
 * source（getPosition/getRotation/getPlayer/permissions 等），故经 {@link #nms} 单点 cast（实现即 NMS
 * 对象，零开销）。NMS 参数类（Vec3Argument/RotationArgument/GameModeArgument/DimensionArgument——
 * 相对坐标/旋转解析语义）经 paperweight 直连可用，树形近乎逐字。
 *
 * <p>权限 = 根 {@code .requires(canUseCommand(commandPlayer))}（规则门，requires 每次执行求值 →
 * 规则动态性天然成立，上游同构；plugin.yml 无条目——brigadier 注册自足）。
 *
 * <p>与上游的偏差：carpet {@code Messenger} 输出 → {@code sendFailure}/bold 组件（消息文本逐字英文）；
 * 取包 {@code ServerPlayerInterface.getActionPack()} → {@link ActionPackManager#get}。
 */
public class PlayerCommand
{
    /** 建树（CarpetModule 经 LifecycleEvents.COMMANDS 注册）。 */
    public static LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> build()
    {
        LiteralArgumentBuilder<io.papermc.paper.command.brigadier.CommandSourceStack> command = literal("player")
                .requires((player) -> CommandHelper.canUseCommand(nms(player), CarpetSettings.commandPlayer))
                .then(argument("player", StringArgumentType.word())
                        .suggests((c, b) -> suggest(getPlayerSuggestions(nms(c.getSource())), b))
                        .then(literal("stop").executes(manipulation(EntityPlayerActionPack::stopAll)))
                        .then(makeActionCommand("use", ActionType.USE))
                        .then(makeActionCommand("jump", ActionType.JUMP))
                        .then(makeActionCommand("attack", ActionType.ATTACK))
                        .then(makeActionCommand("drop", ActionType.DROP_ITEM))
                        .then(makeDropCommand("drop", false))
                        .then(makeActionCommand("dropStack", ActionType.DROP_STACK))
                        .then(makeDropCommand("dropStack", true))
                        .then(makeActionCommand("swapHands", ActionType.SWAP_HANDS))
                        .then(literal("hotbar")
                                .then(argument("slot", IntegerArgumentType.integer(1, 9))
                                        .executes(c -> manipulate(c, ap -> ap.setSlot(IntegerArgumentType.getInteger(c, "slot"))))))
                        .then(literal("kill").executes(PlayerCommand::kill))
                        .then(literal("shadow"). executes(PlayerCommand::shadow))
                        .then(literal("mount").executes(manipulation(ap -> ap.mount(true)))
                                .then(literal("anything").executes(manipulation(ap -> ap.mount(false)))))
                        .then(literal("dismount").executes(manipulation(EntityPlayerActionPack::dismount)))
                        .then(literal("sneak").executes(manipulation(ap -> ap.setSneaking(true))))
                        .then(literal("unsneak").executes(manipulation(ap -> ap.setSneaking(false))))
                        .then(literal("sprint").executes(manipulation(ap -> ap.setSprinting(true))))
                        .then(literal("unsprint").executes(manipulation(ap -> ap.setSprinting(false))))
                        .then(literal("look")
                                .then(literal("north").executes(manipulation(ap -> ap.look(Direction.NORTH))))
                                .then(literal("south").executes(manipulation(ap -> ap.look(Direction.SOUTH))))
                                .then(literal("east").executes(manipulation(ap -> ap.look(Direction.EAST))))
                                .then(literal("west").executes(manipulation(ap -> ap.look(Direction.WEST))))
                                .then(literal("up").executes(manipulation(ap -> ap.look(Direction.UP))))
                                .then(literal("down").executes(manipulation(ap -> ap.look(Direction.DOWN))))
                                .then(literal("at").then(argument("position", Vec3Argument.vec3())
                                        .executes(c -> manipulate(c, ap -> ap.lookAt(c.getArgument("position", net.minecraft.commands.arguments.coordinates.Coordinates.class).getPosition(nms(c.getSource())))))))
                                .then(argument("direction", RotationArgument.rotation())
                                        .executes(c -> manipulate(c, ap -> ap.look(c.getArgument("direction", net.minecraft.commands.arguments.coordinates.Coordinates.class).getRotation(nms(c.getSource()))))))
                        ).then(literal("turn")
                                .then(literal("left").executes(manipulation(ap -> ap.turn(-90, 0))))
                                .then(literal("right").executes(manipulation(ap -> ap.turn(90, 0))))
                                .then(literal("back").executes(manipulation(ap -> ap.turn(180, 0))))
                                .then(argument("rotation", RotationArgument.rotation())
                                        .executes(c -> manipulate(c, ap -> ap.turn(c.getArgument("rotation", net.minecraft.commands.arguments.coordinates.Coordinates.class).getRotation(nms(c.getSource()))))))
                        ).then(literal("move").executes(manipulation(EntityPlayerActionPack::stopMovement))
                                .then(literal("forward").executes(manipulation(ap -> ap.setForward(1))))
                                .then(literal("backward").executes(manipulation(ap -> ap.setForward(-1))))
                                .then(literal("left").executes(manipulation(ap -> ap.setStrafing(1))))
                                .then(literal("right").executes(manipulation(ap -> ap.setStrafing(-1))))
                        ).then(literal("spawn").executes(PlayerCommand::spawn)
                                .then(literal("in").requires(requiresGamemastersPredicate())
                                        .then(argument("gamemode", GameModeArgument.gameMode())
                                        .executes(PlayerCommand::spawn)))
                                .then(literal("at").then(argument("position", Vec3Argument.vec3()).executes(PlayerCommand::spawn)
                                        .then(literal("facing").then(argument("direction", RotationArgument.rotation()).executes(PlayerCommand::spawn)
                                                .then(literal("in").then(argument("dimension", DimensionArgument.dimension()).executes(PlayerCommand::spawn)
                                                        .then(literal("in").requires(requiresGamemastersPredicate())
                                                                .then(argument("gamemode", GameModeArgument.gameMode())
                                                                .executes(PlayerCommand::spawn)
                                                        )))
                                        )))
                                ))
                        )
                );
        return command.build();
    }

    /** NMS source cast（Paper 接口的实现即 NMS CommandSourceStack，见类 javadoc source 类型层）。 */
    private static net.minecraft.commands.CommandSourceStack nms(io.papermc.paper.command.brigadier.CommandSourceStack source)
    {
        return (net.minecraft.commands.CommandSourceStack) source;
    }

    /** gamemode 节点 requires 谓词（上游 Commands.hasPermission(LEVEL_GAMEMASTERS) 的 Paper 谓词形态）。 */
    private static java.util.function.Predicate<io.papermc.paper.command.brigadier.CommandSourceStack> requiresGamemastersPredicate()
    {
        return source -> net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(nms(source).permissions());
    }

    /** gamemasters 级判定（cantSpawn 白名单分支，上游 LEVEL_GAMEMASTERS.check 同构）。 */
    private static boolean requiresGamemasters(net.minecraft.commands.CommandSourceStack source)
    {
        return net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(source.permissions());
    }

    private static LiteralArgumentBuilder<io.papermc.paper.command.brigadier.CommandSourceStack> makeActionCommand(String actionName, ActionType type)
    {
        return literal(actionName)
                .executes(manipulation(ap -> ap.start(type, Action.once())))
                .then(literal("once").executes(manipulation(ap -> ap.start(type, Action.once()))))
                .then(literal("continuous").executes(manipulation(ap -> ap.start(type, Action.continuous()))))
                .then(literal("interval").then(argument("ticks", IntegerArgumentType.integer(1))
                        .executes(c -> manipulate(c, ap -> ap.start(type, Action.interval(IntegerArgumentType.getInteger(c, "ticks")))))));
    }

    private static ArgumentBuilder<io.papermc.paper.command.brigadier.CommandSourceStack, ?> makeDropCommand(String actionName, boolean dropAll)
    {
        return literal(actionName)
                .then(literal("all").executes(manipulation(ap -> ap.drop(-2, dropAll))))
                .then(literal("mainhand").executes(manipulation(ap -> ap.drop(-1, dropAll))))
                .then(literal("offhand").executes(manipulation(ap -> ap.drop(40, dropAll))))
                .then(argument("slot", IntegerArgumentType.integer(0, 40)).
                        executes(c -> manipulate(c, ap -> ap.drop(IntegerArgumentType.getInteger(c, "slot"), dropAll))));
    }

    private static Collection<String> getPlayerSuggestions(net.minecraft.commands.CommandSourceStack source)
    {
        Set<String> players = new LinkedHashSet<>(List.of("Steve", "Alex"));
        players.addAll(source.getOnlinePlayerNames());
        return players;
    }

    private static ServerPlayer getPlayer(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> context)
    {
        String playerName = StringArgumentType.getString(context, "player");
        MinecraftServer server = nms(context.getSource()).getServer();
        return server.getPlayerList().getPlayerByName(playerName);
    }

    private static boolean cantManipulate(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> context)
    {
        Player player = getPlayer(context);
        net.minecraft.commands.CommandSourceStack source = nms(context.getSource());
        if (player == null)
        {
            source.sendFailure(Component.literal("Can only manipulate existing players"));
            return true;
        }
        Player sender = source.getPlayer();
        if (sender == null)
        {
            return false;
        }

        if (!source.getServer().getPlayerList().isOp(sender.nameAndId()))
        {
            if (sender != player && !(player instanceof EntityPlayerMPFake))
            {
                source.sendFailure(Component.literal("Non OP players can't control other real players"));
                return true;
            }
        }
        return false;
    }

    private static boolean cantReMove(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> context)
    {
        if (cantManipulate(context)) return true;
        Player player = getPlayer(context);
        if (player instanceof EntityPlayerMPFake) return false;
        nms(context.getSource()).sendFailure(Component.literal("Only fake players can be moved or killed"));
        return true;
    }

    private static boolean cantSpawn(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> context)
    {
        String playerName = StringArgumentType.getString(context, "player");
        net.minecraft.commands.CommandSourceStack source = nms(context.getSource());
        MinecraftServer server = source.getServer();
        PlayerList manager = server.getPlayerList();

        if (EntityPlayerMPFake.isSpawningPlayer(playerName))
        {
            source.sendFailure(Component.literal("Player " + playerName + " is currently logging on"));
            return true;
        }
        if (manager.getPlayerByName(playerName) != null)
        {
            source.sendFailure(Component.literal("Player " + playerName + " is already logged on"));
            return true;
        }
        UUID uuid = OldUsersConverter.convertMobOwnerIfNecessary(server, playerName);
        if (uuid == null)
        {
            if (!CarpetSettings.allowSpawningOfflinePlayers)
            {
                source.sendFailure(Component.literal("Player " + playerName + " is either banned by Mojang, or auth servers are down. " +
                        "Banned players can only be summoned in Singleplayer and in servers in off-line mode."));
                return true;
            } else {
                uuid = UUIDUtil.createOfflinePlayerUUID(playerName);
            }
        }
        NameAndId profile = server.services().nameToIdCache().get(uuid).orElse(null);
        if (profile == null)
        {
            if (!CarpetSettings.allowSpawningOfflinePlayers)
            {
                source.sendFailure(Component.literal("Player " + playerName + " is either banned by Mojang, or auth servers are down. " +
                        "Banned players can only be summoned in Singleplayer and in servers in off-line mode."));
                return true;
            } else {
                profile = new NameAndId(UUIDUtil.createOfflinePlayerUUID(playerName), playerName);
            }
        }
        if (manager.getBans().isBanned(profile))
        {
            source.sendFailure(Component.literal("Player " + playerName + " is banned on this server"));
            return true;
        }
        if (manager.isUsingWhitelist() && manager.isWhiteListed(profile) && !requiresGamemasters(source))
        {
            source.sendFailure(Component.literal("Whitelisted players can only be spawned by operators"));
            return true;
        }
        return false;
    }

    private static int kill(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> context)
    {
        if (cantReMove(context)) return 0;
        ServerPlayer player = getPlayer(context);
        player.kill(player.level());
        return 1;
    }

    @FunctionalInterface
    interface SupplierWithCSE<T>
    {
        T get() throws CommandSyntaxException;
    }

    private static <T> T getArgOrDefault(SupplierWithCSE<T> getter, T defaultValue) throws CommandSyntaxException
    {
        try
        {
            return getter.get();
        }
        catch (IllegalArgumentException e)
        {
            return defaultValue;
        }
    }

    private static int spawn(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> context) throws CommandSyntaxException
    {
        if (cantSpawn(context)) return 0;

        net.minecraft.commands.CommandSourceStack source = nms(context.getSource());
        Vec3 pos = getArgOrDefault(
                () -> context.getArgument("position", net.minecraft.commands.arguments.coordinates.Coordinates.class).getPosition(source),
                source.getPosition()
        );
        Vec2 facing = getArgOrDefault(
                () -> context.getArgument("direction", net.minecraft.commands.arguments.coordinates.Coordinates.class).getRotation(source),
                source.getRotation()
        );
        ResourceKey<Level> dimType = getArgOrDefault(
                () -> context.getArgument("dimension", net.minecraft.server.level.ServerLevel.class).dimension(),
                source.getLevel().dimension()
        );
        GameType mode = GameType.CREATIVE;
        boolean flying = false;
        if (source.getEntity() instanceof ServerPlayer sender)
        {
            mode = sender.gameMode.getGameModeForPlayer();
            flying = sender.getAbilities().flying;
        }
        try {
            mode = context.getArgument("gamemode", GameType.class);
        } catch (IllegalArgumentException notPresent) {}

        if (mode == GameType.SPECTATOR)
        {
            // Force override flying to true for spectator players, or they will fell out of the world.
            flying = true;
        } else if (mode.isSurvival())
        {
            // Force override flying to false for survival-like players, or they will fly too
            flying = false;
        }
        String playerName = StringArgumentType.getString(context, "player");
        if (playerName.length() > maxNameLength(source.getServer()))
        {
            source.sendFailure(Component.literal("Player name: " + playerName + " is too long").withStyle(ChatFormatting.BOLD));
            return 0;
        }

        if (!Level.isInSpawnableBounds(BlockPos.containing(pos)))
        {
            source.sendFailure(Component.literal("Player " + playerName + " cannot be placed outside of the world").withStyle(ChatFormatting.BOLD));
            return 0;
        }
        boolean success = EntityPlayerMPFake.createFake(playerName, source.getServer(), pos, facing.y, facing.x, dimType, mode, flying);
        if (!success) {
            source.sendFailure(Component.literal("Player " + playerName + " doesn't exist and cannot spawn in online mode. " +
                    "Turn the server offline or the allowSpawningOfflinePlayers on to spawn non-existing players").withStyle(ChatFormatting.BOLD));
            return 0;
        }
        return 1;
    }

    private static int maxNameLength(MinecraftServer server)
    {
        return server.getPort() >= 0 ? SharedConstants.MAX_PLAYER_NAME_LENGTH : 40;
    }

    private static int manipulate(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> context, Consumer<EntityPlayerActionPack> action)
    {
        if (cantManipulate(context)) return 0;
        ServerPlayer player = getPlayer(context);
        action.accept(ActionPackManager.get(player));
        return 1;
    }

    private static Command<io.papermc.paper.command.brigadier.CommandSourceStack> manipulation(Consumer<EntityPlayerActionPack> action)
    {
        return c -> manipulate(c, action);
    }

    private static int shadow(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> context)
    {
        if (cantManipulate(context)) return 0;

        ServerPlayer player = getPlayer(context);
        if (player instanceof EntityPlayerMPFake)
        {
            nms(context.getSource()).sendFailure(Component.literal("Cannot shadow fake players"));
            return 0;
        }
        if (player.level().getServer().isSingleplayerOwner(player.nameAndId())) {
            nms(context.getSource()).sendFailure(Component.literal("Cannot shadow single-player server owner"));
            return 0;
        }

        EntityPlayerMPFake shadow = EntityPlayerMPFake.createShadow(player.level().getServer(), player);
        if (shadow == null)
        {
            // Paper 特有守卫命中：重复登录踢线被第三方插件取消，目标未离场——中止防同 UUID 双实体
            nms(context.getSource()).sendFailure(Component.literal("Cannot shadow " + player.getGameProfile().name() + ": duplicate-login disconnect was cancelled by another plugin"));
            return 0;
        }
        return 1;
    }
}
