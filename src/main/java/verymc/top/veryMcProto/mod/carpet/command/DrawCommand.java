package verymc.top.veryMcProto.mod.carpet.command;

import com.google.common.collect.Lists;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockPredicateArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import java.lang.Math;

import verymc.top.veryMcProto.mod.carpet.CarpetSettings;
import verymc.top.veryMcProto.mod.carpet.util.CommandHelper;
import verymc.top.veryMcProto.mod.carpet.util.Messenger;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

/**
 * /draw 命令——基础形状族（上游 {@code carpet/commands/DrawCommand.java} 26.2 树逐字移植；Paper
 * Brigadier 载体，plugin.yml 无条目）。七 literal：sphere/ball/diamond（center+radius）、pyramid/cone
 * （+height+pointing+orientation）、cylinder/cuboid（center+radius+height+orientation）；形状算法
 * drawSphere/drawDiamond/fillFlat/drawPyramid/drawPrism 单文件内联（上游同——无 ShapeDispatcher）；
 * plop 与基础形状零代码耦合（上游实证），不在移植面。
 *
 * <p><b>与上游的偏差</b>（登记，docs/carpet-architecture.md）：
 * <ul>
 *   <li><b>fillUpdates 固定 true 语义</b>：上游更新抑制 = {@code impendingFillSkipUpdates}
 *       ThreadLocal（消费端全在 6 个 fillUpdates Mixin，Paper 无 Mixin 物理不可移植）→ B 侧删
 *       ThreadLocal 行，收尾 {@code updateNeighborsAt} 无条件执行（= 上游 fillUpdates=true 路径
 *       逐字；false 路径 = 放置态邻居形状后处理剥离，随 Mixin 一并不可移植）；</li>
 *   <li>参数取值等价体：{@code BlockStateArgument.getBlock} → {@code getArgument(BlockInput)}
 *       （vanilla 体即此，javap 实证）；{@code BlockPredicateArgument.getBlockPredicate} →
 *       {@code getArgument(Result)}（Result implements Predicate，同）；{@code getSpawnablePos} →
 *       Coordinates.getBlockPos；getInt/getString 为 brigadier 泛型通配静态、直用。</li>
 * </ul>
 */
public class DrawCommand
{
    /** 建树（CarpetModule 经 LifecycleEvents.COMMANDS 注册；ctx = BlockState/BlockPredicate 参数类构建上下文）。 */
    public static LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> build(final CommandBuildContext context)
    {
        var command = literal("draw").
                requires((player) -> CommandHelper.canUseCommand(nms(player), CarpetSettings.commandDraw)).
                then(literal("sphere").
                        then(argument("center", BlockPosArgument.blockPos()).
                                then(argument("radius", IntegerArgumentType.integer(1)).
                                        then(drawShape(c -> DrawCommand.drawSphere(c, false), context))))).
                then(literal("ball").
                        then(argument("center", BlockPosArgument.blockPos()).
                                then(argument("radius", IntegerArgumentType.integer(1)).
                                        then(drawShape(c -> DrawCommand.drawSphere(c, true), context))))).
                then(literal("diamond").
                        then(argument("center", BlockPosArgument.blockPos()).
                                then(argument("radius", IntegerArgumentType.integer(1)).
                                        then(drawShape(c -> DrawCommand.drawDiamond(c, true), context))))).
                then(literal("pyramid").
                        then(argument("center", BlockPosArgument.blockPos()).
                                then(argument("radius", IntegerArgumentType.integer(1)).
                                        then(argument("height",IntegerArgumentType.integer(1)).
                                                then(argument("pointing",StringArgumentType.word()).suggests( (c, b) -> suggest(new String[]{"up","down"},b)).
                                                        then(argument("orientation",StringArgumentType.word()).suggests( (c, b) -> suggest(new String[]{"y","x","z"},b)).
                                                                then(drawShape(c -> DrawCommand.drawPyramid(c, "square", true), context)))))))).
                then(literal("cone").
                        then(argument("center", BlockPosArgument.blockPos()).
                                then(argument("radius", IntegerArgumentType.integer(1)).
                                        then(argument("height",IntegerArgumentType.integer(1)).
                                                then(argument("pointing",StringArgumentType.word()).suggests( (c, b) -> suggest(new String[]{"up","down"},b)).
                                                        then(argument("orientation",StringArgumentType.word()).suggests( (c, b) -> suggest(new String[]{"y","x","z"},b))
                                                                .then(drawShape(c -> DrawCommand.drawPyramid(c, "circle", true), context)))))))).
                then(literal("cylinder").
                        then(argument("center", BlockPosArgument.blockPos()).
                                then(argument("radius", IntegerArgumentType.integer(1)).
                                        then(argument("height",IntegerArgumentType.integer(1)).
                                                        then(argument("orientation",StringArgumentType.word()).suggests( (c, b) -> suggest(new String[]{"y","x","z"},b))
                                                                .then(drawShape(c -> DrawCommand.drawPrism(c, "circle"), context))))))).
                then(literal("cuboid").
                        then(argument("center", BlockPosArgument.blockPos()).
                                then(argument("radius", IntegerArgumentType.integer(1)).
                                        then(argument("height",IntegerArgumentType.integer(1)).
                                                then(argument("orientation",StringArgumentType.word()).suggests( (c, b) -> suggest(new String[]{"y","x","z"},b))
                                                        .then(drawShape(c -> DrawCommand.drawPrism(c, "square"), context)))))));
        return command.build();
    }

