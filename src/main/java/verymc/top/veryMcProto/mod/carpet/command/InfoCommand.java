package verymc.top.veryMcProto.mod.carpet.command;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import verymc.top.veryMcProto.mod.carpet.CarpetSettings;
import verymc.top.veryMcProto.mod.carpet.util.BlockInfo;
import verymc.top.veryMcProto.mod.carpet.util.CommandHelper;
import verymc.top.veryMcProto.mod.carpet.util.Messenger;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * /info 命令（上游 {@code carpet/commands/InfoCommand.java} 1.21.11 树逐字移植；Paper Brigadier 载体，
 * plugin.yml 无条目）——<b>block-only 属性清单</b>（无实体分支，scope 文档旧述 "NBT dump" 已勘误）。
 *
 * <p><b>grep 匹配层</b>：正则跑在 {@code line.getString()} 纯文本层（上游 :56——非 § 码/组件样式层）；
 * 首行恒输出（上游 :52 {@code messages.get(0)} 无条件）。非 gamemasters 级执行者带 chunk-loaded /
 * world-bounds 双守卫拒绝行（上游 :71-82）。
 *
 * <p>与上游的偏差：参数取值 {@code BlockPosArgument.getSpawnablePos} → {@code getArgument(Coordinates)
 * .getBlockPos(nms(source))} 等价体（vanilla 体即此二连调，javap 实证）。
 */
public class InfoCommand
{
    /** 建树（CarpetModule 经 LifecycleEvents.COMMANDS 注册）。 */
    public static LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> build()
    {
        LiteralArgumentBuilder<io.papermc.paper.command.brigadier.CommandSourceStack> command = literal("info").
                requires((player) -> CommandHelper.canUseCommand(nms(player), CarpetSettings.commandInfo)).
                then(literal("block").
                        then(argument("block position", BlockPosArgument.blockPos()).
                                executes( (c) -> infoBlock(
                                        nms(c.getSource()),
                                        blockPos(c, "block position"), null)).
                                then(literal("grep").
                                        then(argument("regexp",greedyString()).
                                                executes( (c) -> infoBlock(
                                                        nms(c.getSource()),
                                                        blockPos(c, "block position"),
                                                        getString(c, "regexp")))))));

        return command.build();
    }

    public static void printBlock(List<Component> messages, CommandSourceStack source, String grep)
    {
        Messenger.m(source, "");
        if (grep != null)
        {
            Pattern p = Pattern.compile(grep);
            Messenger.m(source, messages.get(0));
            for (int i = 1; i<messages.size(); i++)
            {
                Component line = messages.get(i);
                Matcher m = p.matcher(line.getString());
                if (m.find())
                {
                    Messenger.m(source, line);
                }
            }
        }
        else
        {
            Messenger.send(source, messages);
        }
    }

    private static int infoBlock(CommandSourceStack source, BlockPos pos, String grep)
    {
        if (!Commands.LEVEL_GAMEMASTERS.check(source.permissions())) {
            //check id pos is loaded
            if (!source.getLevel().hasChunkAt(pos)) {
                Messenger.m(source, "r Chunk is not loaded");
                return 0;
            }
            // verify it is in world bounds
            if (!source.getLevel().isInWorldBounds(pos)) {
                Messenger.m(source, "r Position is outside of world bounds");
                return 0;
            }
        }
        printBlock(BlockInfo.blockInfo(pos, source.getLevel()),source, grep);
        return 1;
    }

    /** NMS source cast（Paper 接口的实现即 NMS CommandSourceStack，PlayerCommand 同款单点）。 */
    private static CommandSourceStack nms(io.papermc.paper.command.brigadier.CommandSourceStack source)
    {
        return (CommandSourceStack) source;
    }

    /** BlockPosArgument.getSpawnablePos 等价体（vanilla 体 = getArgument(Coordinates).getBlockPos(source)）。 */
    private static BlockPos blockPos(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> c, String name)
    {
        return c.getArgument(name, Coordinates.class).getBlockPos(nms(c.getSource()));
    }
}
