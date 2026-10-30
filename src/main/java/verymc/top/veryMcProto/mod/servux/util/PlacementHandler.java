package verymc.top.veryMcProto.mod.servux.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.jetbrains.annotations.NotNull;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.mod.servux.dataproviders.ConfigProvider;

/**
 * EasyPlace 放置协议处理器（mod 层，纯算法移植）。
 *
 * <p>移植自原版 {@code fi.dy.masa.servux.util.PlacementHandler}，逐行照抄——这是 masa EasyPlace 协议 v3 的
 * 核心：客户端（Tweakeroo）把精确放置状态（朝向 + 白名单属性）编码进 {@code use_item_on} 包的 hitVec.x
 * 偏移里，服务端用本类从 {@code protocolValue} 解码出最终 BlockState。
 *
 * <p><b>无 Mixin 依赖</b>：原版用两个 Mixin（{@code MixinBlockItem_EasyPlace} 拦截 {@code getPlacementState}、
 * {@code MixinServerPlayNetworkHandler_EasyPlace} 把 hitVec 距离检查短路）接入。Paper 无 Mixin，本移植改用
 * 「改写放行」范式：PacketEvents 在 netty 侧把编码包剥码放行（等效距离检查短路），主线程
 * {@code EasyPlaceFixListener} 在 {@code BlockCanBuildEvent}/{@code BlockPlaceEvent} 双挂点内调用本类修正
 * 最终状态。详见 {@code EasyPlaceListener} / {@code EasyPlaceFixListener}。
 *
 * <p><b>适配</b>：{@code ServuxConfigProvider.INSTANCE} → {@link ConfigProvider#INSTANCE}；
 * {@code Servux.LOGGER.warn(msg, e)} → {@link Reference#logger()} 的 JUL（打完整堆栈）。算法逻辑零改动。
 */
public class PlacementHandler
{
    public static final ImmutableSet<@NotNull Property<?>> WHITELISTED_PROPERTIES = ImmutableSet.of(
            BlockStateProperties.INVERTED,
            BlockStateProperties.OPEN,
            BlockStateProperties.BELL_ATTACHMENT,
            BlockStateProperties.AXIS,
            BlockStateProperties.HALF,
            BlockStateProperties.ATTACH_FACE,
            BlockStateProperties.CHEST_TYPE,
            BlockStateProperties.MODE_COMPARATOR,
            BlockStateProperties.DOOR_HINGE,
            BlockStateProperties.FACING,
            BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.ORIENTATION,
            BlockStateProperties.RAIL_SHAPE,
            BlockStateProperties.RAIL_SHAPE_STRAIGHT,
            BlockStateProperties.SLAB_TYPE,
            BlockStateProperties.STAIRS_SHAPE,
            BlockStateProperties.COPPER_GOLEM_POSE,
            BlockStateProperties.BITES,
            BlockStateProperties.DELAY,
            BlockStateProperties.NOTE,
            BlockStateProperties.ROTATION_16
    );

    /**
     * BlackList for Block States.  Entries here will be reset to their default value.
     */
    public static final ImmutableMap<Property<?>, ? extends Comparable<?>> BLACKLISTED_PROPERTIES = ImmutableMap.of(
            BlockStateProperties.WATERLOGGED,       Boolean.FALSE,
            BlockStateProperties.POWERED,           Boolean.FALSE
    );

    public static <T extends Comparable<T>> BlockState applyPlacementProtocolV3(BlockState state, UseContext context)
    {
        int protocolValue = (int) (context.hitVec().x - (double) context.pos().getX()) - 2;
        BlockState oldState = state;

        if (protocolValue < 0)
        {
            return oldState;
        }

        Optional<EnumProperty<@NotNull Direction>> property = BlockUtils.getFirstDirectionProperty(state);

        // DirectionProperty - allow all except: VERTICAL_DIRECTION (PointedDripstone)
        if (property.isPresent() && property.get() != BlockStateProperties.VERTICAL_DIRECTION)
        {
            state = applyDirectionProperty(state, context, property.get(), protocolValue);

            if (state == null)
            {
                return null;
            }

            if (ConfigProvider.INSTANCE.isEasyPlaceValidatorEnabled())
            {
                if (state.canSurvive(context.world(), context.pos()))
                {
                    oldState = state;
                }
                else
                {
                    state = oldState;
                }
            }
            else
            {
                oldState = state;
            }

            // Consume the bits used for the facing
            protocolValue >>>= 3;
        }
        // Consume the lowest unused bit
        protocolValue >>>= 1;

        List<Property<?>> propList = new ArrayList<>(state.getBlock().getStateDefinition().getProperties());
        propList.sort(Comparator.comparing(Property::getName));

        try
        {
            for (Property<?> p : propList)
            {
                if (property.isPresent() && property.get().equals(p))
                {
                    continue;
                }
                else if (WHITELISTED_PROPERTIES.contains(p) &&
                        !BLACKLISTED_PROPERTIES.containsKey(p))
                {
                    @SuppressWarnings("unchecked")
                    Property<T> prop = (Property<T>) p;
                    List<T> list = new ArrayList<>(prop.getPossibleValues());
                    list.sort(Comparable::compareTo);

                    int requiredBits = Mth.log2(Mth.smallestEncompassingPowerOfTwo(list.size()));
                    int bitMask = ~(0xFFFFFFFF << requiredBits);
                    int valueIndex = protocolValue & bitMask;

                    if (valueIndex >= 0 && valueIndex < list.size())
                    {
                        T value = list.get(valueIndex);

                        if (state.getValue(prop).equals(value) == false &&
                            value != SlabType.DOUBLE) // don't allow duping slabs by forcing a double slab via the protocol
                        {
                            state = state.setValue(prop, value);

                            if (ConfigProvider.INSTANCE.isEasyPlaceValidatorEnabled())
                            {
                                if (state.canSurvive(context.world(), context.pos()))
                                {
                                    oldState = state;
                                }
                                else
                                {
                                    state = oldState;
                                }
                            }
                            else
                            {
                                oldState = state;
                            }
                        }

                        protocolValue >>>= requiredBits;
                    }
                }
            }
        }
        catch (Exception e)
        {
            Reference.logger().log(java.util.logging.Level.WARNING, "Exception trying to apply placement protocol value", e);
        }

        // Strip Blacklisted properties, and use the Block's default state.
        // This needs to be done after the initial loop, or it breaks compatibility
        for (Property<?> p : BLACKLISTED_PROPERTIES.keySet())
        {
            if (state.hasProperty(p))
            {
                @SuppressWarnings("unchecked")
                Property<T> prop = (Property<T>) p;
                state = state.setValue(prop, (T) BLACKLISTED_PROPERTIES.get(p));
            }
        }

        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && (
            oldState.hasProperty(BlockStateProperties.WATERLOGGED) && oldState.getValue(BlockStateProperties.WATERLOGGED) ||
            (oldState.getFluidState() != null && oldState.getFluidState().getType().isSame(Fluids.WATER))
        ))
        {
            // Revert only if original state was waterlogged / Still Water already
            state = state.setValue(BlockStateProperties.WATERLOGGED, true);
        }

