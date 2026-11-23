package verymc.top.veryMcProto.mod.carpet.network;

import java.util.List;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSigningContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.Mth;
import net.minecraft.util.TaskChainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * 命令输出采集源（mod 层，carpet:hello 协议）——上游 {@code carpet/script/utils/SnoopyCommandSource.java}
 * （tag v26.3 树：:34 {@code namesProvider} 字段、:48-100 字段/两构造）的 Paper 移植：以玩家自身权限为
 * 载体执行命令（无提权），覆写 {@code sendSuccess/sendFailure} 采集输出与错误供 clientCommand 回执组装。
 *
 * <p>与上游的结构差异（对账登记，docs/carpet/protocol.md 偏差清单）：
 * <ul>
 *   <li><b>删 {@code setupPrivates}</b>——上游经 Mixin（{@code CommandSourceStack_scarpetMixin}）把
 *       resultCallback 等 5 个私有字段写入基类；Paper 无 Mixin 运行时不可照抄。上游 26.3 已随基类
 *       NamesProvider 化（原 13 参全量构造收敛）：公开 7 参构造（{@code CommandSourceStack.java:68-79}，
 *       {@code PermissionSet, server, entity} 三新参面，entity 版内部走 {@code NamesProvider.FOR_ENTITY}；
 *       8 参/13 参均已 private）。B 侧 7 参形态与上游 26.3 同构（子类镜像 {@code namesProvider} 字段）+
 *       {@code with*} 保型覆写承载，基类私有 resultConsumer 保持构造器默认
 *       {@code CommandResultCallback.EMPTY}（no-op，无 NPE 面）→ 回执帧 {@code return}
 *       恒 0。客户端 {@code CarpetClient.onClientCommand} 只读 id/error/output、零消费 return，
 *       该分歧无任何可观测影响（上游 {@code ServerNetworkHandler.java:103} 写真实值——偏差已登记）。</li>
 *   <li>覆写集 = 上游封闭 11 方法清单逐条（withEntity/withPosition/withRotation/withCallback×2/
 *       withPermission/withMaximumPermission/withAnchor/withSigningContext/withLevel/facing）；
 *       26.3 另有的 withSource/withLocation/withSuppressedOutput 上游不覆写，B 侧同判不覆写。</li>
 * </ul>
 */
public class CarpetSnoopyCommandSource extends CommandSourceStack
{
    private final CommandSource output;
    private final Vec3 position;
    private final ServerLevel world;
    private final PermissionSet level;
    private final CommandSourceStack.NamesProvider namesProvider;
    private final MinecraftServer server;
    private final Entity entity;
    private final CommandResultCallback resultConsumer;
    private final EntityAnchorArgument.Anchor entityAnchor;
    private final Vec2 rotation;
    private final Component[] error;
    private final List<Component> chatOutput;
    private final CommandSigningContext signingContext;
    private final TaskChainer taskChainer;

    public CarpetSnoopyCommandSource(ServerPlayer player, Component[] error, List<Component> output, int[] result)
    {
        super(player.commandSource(), player.position(), player.getRotationVector(),
                player.level() instanceof final ServerLevel serverLevel ? serverLevel : null,
                player.level().getServer().getProfilePermissions(player.nameAndId()),
                player.level().getServer(), player);
        this.output = player.commandSource();
        this.position = player.position();
        this.world = player.level() instanceof final ServerLevel serverLevel ? serverLevel : null;
        this.level = player.level().getServer().getProfilePermissions(player.nameAndId());
        this.namesProvider = NamesProvider.FOR_ENTITY;
        this.server = player.level().getServer();
        this.entity = player;
        this.resultConsumer = (b, i) -> result[0] = i;
        this.entityAnchor = EntityAnchorArgument.Anchor.FEET;
        this.rotation = player.getRotationVector();
        this.error = error;
        this.chatOutput = output;
        this.signingContext = CommandSigningContext.ANONYMOUS;
        this.taskChainer = TaskChainer.immediate(player.level().getServer());
    }

    private CarpetSnoopyCommandSource(CommandSource output, Vec3 pos, Vec2 rot, ServerLevel world,
            PermissionSet level, CommandSourceStack.NamesProvider namesProvider, MinecraftServer server, @Nullable Entity entity,
            CommandResultCallback consumer, EntityAnchorArgument.Anchor entityAnchor,
            CommandSigningContext context, TaskChainer chainer,
            Component[] error, List<Component> chatOutput)
    {
        super(output, pos, rot, world, level, server, entity);
        this.output = output;
        this.position = pos;
        this.rotation = rot;
        this.world = world;
        this.level = level;
        this.namesProvider = namesProvider;
        this.server = server;
        this.entity = entity;
        this.resultConsumer = consumer;
        this.entityAnchor = entityAnchor;
        this.error = error;
        this.chatOutput = chatOutput;
        this.signingContext = context;
        this.taskChainer = chainer;
    }