    @FunctionalInterface
    private interface ArgumentExtractor<T>
    {
        T apply(final CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx, final String argName) throws CommandSyntaxException;
    }

    private static RequiredArgumentBuilder<io.papermc.paper.command.brigadier.CommandSourceStack, BlockInput>
    drawShape(Command<io.papermc.paper.command.brigadier.CommandSourceStack> drawer, CommandBuildContext commandBuildContext)
    {
        return argument("block", BlockStateArgument.block(commandBuildContext)).
                executes(drawer)
                .then(literal("replace")
                        .then(argument("filter", BlockPredicateArgument.blockPredicate(commandBuildContext))
                                .executes(drawer)));
    }

    private static class ErrorHandled extends RuntimeException {}

    private static <T> T getArg(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx, ArgumentExtractor<T> extract, String hwat) throws CommandSyntaxException
    {
        return getArg(ctx, extract, hwat, false);
    }

    private static <T> T getArg(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx, ArgumentExtractor<T> extract, String hwat, boolean optional) throws CommandSyntaxException
    {
        T arg = null;
        try
        {
            arg = extract.apply(ctx, hwat);
        }
        catch (IllegalArgumentException e)
        {
            if (optional) return null;
            Messenger.m(nms(ctx.getSource()), "rb Missing "+hwat);
            throw new ErrorHandled();
        }
        return arg;
    }

    private static double lengthSq(double x, double y, double z)
    {
        return (x * x) + (y * y) + (z * z);
    }

    private static int setBlock(
            ServerLevel world, BlockPos.MutableBlockPos mbpos, int x, int y, int z,
            BlockInput block, Predicate<BlockInWorld> replacement,
            List<BlockPos> list
    )
    {
        mbpos.set(x, y, z);
        int success=0;
        if (replacement == null || replacement.test(new BlockInWorld(world, mbpos, true)))
        {
            BlockEntity tileentity = world.getBlockEntity(mbpos);
            if (tileentity instanceof Container)
            {
                ((Container) tileentity).clearContent();
            }
            if (block.place(world, mbpos, 2))
            {
                list.add(mbpos.immutable());
                ++success;
            }
        }

        return success;
    }

    private static int drawSphere(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx, boolean solid) throws CommandSyntaxException
    {
        BlockPos pos;
        int radius;
        BlockInput block;
        Predicate<BlockInWorld> replacement;
        try
        {
            pos = getArg(ctx, DrawCommand::blockPos, "center");
            radius = getArg(ctx, IntegerArgumentType::getInteger, "radius");
            block = getArg(ctx, (cc, name) -> cc.getArgument(name, BlockInput.class), "block");
            replacement = getArg(ctx, (cc, name) -> cc.getArgument(name, BlockPredicateArgument.Result.class), "filter", true);
        }
        catch (ErrorHandled ignored) { return 0; }

        int affected = 0;
        ServerLevel world = nms(ctx.getSource()).getLevel();

        double radiusX = radius+0.5;
        double radiusY = radius+0.5;
        double radiusZ = radius+0.5;

        final double invRadiusX = 1 / radiusX;
        final double invRadiusY = 1 / radiusY;
        final double invRadiusZ = 1 / radiusZ;

        final int ceilRadiusX = (int) Math.ceil(radiusX);
        final int ceilRadiusY = (int) Math.ceil(radiusY);
        final int ceilRadiusZ = (int) Math.ceil(radiusZ);

        BlockPos.MutableBlockPos mbpos = pos.mutable();
        List<BlockPos> list = Lists.newArrayList();

        double nextXn = 0;

        forX: for (int x = 0; x <= ceilRadiusX; ++x)
        {
            final double xn = nextXn;
            nextXn = (x + 1) * invRadiusX;
            double nextYn = 0;
            forY: for (int y = 0; y <= ceilRadiusY; ++y)
            {
                final double yn = nextYn;
                nextYn = (y + 1) * invRadiusY;
                double nextZn = 0;
                forZ: for (int z = 0; z <= ceilRadiusZ; ++z)
                {
                    final double zn = nextZn;
                    nextZn = (z + 1) * invRadiusZ;

                    double distanceSq = lengthSq(xn, yn, zn);
                    if (distanceSq > 1)
                    {
                        if (z == 0)
                        {
                            if (y == 0)
                            {
                                break forX;
                            }
                            break forY;
                        }
                        break forZ;
                    }

                    if (!solid && lengthSq(nextXn, yn, zn) <= 1 && lengthSq(xn, nextYn, zn) <= 1 && lengthSq(xn, yn, nextZn) <= 1)
                    {
                        continue;
                    }

                    // 上游此处 impendingFillSkipUpdates ThreadLocal（fillUpdates Mixin 消费端）——
                    // Paper 无 Mixin 物理不可移植，删除（固定 fillUpdates=true 语义，类 javadoc）
                    for (int xmod = -1; xmod < 2; xmod += 2)
                    {
                        for (int ymod = -1; ymod < 2; ymod += 2)
                        {
                            for (int zmod = -1; zmod < 2; zmod += 2)
                            {
                                affected+= setBlock(world, mbpos,
                                        pos.getX() + xmod * x, pos.getY() + ymod * y, pos.getZ() + zmod * z,
                                        block, replacement, list
                                );
                            }
                        }
                    }
                }
            }
        }
        // 上游 if (fillUpdates) 门控——B 侧固定 true（规则面未移植），无条件收尾
        list.forEach(blockpos1 -> world.updateNeighborsAt(blockpos1, world.getBlockState(blockpos1).getBlock()));
        Messenger.m(nms(ctx.getSource()), "gi Filled " + affected + " blocks");
        return affected;
    }

