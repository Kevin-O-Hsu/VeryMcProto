package verymc.top.veryMcProto.mod.servux.util.nbt;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;
import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtAccounterException;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;

import verymc.top.veryMcProto.Reference;

/**
 * malilib/servux 26.1「DataTag」线格式编解码器（vanilla {@link CompoundTag} 视角，纯函数）。
 *
 * <p>MC 26.1 起 servux 协议业务包的 NBT 载体从 vanilla {@code writeNbt} 切换为 malilib DataTag 线格式
 * （对照 {@code OriginImpl/servux-LTS-26.1 util/data/tag/util/DataByteBufUtils.java} 逐字节实证）：
 *
 * <pre>线格式 = [int32 大端 压缩后字节数][GZIP 压缩流]</pre>
 *
 * <p>流内为经典 NBT 具名根布局：{@code [byte tagType][writeUTF 根名（空串，读端丢弃）][CompoundTag 条目 + TAG_END]}，
 * 与原版 {@link NbtIo} 的具名根输出（空根名）<b>逐字节兼容</b>——故本类不移植 malilib 的 18 个 DataTag 类，
 * 直接以 NMS CompoundTag 为内部表示、借 {@link NbtIo} 完成序列化。
 *
 * <p>边界语义（上游实证，勿改）：
 * <ul>
 *   <li><b>写端恒发 tagType=10 根</b>（哪怕零键），绝不发上游 EmptyData 的 0x00 单字节形态——
 *       上游读端 {@code DataFileUtils.readFromNbtStream} 对 TAG_END 根返回 null → 包被整包丢弃；</li>
 *   <li>读端对 0x00 根（上游 EmptyData）容错为空 CompoundTag（上游调用方 {@code opt.ifPresent} 为静默丢弃，
 *       服务端收端选择保留空语义以便上层继续 Task 路由）；</li>
 *   <li>读端 GZIP 解压失败（{@link ZipException}）时回落按非压缩裸读（上游 {@code fromByteBuf} 同构）；</li>
 *   <li>长度前缀为<b>压缩后</b>字节数、大端 int32（{@code ByteBuf.writeInt}），<b>不是 VarInt</b>；</li>
 *   <li>单包 NBT 流上限 64MB（上游 {@code SizeTracker.NETWORK_MAX_BYTES}）。</li>
 * </ul>
 *
 * <p>读端配额闸（2026-10 安全修复）：解压与解析合并为「GZIP 流式直读 + {@link NbtAccounter}」单闸，
 * 上游 servux {@code NbtUtils#read} 同构（流上逐结构计数，配额 = {@link #NETWORK_MAX_BYTES} 解压后字节，
 * 与上游 {@code SizeTracker} 在解压流上逐字节计数同语义）。{@link NbtAccounter} 对长度字段驱动的大数组
 * <b>先记账后分配</b>（{@code LongArrayTag.readAccounted}：accountBytes(8×len) 先于 newarray long）——
 * 两类炸弹在同一道闸下被拦截：gzip bomb（解压膨胀）与长度字段炸弹（≤64MB 流内声明 long[2^31-1] 驱动
 * 16GB 分配）。超配额抛 {@code NbtAccounterException}（RuntimeException）→ 按坏包契约 warn + null 丢弃。
 * 勿回退为「整体解压进内存再解析」——那既多两次数据搬运，又漏掉全部配额（旧实现即此形态，2026-10 修复）。
 *
 * <p>读端异常三分语义（与旧实现逐点对齐，改动点已由 DataTagIoTest 锁定）：
 * <ul>
 *   <li><b>空 tag</b> = peek 到 0x00 根（EmptyData 容错）/ 空 payload / 解压期失败（EOF 等，
 *       含过短的 GZIP 头——对齐旧 {@code gunzipOrRaw} 解压失败 → 空 tag 路径）；</li>
 *   <li><b>null</b>（坏包丢弃 + warn）= {@code NbtAccounterException} 超配额 / NBT 解析失败
 *       （对齐旧 {@code NbtIo.read} catch 路径）；</li>
 *   <li><b>{@link ZipException}</b>（GZIP 魔数校验失败）→ 回落裸读分支，两分支共用同一 peek+配额逻辑。</li>
 * </ul>
 * 已知的恶意包映射差（接受并记录）：截断 gzip 在旧实现 EOF 于解压期 → 空 tag，流式实现若首字节已产出则
 * EOF 推迟到解析期 → null；两侧均安全失败，仅恶意/损坏包可达。
 */
public final class DataTagIo
{
    /** 上游 {@code SizeTracker.NETWORK_MAX_BYTES} = 64MB：单包 NBT 流（压缩前）大小上限。 */
    public static final long NETWORK_MAX_BYTES = 64L * 1024L * 1024L;

    private DataTagIo() { }

