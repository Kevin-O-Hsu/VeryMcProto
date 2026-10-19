package verymc.top.veryMcProto.mod.carpet.command;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import verymc.top.veryMcProto.mod.carpet.CarpetSettings;
import verymc.top.veryMcProto.mod.carpet.util.CommandHelper;
import verymc.top.veryMcProto.mod.carpet.util.Messenger;
import verymc.top.veryMcProto.mod.carpet.util.PerimeterDiagnostics;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * /perimeterinfo 命令（上游 {@code carpet/commands/PerimeterInfoCommand.java} 26.2 树逐字移植；
 * Paper Brigadier 载体，plugin.yml 无条目）。根裸 = 以执行者当前位为心（上游 :33-36）。
 *
 * <p><b>与上游的偏差</b>（登记，docs/carpet/architecture.md）：
 * <ul>
 *   <li>suggests 桥接：上游 {@code SuggestionProviders.cast(SUMMONABLE_ENTITIES)} 的泛型界
 *       {@code <S extends SharedSuggestionProvider>} 在 paper 树泛型上不可推断 → 直接 unchecked
 *       cast（{@link #SUMMONABLE_ENTITIES}）。运行时安全：vanilla provider 对
 *       {@code context.getSource()} 做 {@code checkcast SharedSuggestionProvider} 后调
 *       {@code enabledFeatures()}（字节码实证），而 Paper 派发链的运行时 source 即 NMS
 *       {@code CommandSourceStack}（implements SharedSuggestionProvider + PaperCommandSourceStack，
 *       javap 实证）——checkcast 必过。实机判据 = {@code /perimeterinfo <tab>} 补全出现可召唤实体
 *       id 全集（本模块首个触 source 的 NMS suggestion provider）；</li>
 *   <li>参数取值 {@code ResourceArgument.getSummonableEntityType}（NMS 泛型上下文签名）→
 *       {@code getArgument(Holder.Reference.class)} 等价体；其 canSummon 复检（vanilla
 *       {@code ERROR_NOT_SUMMONABLE_ENTITY} 非 public 不可引用）省略——suggests 已过滤 + 非 Mob
 *       拒绝行双兜底，不可达面经 loadEntityRecursive 后仍落 "requires a mob entity" 回执；</li>
 *   <li>{@code BlockPosArgument.getSpawnablePos} → Coordinates.getBlockPos 等价体（InfoCommand 同款）。</li>
 * </ul>
 */
public class PerimeterInfoCommand
{
    /**
     * SUMMONABLE_ENTITIES 跨泛型桥（上游 :43 cast() 的 unchecked 直桥形态；论证见类 javadoc——
     * B 侧首个触 source 的 NMS suggestion provider，tab 补全实测为完成判据）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final SuggestionProvider<io.papermc.paper.command.brigadier.CommandSourceStack> SUMMONABLE_ENTITIES =
            (SuggestionProvider<io.papermc.paper.command.brigadier.CommandSourceStack>) (SuggestionProvider) SuggestionProviders.SUMMONABLE_ENTITIES;

    /** 建树（CarpetModule 经 LifecycleEvents.COMMANDS 注册；ctx = BlockState/Resource 参数类构建上下文）。 */
    public static LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> build(CommandBuildContext commandBuildContext)
    {
        LiteralArgumentBuilder<io.papermc.paper.command.brigadier.CommandSourceStack> command = literal("perimeterinfo").
                requires((player) -> CommandHelper.canUseCommand(nms(player), CarpetSettings.commandPerimeterInfo)).
                executes( (c) -> perimeterDiagnose(
                        nms(c.getSource()),
                        BlockPos.containing(nms(c.getSource()).getPosition()),
                        null)).
                then(argument("center position", BlockPosArgument.blockPos()).
                        executes( (c) -> perimeterDiagnose(
                                nms(c.getSource()),
                                blockPos(c, "center position"),
                                null)).
                        then(argument("mob", net.minecraft.commands.arguments.ResourceArgument.resource(commandBuildContext, Registries.ENTITY_TYPE)).
                                suggests(SUMMONABLE_ENTITIES).
                                executes( (c) -> perimeterDiagnose(
                                        nms(c.getSource()),
                                        blockPos(c, "center position"),
                                        mobId(c, "mob")
                                ))));
        return command.build();
    }

    private static int perimeterDiagnose(CommandSourceStack source, BlockPos pos, String mobId)
    {
        CompoundTag nbttagcompound = new CompoundTag();
        Mob entityliving = null;
        if (mobId != null)
        {
            nbttagcompound.putString("id", mobId);
            Entity baseEntity = EntityType.loadEntityRecursive(nbttagcompound, source.getLevel(), new EntitySpawnRequest(EntitySpawnReason.COMMAND, true), (entity_1x) -> {
                entity_1x.snapTo(new BlockPos(pos.getX(), source.getLevel().getMinY()-10, pos.getZ()), entity_1x.getYRot(), entity_1x. getXRot());
                return !source.getLevel().addWithUUID(entity_1x) ? null : entity_1x;
            });
            if (!(baseEntity instanceof  Mob))
            {
                Messenger.m(source, "r /perimeterinfo requires a mob entity to test against.");
                if (baseEntity != null) baseEntity.discard();
                return 0;
            }
            entityliving = (Mob) baseEntity;
        }
        PerimeterDiagnostics.Result res = PerimeterDiagnostics.countSpots(source.getLevel(), pos, entityliving);

        Messenger.m(source, "w Spawning spaces around ",Messenger.tp("c",pos), "w :");
        Messenger.m(source, "w   potential in-liquid: ","wb "+res.liquid);
        Messenger.m(source, "w   potential on-ground: ","wb "+res.ground);
        if (entityliving != null)
        {
            Messenger.m(source, "w   ", entityliving.getDisplayName() ,"w : ","wb "+res.specific);
            res.samples.forEach(bp -> Messenger.m(source, "w   ", Messenger.tp("c", bp)));
            entityliving.discard(); // dicard // remove();
        }
        return 1;
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

    /** ResourceArgument.getSummonableEntityType 的 id 串等价体（vanilla 体 = getArgument(Reference) 后 key 取值）。 */
    @SuppressWarnings("unchecked")
    private static String mobId(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> c, String name)
    {
        Holder.Reference<EntityType<?>> ref =
                (Holder.Reference<EntityType<?>>) (Holder.Reference<?>) c.getArgument(name, Holder.Reference.class);
        return ref.key().identifier().toString();
    }
}