    private static int drawDiamond(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx, boolean solid) throws CommandSyntaxException
    {
        BlockPos pos;
        int radius;
        BlockInput block;
        Predicate<BlockInWorld> replacement;
        try
        {
            pos = getArg(ctx, DrawCommand::blockPos, "center");
            radius = getArg(ctx, IntegerArgumentType::getInteger, "radius");
            block = getArg(ctx, (cc, name) -> cc.getArgument(name, BlockInput.class), "block");
            replacement = getArg(ctx, (cc, name) -> cc.getArgument(name, BlockPredicateArgument.Result.class), "filter", true);
        }
        catch (ErrorHandled ignored) { return 0; }

        CommandSourceStack source = nms(ctx.getSource());

        int affected=0;

        BlockPos.MutableBlockPos mbpos = pos.mutable();
        List<BlockPos> list = Lists.newArrayList();

        ServerLevel world = source.getLevel();

        for (int r = 0; r < radius; ++r)
        {
            int y=r-radius+1;
            for (int x = -r; x <= r; ++x)
            {
                int z=r-Math.abs(x);

                affected+= setBlock(world, mbpos, pos.getX()+x, pos.getY()-y, pos.getZ()+z, block, replacement, list);
                affected+= setBlock(world, mbpos, pos.getX()+x, pos.getY()-y, pos.getZ()-z, block, replacement, list);
                affected+= setBlock(world, mbpos, pos.getX()+x, pos.getY()+y, pos.getZ()+z, block, replacement, list);
                affected+= setBlock(world, mbpos, pos.getX()+x, pos.getY()+y, pos.getZ()-z, block, replacement, list);
            }
        }

        // 上游 ThreadLocal 行删除（固定 fillUpdates=true 语义，类 javadoc）
        list.forEach(p -> world.updateNeighborsAt(p, world.getBlockState(p).getBlock()));

        Messenger.m(source, "gi Filled " + affected + " blocks");

        return affected;
    }

    private static int fillFlat(
            ServerLevel world, BlockPos pos, int offset, double dr, boolean rectangle, String orientation,
            BlockInput block, Predicate<BlockInWorld> replacement,
            List<BlockPos> list, BlockPos.MutableBlockPos mbpos
    )
    {
        int successes=0;
        int r = Mth.floor(dr);
        double drsq = dr*dr;
        if (orientation.equalsIgnoreCase("x"))
        {
            for(int a=-r; a<=r; ++a) for(int b=-r; b<=r; ++b) if(rectangle || a*a + b*b <= drsq)
            {
                successes += setBlock(
                        world, mbpos,pos.getX()+offset, pos.getY()+a, pos.getZ()+b,
                        block, replacement, list
                );
            }
            return successes;
        }
        if (orientation.equalsIgnoreCase("y"))
        {
            for(int a=-r; a<=r; ++a) for(int b=-r; b<=r; ++b) if(rectangle || a*a + b*b <= drsq)
            {
                successes += setBlock(
                        world, mbpos,pos.getX()+a, pos.getY()+offset, pos.getZ()+b,
                        block, replacement, list
                );
            }
            return successes;
        }
        if (orientation.equalsIgnoreCase("z"))
        {
            for(int a=-r; a<=r; ++a) for(int b=-r; b<=r; ++b) if(rectangle || a*a + b*b <= drsq)
            {
                successes += setBlock(
                        world, mbpos,pos.getX()+b, pos.getY()+a, pos.getZ()+offset,
                        block, replacement, list
                );
            }
            return successes;
        }
        return 0;
    }

