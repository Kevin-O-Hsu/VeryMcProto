package verymc.top.veryMcProto.mod.carpet.command;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;

import verymc.top.veryMcProto.mod.carpet.CarpetSettings;
import verymc.top.veryMcProto.mod.carpet.logging.SpawnReport;
import verymc.top.veryMcProto.mod.carpet.util.CommandHelper;
import verymc.top.veryMcProto.mod.carpet.util.Messenger;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

/**
 * /spawn 命令——mobcaps/entities 两子树（上游 {@code carpet/commands/SpawnCommand.java} 26.2 树
 * :95-107 的两 literal 逐字移植；Paper Brigadier 载体，plugin.yml 无条目）：
 * <pre>/spawn mobcaps                    当前维度 mobcaps 多行报告（entities 裸同款，上游 :96/:103）
 * /spawn mobcaps &lt;dimension&gt;       指定维度
 * /spawn entities                  同 mobcaps 裸（上游同引用 generalMobcaps）
 * /spawn entities &lt;type&gt; [all]     类别实体清单（type = MobCategory 名，非实体 id）</pre>
 *
 * <p><b>与上游的偏差</b>（登记，docs/carpet/architecture.md）：
 * <ul>
 *   <li>list/tracking/test/mocking/rates 五子树不移植（tracking = NaturalSpawnerMixin 深钩，
 *       scope §3 未移植；test/mocking/rates 为 tracking 配套）；</li>
 *   <li><b>mobcaps set 子分支裁剪</b>：写场 {@code SpawnReporter.mobcap_exponent} 的消费端全在
 *       Mixin（MobCategory_spawnMixin/NaturalSpawnerMixin，上游 SpawnCommand.java:230-236）——Paper
 *       无 Mixin 物理不可移植，移植即得"打印成功实为 no-op"的命令；</li>
 *   <li>数据面换轨：上游 {@code SpawnReporter} → B 侧 {@link SpawnReport}（/log 切片类——chunkcount
 *       走 SpawnState 公开 API 谱系偏差 #52；spawn_tries 追踪面未移植 → rounds 段恒空）；</li>
 *   <li>参数取值 {@code DimensionArgument.getDimension} → {@code getArgument(ServerLevel.class)}
 *       等价体（vanilla 体即此，PlayerCommand :322 同款先例）。</li>
 * </ul>
 */
public class SpawnCommand
{
    /** 建树（CarpetModule 经 LifecycleEvents.COMMANDS 注册）。 */
    public static LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> build()
    {
        LiteralArgumentBuilder<io.papermc.paper.command.brigadier.CommandSourceStack> literalargumentbuilder = literal("spawn").
                requires((player) -> CommandHelper.canUseCommand(nms(player), CarpetSettings.commandSpawn));

        literalargumentbuilder.
                then(literal("mobcaps").
                        executes( (c) -> generalMobcaps(nms(c.getSource()))).
                        then(argument("dimension", DimensionArgument.dimension()).
                                executes( (c)-> mobcapsForDimension(nms(c.getSource()), c.getArgument("dimension", ServerLevel.class))))).
                then(literal("entities").
                        executes( (c) -> generalMobcaps(nms(c.getSource())) ).
                        then(argument("type", string()).
                                suggests( (c, b)->suggest(Arrays.stream(SpawnReport.cachedMobCategories()).map(MobCategory::getName), b)).
                                executes( (c) -> listEntitiesOfType(nms(c.getSource()), getString(c, "type"), false)).
                                then(literal("all").executes( (c) -> listEntitiesOfType(nms(c.getSource()), getString(c, "type"), true)))));

        return literalargumentbuilder.build();
    }

    private static final Map<String, MobCategory> MOB_CATEGORY_MAP = Arrays.stream(SpawnReport.cachedMobCategories()).collect(Collectors.toMap(MobCategory::getName, Function.identity()));

    private static MobCategory getCategory(String string) throws CommandSyntaxException
    {
        if (!Arrays.stream(SpawnReport.cachedMobCategories()).map(MobCategory::getName).collect(Collectors.toSet()).contains(string))
        {
            throw new SimpleCommandExceptionType(Messenger.c("r Wrong mob type: "+string+" should be "+ Arrays.stream(SpawnReport.cachedMobCategories()).map(MobCategory::getName).collect(Collectors.joining(", ")))).create();
        }
        return MOB_CATEGORY_MAP.get(string.toLowerCase(Locale.ROOT));
    }

    private static int generalMobcaps(CommandSourceStack source)
    {
        Messenger.send(source, SpawnReport.printMobcapsForDimension(source.getLevel(), true));
        return 1;
    }

    private static int mobcapsForDimension(CommandSourceStack source, ServerLevel world)
    {
        Messenger.send(source, SpawnReport.printMobcapsForDimension(world, true));
        return 1;
    }

    private static int listEntitiesOfType(CommandSourceStack source, String mobtype, boolean all) throws CommandSyntaxException
    {
        MobCategory cat = getCategory(mobtype);
        Messenger.send(source, SpawnReport.printEntitiesByType(cat, source.getLevel(), all));
        return 1;
    }

    /** NMS source cast（Paper 接口的实现即 NMS CommandSourceStack，PlayerCommand 同款单点）。 */
    private static CommandSourceStack nms(io.papermc.paper.command.brigadier.CommandSourceStack source)
    {
        return (CommandSourceStack) source;
    }
}
