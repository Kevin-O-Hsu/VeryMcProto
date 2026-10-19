package verymc.top.veryMcProto.mod.carpet.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.world.phys.Vec3;

import verymc.top.veryMcProto.mod.carpet.CarpetSettings;
import verymc.top.veryMcProto.mod.carpet.util.CommandHelper;
import verymc.top.veryMcProto.mod.carpet.util.DistanceCalculator;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * /distance 命令（上游 {@code carpet/commands/DistanceCommand.java} 1.21.11 树逐字移植；Paper
 * Brigadier 载体——{@code PlayerCommand} 范式，plugin.yml 无条目）。
 *
 * <p><b>树形</b>（上游 :19-41 非对称嵌套链，六分支）：
 * <pre>/distance from                        setStart(执行者当前位)
 * /distance from &lt;from&gt;                setStart(from)
 * /distance from &lt;from&gt; to            distance(from, 当前位)——直算回显，不触碰存储
 * /distance from &lt;from&gt; to &lt;to&gt;     distance(from, to)
 * /distance to                          setEnd(当前位)——无存储起点时回落设起点并回执
 * /distance to &lt;to&gt;                    setEnd(to)</pre>
 *
 * <p>与上游的偏差：参数取值 {@code Vec3Argument.getVec3(ctx, name)}（NMS 泛型上下文签名，paper
 * 树不可直调）→ {@code ctx.getArgument(name, Coordinates.class).getPosition(nms(source))} 等价体
 * （vanilla getVec3 体即此二连调，javap 实证）；裸分支取 {@code source.getPosition()} 上游同构。
 */
public class DistanceCommand
{
    /** 建树（CarpetModule 经 LifecycleEvents.COMMANDS 注册）。 */
    public static LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> build()
    {
        LiteralArgumentBuilder<io.papermc.paper.command.brigadier.CommandSourceStack> command = literal("distance").
                requires((player) -> CommandHelper.canUseCommand(nms(player), CarpetSettings.commandDistance)).
                then(literal("from").
                        executes( (c) -> DistanceCalculator.setStart(nms(c.getSource()), nms(c.getSource()).getPosition())).
                        then(argument("from", Vec3Argument.vec3()).
                                executes( (c) -> DistanceCalculator.setStart(
                                        nms(c.getSource()),
                                        vec3(c, "from"))).
                                then(literal("to").
                                        executes((c) -> DistanceCalculator.distance(
                                                nms(c.getSource()),
                                                vec3(c, "from"),
                                                nms(c.getSource()).getPosition())).
                                        then(argument("to", Vec3Argument.vec3()).
                                                executes( (c) -> DistanceCalculator.distance(
                                                        nms(c.getSource()),
                                                        vec3(c, "from"),
                                                        vec3(c, "to")
                                                )))))).
                then(literal("to").
                        executes( (c) -> DistanceCalculator.setEnd(nms(c.getSource()), nms(c.getSource()).getPosition()) ).
                        then(argument("to", Vec3Argument.vec3()).
                                executes( (c) -> DistanceCalculator.setEnd(nms(c.getSource()), vec3(c, "to")))));
        return command.build();
    }

    /** NMS source cast（Paper 接口的实现即 NMS CommandSourceStack，PlayerCommand 同款单点）。 */
    private static CommandSourceStack nms(io.papermc.paper.command.brigadier.CommandSourceStack source)
    {
        return (CommandSourceStack) source;
    }

    /** Vec3Argument.getVec3 等价体（vanilla 体 = getArgument(Coordinates).getPosition(source)）。 */
    private static Vec3 vec3(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> c, String name)
    {
        return c.getArgument(name, Coordinates.class).getPosition(nms(c.getSource()));
    }
}