    private static int drawPyramid(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx, String base, boolean solid) throws CommandSyntaxException
    {
        BlockPos pos;
        double radius;
        int height;
        boolean pointup;
        String orientation;
        BlockInput block;
        Predicate<BlockInWorld> replacement;
        try
        {
            pos = getArg(ctx, DrawCommand::blockPos, "center");
            radius = getArg(ctx, IntegerArgumentType::getInteger, "radius")+0.5D;
            height = getArg(ctx, IntegerArgumentType::getInteger, "height");
            pointup = getArg(ctx, StringArgumentType::getString, "pointing").equalsIgnoreCase("up");
            orientation = getArg(ctx, StringArgumentType::getString,"orientation");
            block = getArg(ctx, (cc, name) -> cc.getArgument(name, BlockInput.class), "block");
            replacement = getArg(ctx, (cc, name) -> cc.getArgument(name, BlockPredicateArgument.Result.class), "filter", true);
        }
        catch (ErrorHandled ignored) { return 0; }

        CommandSourceStack source = nms(ctx.getSource());

        int affected = 0;
        BlockPos.MutableBlockPos mbpos = pos.mutable();

        List<BlockPos> list = Lists.newArrayList();

        ServerLevel world = source.getLevel();

        boolean isSquare = base.equalsIgnoreCase("square");

        for(int i =0; i<height;++i)
        {
            double r = pointup ? radius - radius * i / height - 1 : radius * i / height;
            affected+= fillFlat(world, pos, i, r, isSquare, orientation, block, replacement, list, mbpos);
        }

        // 上游 ThreadLocal 行删除 + if (fillUpdates) 门控展开（固定 true 语义，类 javadoc）
        for (BlockPos blockpos1 : list) {
            Block blokc = world.getBlockState(blockpos1).getBlock();
            world.updateNeighborsAt(blockpos1, blokc);
        }

        Messenger.m(source, "gi Filled " + affected + " blocks");

        return affected;
    }

    private static int drawPrism(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx, String base){
        BlockPos pos;
        double radius;
        int height;
        String orientation;
        BlockInput block;
        Predicate<BlockInWorld> replacement;
        try
        {
            pos = getArg(ctx, DrawCommand::blockPos, "center");
            radius = getArg(ctx, IntegerArgumentType::getInteger, "radius")+0.5D;
            height = getArg(ctx, IntegerArgumentType::getInteger, "height");
            orientation = getArg(ctx, StringArgumentType::getString,"orientation");
            block = getArg(ctx, (cc, name) -> cc.getArgument(name, BlockInput.class), "block");
            replacement = getArg(ctx, (cc, name) -> cc.getArgument(name, BlockPredicateArgument.Result.class), "filter", true);
        }
        catch (ErrorHandled | CommandSyntaxException ignored) { return 0; }

        CommandSourceStack source = nms(ctx.getSource());

        int affected = 0;
        BlockPos.MutableBlockPos mbpos = pos.mutable();

        List<BlockPos> list = Lists.newArrayList();

        ServerLevel world = source.getLevel();

        boolean isSquare = base.equalsIgnoreCase("square");

        for(int i =0; i<height;++i)
        {
            affected+= fillFlat(world, pos, i, radius, isSquare, orientation, block, replacement, list, mbpos);
        }

        // 上游 ThreadLocal 行删除 + if (fillUpdates) 门控展开（固定 true 语义，类 javadoc）
        for (BlockPos blockpos1 : list) {
            Block blokc = world.getBlockState(blockpos1).getBlock();
            world.updateNeighborsAt(blockpos1, blokc);
        }

        Messenger.m(source, "gi Filled " + affected + " blocks");

        return affected;
    }

    /** NMS source cast（Paper 接口的实现即 NMS CommandSourceStack，PlayerCommand 同款单点）。 */
    private static CommandSourceStack nms(io.papermc.paper.command.brigadier.CommandSourceStack source)
    {
        return (CommandSourceStack) source;
    }

    /** BlockPosArgument.getSpawnablePos 等价体（vanilla 体 = getArgument(Coordinates).getBlockPos(source)）。 */
    private static BlockPos blockPos(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> c, String name)
    {
        return c.getArgument(name, Coordinates.class).getBlockPos(nms(c.getSource()));
    }
}