    /**
     * 把 CompoundTag 以 DataTag 线格式写入 FriendlyByteBuf（S2C 发送用）。
     * 序列化失败时防御性写「空 compound 的线格式」，绝不抛穿调用方（上游 toPacket 均为 try/catch + log 形态）。
     */
    public static void writeTag(FriendlyByteBuf output, @Nullable CompoundTag tag)
    {
        CompoundTag root = (tag != null) ? tag : new CompoundTag();

        if (root.sizeInBytes() > NETWORK_MAX_BYTES)
        {
            // 上游超限为 SizeTrackerException → 抛 IOException → 包被丢弃；这里同构降级为空包 + 日志
            Reference.logger().warning("DataTagIo#writeTag: NBT 超过 64MB 网络上限（" + root.sizeInBytes() + " 字节），降级为空 compound");
            root = new CompoundTag();
        }

        byte[] payload = gzipNamedRoot(root);
        output.writeInt(payload.length);
        output.writeBytes(payload);
    }

    /**
     * 从 FriendlyByteBuf 读出 DataTag 线格式的 CompoundTag（C2S 接收用）。
     * 长度非法（负数 / 超出可读字节 / 超 64MB）返回 null（调用方按坏包丢弃）。
     *
     * <p>解压与解析为 GZIP 流式直读（不物化中间 byte[]），配额由 {@link NbtAccounter} 在解压流上
     * 逐结构记账（详见类 javadoc「读端配额闸」）。
     */
    @Nullable
    public static CompoundTag readTag(FriendlyByteBuf input)
    {
        int length = input.readInt();

        if (length < 0 || length > input.readableBytes() || length > NETWORK_MAX_BYTES)
        {
            Reference.logger().warning("DataTagIo#readTag: 非法长度前缀 " + length + "（可读 " + input.readableBytes() + "）");
            return null;
        }

        byte[] payload = new byte[length];
        input.readBytes(payload);

        try
        {
            // 主路径：GZIP 流式直读（mark 缓冲必须位于解压流之上——GZIPInputStream 自身不支持 mark）
            return readStreamAccounted(new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(payload))));
        }
        catch (ZipException e)
        {
            // 裸读回落（上游 fromByteBuf 同构）：payload 本身即未压缩 NBT 流
            return readStreamAccounted(new BufferedInputStream(new ByteArrayInputStream(payload)));
        }
        catch (Exception e)
        {
            // GZIP 头读取失败（EOF——payload 过短/为空）→ 对齐旧 gunzipOrRaw 解压失败 → 空 tag 语义
            return new CompoundTag();
        }
    }

    /**
     * 在（已解压或裸的）输入流上做「peek 根类型 + NbtAccounter 配额解析」（readTag 主路径与裸读回落共用）。
     *
     * <p>异常三分语义见类 javadoc——注意 NbtAccounterException 的专属 catch 必须与 NbtIo.read 处于
     * 同一 try 体内（JLS 兄弟 catch 子句不互捕，拆分会导致回落分支的配额异常穿透逃逸）。
     *
     * @param bis 已按 mark(1) 预备的缓冲流（mark 缓冲承载 peek/reset，marklimit=1 足够）
     */
    @Nullable
    private static CompoundTag readStreamAccounted(BufferedInputStream bis)
    {
        try
        {
            bis.mark(1);
            final int first = bis.read();
            if (first == 0x00 || first == -1)
            {
                // 上游 EmptyData（TAG_END 根，单字节）/ 空流 → 空语义
                return new CompoundTag();
            }
            bis.reset();
        }
        catch (Exception e)
        {
            // peek 触发解压（Inflater 拉首字节）——EOF/截断 gzip 等解压期失败 → 空 tag（对齐旧实现）
            return new CompoundTag();
        }

        try
        {
            // NbtIo.read 对非 CompoundTag 根恒抛 IOException（无 null 返回），无需 null 分支
            return NbtIo.read(new DataInputStream(bis), NbtAccounter.create(NETWORK_MAX_BYTES));
        }
        catch (NbtAccounterException e)
        {
            Reference.logger().warning("DataTagIo#readTag: NBT 超 64MB 解压域配额（gzip bomb / 长度字段炸弹）: " + e.getMessage());
            return null;
        }
        catch (Exception e)
        {
            Reference.logger().warning("DataTagIo#readTag: NBT 解析失败: " + e.getMessage());
            return null;
        }
    }

    /** 序列化为 [GZIP(具名根 NBT 流)]：[10][utf ""][条目 + TAG_END]，与上游 writeToNbtStream 逐字节兼容。 */
    private static byte[] gzipNamedRoot(CompoundTag root)
    {
        try
        {
            ByteArrayOutputStream gz = new ByteArrayOutputStream();
            try (DataOutputStream os = new DataOutputStream(new GZIPOutputStream(gz)))
            {
                NbtIo.write(root, os);
            }
            return gz.toByteArray();
        }
        catch (Exception e)
        {
            Reference.logger().warning("DataTagIo#gzipNamedRoot: GZIP 序列化失败: " + e.getMessage());
            // 兜底：空 compound（一个 0 键的 TAG_COMPOUND 根，非 EmptyData 形态）
            try
            {
                ByteArrayOutputStream gz = new ByteArrayOutputStream();
                try (DataOutputStream os = new DataOutputStream(new GZIPOutputStream(gz)))
                {
                    NbtIo.write(new CompoundTag(), os);
                }
                return gz.toByteArray();
            }
            catch (Exception e2)
            {
                // GZIP 一个空 compound 不可能失败；此处仅形式完备
                return new byte[0];
            }
        }
    }
}