    @Override
    public CommandSourceStack withEntity(Entity entity)
    {
        return new CarpetSnoopyCommandSource(output, position, rotation, world, level,
                NamesProvider.FOR_ENTITY, server, entity,
                resultConsumer, entityAnchor, signingContext, taskChainer, error, chatOutput);
    }

    @Override
    public CommandSourceStack withPosition(Vec3 position)
    {
        return new CarpetSnoopyCommandSource(output, position, rotation, world, level,
                namesProvider, server, entity, resultConsumer, entityAnchor, signingContext, taskChainer, error, chatOutput);
    }

    @Override
    public CommandSourceStack withRotation(Vec2 rotation)
    {
        return new CarpetSnoopyCommandSource(output, position, rotation, world, level,
                namesProvider, server, entity, resultConsumer, entityAnchor, signingContext, taskChainer, error, chatOutput);
    }

    @Override
    public CommandSourceStack withCallback(CommandResultCallback consumer)
    {
        return new CarpetSnoopyCommandSource(output, position, rotation, world, level,
                namesProvider, server, entity, consumer, entityAnchor, signingContext, taskChainer, error, chatOutput);
    }

    @Override
    public CommandSourceStack withCallback(CommandResultCallback consumer, BinaryOperator<CommandResultCallback> binaryOperator)
    {
        CommandResultCallback merged = binaryOperator.apply(this.resultConsumer, consumer);
        return this.withCallback(merged);
    }

    @Override
    public CommandSourceStack withPermission(PermissionSet level)
    {
        // 上游语义逐字：锁权限——命令执行中途的升/降级请求被忽略（:170-178）
        return this;
    }

    @Override
    public CommandSourceStack withMaximumPermission(PermissionSet level)
    {
        // 上游语义逐字（:180-186）
        return this;
    }

    @Override
    public CommandSourceStack withAnchor(EntityAnchorArgument.Anchor anchor)
    {
        return new CarpetSnoopyCommandSource(output, position, rotation, world, level,
                namesProvider, server, entity, resultConsumer, anchor, signingContext, taskChainer, error, chatOutput);
    }

    @Override
    public CommandSourceStack withSigningContext(CommandSigningContext commandSigningContext, TaskChainer taskChainer)
    {
        return new CarpetSnoopyCommandSource(output, position, rotation, world, level,
                namesProvider, server, entity, resultConsumer, entityAnchor, commandSigningContext, taskChainer, error, chatOutput);
    }

    @Override
    public CommandSourceStack withLevel(ServerLevel world)
    {
        double scale = net.minecraft.world.level.dimension.DimensionType.getTeleportationScale(this.world.dimensionType(), world.dimensionType());
        Vec3 position = new Vec3(this.position.x * scale, this.position.y, this.position.z * scale);
        return new CarpetSnoopyCommandSource(output, position, rotation, world, level,
                namesProvider, server, entity, resultConsumer, entityAnchor, signingContext, taskChainer, error, chatOutput);
    }

    @Override
    public CommandSourceStack facing(Vec3 position)
    {
        Vec3 anchorPos = this.entityAnchor.apply(this);
        double dx = position.x - anchorPos.x;
        double dy = position.y - anchorPos.y;
        double dz = position.z - anchorPos.z;
        double groundDist = Math.sqrt(dx * dx + dz * dz);
        float pitch = Mth.wrapDegrees((float) (-(Mth.atan2(dy, groundDist) * 57.2957763671875D)));
        float yaw = Mth.wrapDegrees((float) ((Mth.atan2(dz, dx) * 57.2957763671875D) - 90.0F));
        return this.withRotation(new Vec2(pitch, yaw));
    }

    @Override
    public void sendFailure(Component message)
    {
        error[0] = message;
    }

    @Override
    public void sendSuccess(Supplier<Component> message, boolean broadcastToOps)
    {
        // 上游语义逐字（:209-213）：采集进 chatOutput，丢弃 broadcastToOps 广播——
        // clientCommand 的输出回传客户端而非 op 广播（审计缺口由 handler 的 INFO 审计行补偿，B 侧增补）
        chatOutput.add(message.get());
    }
}
