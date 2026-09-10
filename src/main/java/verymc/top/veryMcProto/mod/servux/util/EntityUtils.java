package verymc.top.veryMcProto.mod.servux.util;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import verymc.top.veryMcProto.mod.servux.dataproviders.LitematicsDataProvider;
import verymc.top.veryMcProto.mod.servux.util.nbt.NbtView;
import verymc.top.veryMcProto.mod.servux.util.position.PositionUtils;

public class EntityUtils
{
    public static final Predicate<Entity> NOT_PLAYER = entity -> (entity instanceof Player) == false;
    /** 撞车重排随机源（上游同款 ThreadLocalRandom）。 */
    private static final ThreadLocalRandom RAND = ThreadLocalRandom.current();

    public static boolean isCreativeMode(Player player)
    {
        return player.getAbilities().instabuild;
    }

    public static Direction getHorizontalLookingDirection(Entity entity)
    {
        return Direction.fromYRot(entity.getYRot());
    }

    public static Direction getVerticalLookingDirection(Entity entity)
    {
        return entity.getXRot() > 0 ? Direction.DOWN : Direction.UP;
    }

    public static Direction getClosestLookingDirection(Entity entity)
    {
        if (entity.getXRot() > 60.0f)
        {
            return Direction.DOWN;
        }
        else if (-entity.getXRot() > 60.0f)
        {
            return Direction.UP;
        }

        return getHorizontalLookingDirection(entity);
    }

    @Nullable
    public static <T extends Entity> T findEntityByUUID(List<T> list, UUID uuid)
    {
        if (uuid == null)
        {
            return null;
        }

        for (T entity : list)
        {
            if (entity.getUUID().equals(uuid))
            {
                return entity;
            }
        }

        return null;
    }

    @Nullable
    public static String getEntityId(Entity entity)
    {
        EntityType<?> entitytype = entity.getType();
        Identifier resourcelocation = EntityType.getKey(entitytype);
        return entitytype.canSerialize() && resourcelocation != null ? resourcelocation.toString() : null;
    }

    @Nullable
    private static Entity createEntityFromNBTSingle(CompoundTag nbt, Level world)
    {
        try
        {
            NbtView view = NbtView.getReader(nbt, world.registryAccess());
            Optional<Entity> optional = EntityType.create(view.getReader(), world, EntitySpawnReason.LOAD);

            if (optional.isPresent())
            {
                Entity entity = optional.get();

                // 对齐上游 EntityUtils:104-116：尊重投影 NBT 原 UUID（无键才随机——重复粘贴同投影保留同 UUID，
                // 配合 spawn 侧撞车重排/去重）；LastEntityID(TAG_INT) 恢复原 id，否则随机高位 id 避开原版分配段。
                // vanilla CompoundTag 无 contains(String,int) 重载——getInt() 返回 OptionalInt，非 Int 类型
                // 恒 empty，与上游 TAG_INT 类型校验语义等价
                if (!nbt.contains("UUID"))
                {
                    entity.setUUID(UUID.randomUUID());
                }

                Optional<Integer> lastEntityId = nbt.getInt("LastEntityID");
                if (lastEntityId.isPresent())
                {
                    entity.setId(lastEntityId.get());
                }
                else
                {
                    entity.setId(RAND.nextInt(50000, Integer.MAX_VALUE));
                }

                return entity;
            }
        }
        catch (Exception ignore)
        {
        }

        return null;
    }

    /**
     * Note: This does NOT spawn any of the entities in the world!
     * @param nbt ()
     * @param world ()
     * @return ()
     */
    @Nullable
    public static Entity createEntityAndPassengersFromNBT(CompoundTag nbt, Level world)
    {
        Entity entity = createEntityFromNBTSingle(nbt, world);

        if (entity == null)
        {
            return null;
        }
        else
        {
            if (nbt.contains("Passengers"))
            {
                ListTag taglist = nbt.getListOrEmpty("Passengers");

                for (int i = 0; i < taglist.size(); ++i)
                {
                    Entity passenger = createEntityAndPassengersFromNBT(taglist.getCompoundOrEmpty(i), world);

                    if (passenger != null)
                    {
                        passenger.startRiding(entity, true, false);
                    }
                }
            }

            return entity;
        }
    }

    public static void spawnEntityAndPassengersInWorld(Entity entity, Level world)
    {
        boolean result;

        // 对齐上游 EntityUtils:164-198：撞车重排（deduplicate_schematic_entities=false 时开）——
        // id/UUID 与世界现有实体撞车则改派新值，避免同投影重复粘贴产生同 UUID 实体；
        // setting=true 跳过重排，依赖原版 addFreshEntity 的 UUID 唯一性拒绝重复实体（去重模式）
        Entity other = world.getEntity(entity.getId());

        if (!LitematicsDataProvider.INSTANCE.shouldDeDuplicateEntities())
        {
            if (other != null)
            {
                entity.setId(RAND.nextInt(entity.getId() * 4, Integer.MAX_VALUE));
            }

            other = world.getEntity(entity.getUUID());

            if (other != null)
            {
                entity.setUUID(UUID.randomUUID());
            }
        }

        try
        {
            result = world.addFreshEntity(entity);
        }
        catch (Exception e)
        {
            verymc.top.veryMcProto.Reference.logger().severe("EntityUtils#spawnEntityAndPassengersInWorld(): 生成实体失败 id("
                    + entity.getId() + "): [" + entity.getStringUUID() + "/" + entity.getType().toShortString() + "]; "
                    + e.getMessage());
            result = false;
        }

        if (result && entity.isVehicle())
        {
            for (Entity passenger : entity.getPassengers())
            {
                passenger.snapTo(
                        entity.getX(),
                        entity.getY() + entity.getPassengerRidingPosition(passenger).y(),
                        entity.getZ(),
                        passenger.getYRot(), passenger.getXRot());
                setEntityRotations(passenger, passenger.getYRot(), passenger.getXRot());
                spawnEntityAndPassengersInWorld(passenger, world);
            }
        }
    }

    public static void setEntityRotations(Entity entity, float yaw, float pitch)
    {
        entity.setYRot(yaw);
        entity.yRotO = yaw;

        entity.setXRot(pitch);
        entity.xRotO = pitch;

        if (entity instanceof LivingEntity livingBase)
        {
            livingBase.yHeadRot = yaw;
            livingBase.yBodyRot = yaw;
            livingBase.yHeadRotO = yaw;
            livingBase.yBodyRotO = yaw;
            //livingBase.renderYawOffset = yaw;
            //livingBase.prevRenderYawOffset = yaw;
        }
    }

    // 未移植：getEntitiesWithinSubRegion(...)（原版 ORIGIN/util/EntityUtils.java:181，区域内实体筛选的可选增强，粘贴链路未用）。
    // 粘贴链路当前会放置实体：SchematicPlacingUtils → createEntityAndPassengersFromNBT / spawnEntityAndPassengersInWorld。
}
