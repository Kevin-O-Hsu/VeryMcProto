package verymc.top.veryMcProto.mod.servux.util.nbt;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import verymc.top.veryMcProto.framework.reflect.Reflect;

/**
 * NBT 视图（mod 层）。移植自原版 {@code NbtView}，<b>绕开 IMixinNbtReadView/WriteView</b>。
 *
 * <p>原版用 Mixin {@code @Accessor} 取 {@link TagValueOutput} 的私有字段 {@code output}/{@code ops}
 * 与 {@link TagValueInput} 的 {@code input}/{@code context}。Paper 无 Mixin，改用 {@link Reflect#getOr}
 * 反射（字段名 Mojang，reobf 不转换，命中正确）。
 *
 * <p>核心用途：{@code entity.saveWithoutId(view.getWriter())} 写实体 NBT，再 {@link #readNbt} 取回 CompoundTag。
 * 这是 Entities / Litematics 的实体 NBT 序列化命门。
 */
public class NbtView
{
    private static final Logger LOGGER = LoggerFactory.getLogger("servux-NbtView");
    private static final ProblemReporter log = new ProblemReporter.ScopedCollector(LOGGER);

    private ValueInput reader;
    private ValueOutput writer;

    private NbtView() { }

    public static NbtView getReader(CompoundTag nbt, @Nonnull RegistryAccess registry)
    {
        NbtView v = new NbtView();
        v.reader = TagValueInput.create(log, registry, nbt);
        return v;
    }

    public static NbtView getWriter(@Nonnull RegistryAccess registry)
    {
        NbtView v = new NbtView();
        v.writer = TagValueOutput.createWithContext(log, registry);
        return v;
    }

    public ProblemReporter getErrorReporter() { return log; }

    public boolean isReader() { return this.reader != null; }
    public boolean isWriter() { return this.writer != null; }

    public @Nullable ValueInput getReader() { return this.reader; }
    public @Nullable ValueOutput getWriter() { return this.writer; }

    public @Nullable TagValueInput asNbtReader() { return (TagValueInput) this.reader; }
    public @Nullable TagValueOutput asNbtWriter() { return (TagValueOutput) this.writer; }

    /**
     * 取出 Reader/Writer 包含的 CompoundTag。
     * <p>Reader → 反射 {@link TagValueInput} 的 {@code input}；Writer → 反射 {@link TagValueOutput} 的 {@code output}。
     * 反射失败返回 null（调用方需自检）。
     */
    public @Nullable CompoundTag readNbt()
    {
        try
        {
            if (this.isReader()) { return Reflect.getOr(this.reader, "input", null); }
            if (this.isWriter()) { return Reflect.getOr(this.writer, "output", null); }
        }
        catch (Exception e)
        {
            LOGGER.warn("readNbt: 反射失败: {}", e.toString());
        }
        return null;
    }
}
