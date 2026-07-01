package verymc.top.veryMcProto.mod.servux.easyplace;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;

/**
 * EasyPlace 启动桥（隔离 PacketEvents 类引用）。
 *
 * <p>本类是<b>唯一</b>在方法体内直接引用 PacketEvents 类（{@link PacketEvents} /
 * {@link PacketListenerPriority} / {@link EasyPlaceListener} implements PacketListener）的非 PE 代码点。
 *
 * <p><b>为何隔离</b>：若把 PE 引用直接写在 {@code ServuxModule.onRegister} 里，JVM 解析该方法时
 * 会触发 PE 类加载——服务器未装 PacketEvents 时抛 {@code NoClassDefFoundError}，且发生在方法体
 * 执行<b>之前</b>（类链接/方法解析阶段），{@code try/catch} 根本进不去，整个 ServuxModule 加载失败、
 * 6 个 provider 全部注册不了。把 PE 引用收敛到本类后，{@code ServuxModule} 用反射
 * {@code Class.forName("...EasyPlaceBootstrap")} 加载本类：PE 缺失只让本类加载失败，
 * 被 {@code catch(Throwable)} 优雅降级，provider 注册不受影响。
 */
public final class EasyPlaceBootstrap
{
    private EasyPlaceBootstrap() { }

    /** 注册 EasyPlace 包监听器。仅当服务器已安装 PacketEvents 时可调用。 */
    public static void register()
    {
        PacketEvents.getAPI().getEventManager()
                .registerListener(new EasyPlaceListener(), PacketListenerPriority.NORMAL);
    }
}