        if (ConfigProvider.INSTANCE.isEasyPlaceValidatorEnabled())
        {
            // This validates that the player can legally place this block state; such as in air.
            if (state.canSurvive(context.world(), context.pos()))
            {
                return state;
            }
            else
            {
                return null;
            }
        }

        return state;
    }

    private static BlockState applyDirectionProperty(BlockState state, UseContext context,
                                                     EnumProperty<@NotNull Direction> property, int protocolValue)
    {
        Direction facingOrig = state.getValue(property);
        Direction facing = facingOrig;
        int decodedFacingIndex = (protocolValue & 0xF) >> 1;

        if (decodedFacingIndex == 6) // the opposite of the normal facing requested
        {
            facing = facing.getOpposite();
        }
        else if (decodedFacingIndex >= 0 && decodedFacingIndex <= 5)
        {
            facing = Direction.from3DDataValue(decodedFacingIndex);

            if (property.getPossibleValues().contains(facing) == false)
            {
                facing = context.entity().getDirection().getOpposite();
            }
        }

        if (facing != facingOrig && property.getPossibleValues().contains(facing))
        {
            if (state.getBlock() instanceof BedBlock)
            {
                // 本地分支（勿随上游模板回退）：上游传 NMS BlockPlaceContext 查床头可替换性
                // （canBeReplaced(ctx)），而 1.21.11 vanilla 基类实现为
                // {@code state.canBeReplaced() && (ctx.getItemInHand().isEmpty() || ...)}——短路仅保护
                // 「不可替换」分支，可替换分支（含空气这一常态：AIR 注册即 .replaceable()）无条件解引用
                // ctx（1.21.11 Mache 源码 BlockBehaviour 实证）。本范式 UseContext.itemPlacementContext
                // 恒 null（无 Mixin 取不到 NMS BlockPlaceContext），原样照抄必 NPE。改为无参
                // canBeReplaced()：canBuild 时点修正朝向的床头位恒未被本次 vanilla 放置触碰
                // （vanilla 只写事件位与 vanilla 朝向床头，Direction.relative 单射），当前态 == 放置前态，
                // 判定等价上游。与带参版差异两枝：手持物品==床头位方块 asItem 时上游拒绝/我方放行
                // （更宽）；Paper isDestroyable 枝（replaceable 且不可毁的组合现实中≈空集）。
                BlockPos headPos = context.pos().relative(facing);

                if (context.world().getBlockState(headPos).canBeReplaced() == false)
                {
                    return null;
                }
            }

            state = state.setValue(property, facing);
        }

        return state;
    }

    /**
     * EasyPlace 放置上下文（record，照抄原版语义；side 组件为零引用死输入已删）。
     *
     * <p>{@link EasyPlaceFixListener} 直接 {@code new UseContext(level, pos, hitVec, player, hand, null)} 构造
     * （hitVec.x 按编码公式重推 pv；{@code itemPlacementContext} 恒 null——无 Mixin 取不到 NMS
     * {@code BlockPlaceContext}，床头检查已按该前提适配，见 {@link #applyDirectionProperty} 内注释）。
     * 解码器对 {@code hand / hitVec.y / hitVec.z} 同样零引用，仅为上下文保真保留。
     */
    public record UseContext(Level world, BlockPos pos, Vec3 hitVec, LivingEntity entity,
                             InteractionHand hand, @Nullable BlockPlaceContext itemPlacementContext)
    {
    }
}
