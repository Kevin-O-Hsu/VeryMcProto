package verymc.top.veryMcProto.framework.reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NMS 反射工具（框架层）。
 *
 * <p>paperweight-userdev 的 Mojang dev bundle 提供全映射 {@code net.minecraft.*}：
 * public / protected 成员直接用 Mojang 名访问；private / final 成员用本工具兜底反射。
 * 26.1 起 reobf 废除（Mojang 移除服务端混淆），产物与 Paper 运行时同为 Mojang 映射，反射用 Mojang 名天然命中。
 *
 * <p>沿父类链逐层查找字段 / 方法，自动 {@link Field#setAccessible(boolean)}。
 * 字段 / 方法查找结果做线程安全缓存，避免热路径（如每 tick 的 TPS / MobCap 采集）重复反射。
 *
 * <p><b>防御性</b>：提供 {@link #tryField} / {@link #getOr} / {@link #trySet} 等不抛异常入口，
 * 供数据采集在反射点版本漂移时降级（返回默认值 / 跳过该字段），绝不让 NMS 字段缺失拖垮服务端。
 */
public final class Reflect
{
    private Reflect() { }

    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<MethodKey, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private record MethodKey(Class<?> clazz, String name, List<Class<?>> params) { }

    /** 获取字段（沿父类链查找）并强制可访问；结果缓存。找不到抛 IllegalStateException。 */
    public static Field field(Class<?> clazz, String name)
    {
        return FIELD_CACHE
                .computeIfAbsent(clazz, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(name, n ->
                {
                    Field f = findField(clazz, n);
                    f.setAccessible(true);
                    return f;
                });
    }

    private static Field findField(Class<?> clazz, String name)
    {
        for (Class<?> k = clazz; k != null; k = k.getSuperclass())
        {
            try
            {
                return k.getDeclaredField(name);
            }
            catch (NoSuchFieldException ignored) { }
        }
        throw new IllegalStateException("No field " + name + " in " + clazz.getName());
    }

    /** 防御查询：字段不存在返回 {@link Optional#empty()}（不抛异常）。 */
    public static Optional<Field> tryField(Class<?> clazz, String name)
    {
        try
        {
            return Optional.of(field(clazz, name));
        }
        catch (Exception e)
        {
            return Optional.empty();
        }
    }

    /** 读取字段值（沿父类链查找）；失败抛 RuntimeException。 */
    @SuppressWarnings("unchecked")
    public static <T> T get(Object obj, String fieldName)
    {
        try
        {
            return (T) field(obj.getClass(), fieldName).get(obj);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Reflect.get[" + fieldName + "] on " + obj.getClass().getName(), e);
        }
    }

    /** 防御读取：失败（字段不存在 / 访问受限）返回 {@code defaultValue}，绝不抛异常。 */
    @SuppressWarnings("unchecked")
    public static <T> T getOr(Object obj, String fieldName, T defaultValue)
    {
        try
        {
            return (T) field(obj.getClass(), fieldName).get(obj);
        }
        catch (Exception e)
        {
            return defaultValue;
        }
    }

    /** 写入字段值（沿父类链查找）；失败抛 RuntimeException。 */
    public static void set(Object obj, String fieldName, Object value)
    {
        try
        {
            field(obj.getClass(), fieldName).set(obj, value);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Reflect.set[" + fieldName + "] on " + obj.getClass().getName(), e);
        }
    }

    /** 防御写入：失败返回 false（不抛异常）。 */
    public static boolean trySet(Object obj, String fieldName, Object value)
    {
        try
        {
            field(obj.getClass(), fieldName).set(obj, value);
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** 获取方法（沿父类链查找）并强制可访问；结果缓存。 */
    public static Method method(Class<?> clazz, String name, Class<?>... params)
    {
        MethodKey key = new MethodKey(clazz, name, List.of(params));
        return METHOD_CACHE.computeIfAbsent(key, k ->
        {
            for (Class<?> c = k.clazz(); c != null; c = c.getSuperclass())
            {
                try
                {
                    Method m = c.getDeclaredMethod(k.name(), k.params().toArray(new Class<?>[0]));
                    m.setAccessible(true);
                    return m;
                }
                catch (NoSuchMethodException ignored) { }
            }
            throw new IllegalStateException("No method " + k.name() + " in " + k.clazz().getName());
        });
    }

    /** 从对象运行时类开始沿父类链查找方法。 */
    public static Method method(Object obj, String name, Class<?>... params)
    {
        return method(obj.getClass(), name, params);
    }

    /** 反射构造实例（绕过可见性）。用于 private 构造器。 */
    public static <T> T construct(Class<T> clazz, Class<?>[] paramTypes, Object... args)
    {
        try
        {
            Constructor<T> c = clazz.getDeclaredConstructor(paramTypes);
            c.setAccessible(true);
            return c.newInstance(args);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Reflect.construct " + clazz.getName(), e);
        }
    }
}
