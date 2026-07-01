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
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RepeaterBlock;
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
 * PacketEvents 拦截原版 {@code use_item_on}（= {@code PLAYER_BLOCK_PLACEMENT}）包后手动调用本类 + 手动复刻
 * {@code BlockItem.place} 副作用。详见 {@code EasyPlaceListener}。
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
                BlockPos headPos = context.pos().relative(facing);
                BlockPlaceContext ctx = context.itemPlacementContext();

                if (context.world().getBlockState(headPos).canBeReplaced(ctx) == false)
                {
                    return null;
                }
            }

            state = state.setValue(property, facing);
        }

        return state;
    }

    public static BlockState applyPlacementProtocolV2(BlockState state, UseContext context)
    {
        int protocolValue = (int) (context.hitVec().x - (double) context.pos().getX()) - 2;

        if (protocolValue < 0)
        {
            return state;
        }

        Optional<EnumProperty<@NotNull Direction>> property = BlockUtils.getFirstDirectionProperty(state);

        if (property.isPresent())
        {
            state = applyDirectionProperty(state, context, property.get(), protocolValue);

            if (state == null)
            {
                return null;
            }
        }
        else if (state.hasProperty(BlockStateProperties.AXIS))
        {
            Direction.Axis axis = Direction.Axis.VALUES[((protocolValue >> 1) & 0x3) % 3];

            if (BlockStateProperties.AXIS.getPossibleValues().contains(axis))
            {
                state = state.setValue(BlockStateProperties.AXIS, axis);
            }
        }

        // Divide by two, and then remove the 4 bits used for the facing
        protocolValue >>>= 5;

        if (protocolValue > 0)
        {
            Block block = state.getBlock();

            if (block instanceof RepeaterBlock)
            {
                Integer delay = protocolValue;

                if (RepeaterBlock.DELAY.getPossibleValues().contains(delay))
                {
                    state = state.setValue(RepeaterBlock.DELAY, delay);
                }
            }
            else if (block instanceof ComparatorBlock)
            {
                state = state.setValue(ComparatorBlock.MODE, ComparatorMode.SUBTRACT);
            }
        }

        if (state.hasProperty(BlockStateProperties.HALF))
        {
            state = state.setValue(BlockStateProperties.HALF, protocolValue > 0 ? Half.TOP : Half.BOTTOM);
        }

        return state;
    }

    /**
     * EasyPlace 放置上下文（record，照抄原版）。
     *
     * <p>{@link EasyPlaceListener} 直接 {@code new UseContext(level, pos, face, hitVec, player, hand, null)} 构造，
     * 不走 {@link #from(BlockPlaceContext, InteractionHand)}（那是原版 Mixin 注入路径，Paper 不用，保留签名仅为保真）。
     */
	public record UseContext(Level world, BlockPos pos, Direction side, Vec3 hitVec, LivingEntity entity,
	                         InteractionHand hand, @Nullable BlockPlaceContext itemPlacementContext)
	{
		public static UseContext from(BlockPlaceContext ctx, InteractionHand hand)
		{
			Vec3 pos = ctx.getClickLocation();
			return new UseContext(ctx.getLevel(), ctx.getClickedPos(), ctx.getClickedFace(), new Vec3(pos.x, pos.y, pos.z),
			                      ctx.getPlayer(), hand, ctx);
		}
	}
}
