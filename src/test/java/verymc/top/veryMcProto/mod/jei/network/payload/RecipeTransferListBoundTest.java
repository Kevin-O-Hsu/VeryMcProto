package verymc.top.veryMcProto.mod.jei.network.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 转移包列表解码容量封顶单测（DoS 回归）。声明 count 攻击者可控，预分配容量经
 * {@code Math.min(count, 65536)} 封顶——与 vanilla 26.1.2 {@code ByteBufCodecs.collection(...)}
 * decode 同源；超大声明不得直达分配器（{@code OutOfMemoryError} 是 Error，穿透全部
 * catch(Exception)），负数由 ArrayList 构造器抛 IAE。主验证 {@code readVarIntList}
 * （{@code readOperations} 同型同改，但需 RegistryFriendlyByteBuf 构造成本，机制单侧覆盖）。
 */
class RecipeTransferListBoundTest
{
    @Test
    void hugeDeclaredCountFailsFastInsteadOfOom()
    {
        // 5 字节 VarInt 声明 2^31-1，零元素字节跟随——修复前此处为 new ArrayList<>(2147483647)
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(Integer.MAX_VALUE);
        // 26.1.2 VarInt.read → netty readByte：字节耗尽抛 IndexOutOfBoundsException（Exception 系，
        // 被 JeiServerPlayHandler 逐包 catch 承接），OOM 向量已在分配前消除
        assertThrows(IndexOutOfBoundsException.class, () -> AbstractRecipeTransferPacket.readVarIntList(buf));
        buf.release();
    }

    @Test
    void negativeCountRejectedByArrayListCtor()
    {
        // Math.min(-1, 65536) = -1 → ArrayList 构造器 "Illegal Capacity: -1"（vanilla collection
        // decode 对负 count 同样经构造器 IAE 终止，非静默空表）
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(-1);
        assertThrows(IllegalArgumentException.class, () -> AbstractRecipeTransferPacket.readVarIntList(buf));
        buf.release();
    }

    @Test
    void legitListRoundTrip()
    {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(3);
        buf.writeVarInt(0);
        buf.writeVarInt(1);
        buf.writeVarInt(127);
        List<Integer> list = AbstractRecipeTransferPacket.readVarIntList(buf);
        assertEquals(List.of(0, 1, 127), list);
        buf.release();
    }

    @Test
    void capIsHintOnlyListGrowsPast65536()
    {
        // >32767 字节输入在实线 C2S（vanilla DiscardedPayload 帧上限）不可达——本例为纯函数级
        // 验证：封顶仅是初始容量提示，超限真实列表仍正确增长解码
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(70000);
        for (int i = 0; i < 70000; i++)
        {
            buf.writeVarInt(0);
        }
        List<Integer> list = AbstractRecipeTransferPacket.readVarIntList(buf);
        assertEquals(70000, list.size());
        buf.release();
    }
}
