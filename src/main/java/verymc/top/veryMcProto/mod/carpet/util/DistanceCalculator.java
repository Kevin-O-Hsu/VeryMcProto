package verymc.top.veryMcProto.mod.carpet.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * /distance 距离计算体（上游 {@code carpet/utils/DistanceCalculator.java} 26.2 树逐字迁包——
 * <b>非寻路算法</b>：两点 Spherical/Cylindrical/Manhattan 三距离公式，phases/scope 文档旧述
 * "A* 寻路/Tracer" 系笔误已勘误，docs/carpet/porting-scope.md §3 / docs/carpet/phases.md §7）。
 *
 * <p>{@link #START_POINT_STORAGE} 按命令源名键控（{@code from}/{@code to} 两次独立调用间的进程内
 * 配对状态，上游同构）；{@link #findDistanceBetweenTwoPoints} 返回组件列表（Component 构造为纯
 * 数据操作，纯 JVM 可直调——单测锚 {@code DistanceCalculatorTest}）。
 *
 * <p>公式细节逐字：dx/dy/dz = {@code Mth.abs((float)a-(float)b)}（float 域减法后取绝对值再升
 * double——勿改写为 {@code Math.abs(a-b)} 双精度形态，末位精度与 %.2f 舍入面不同）。
 */
public class DistanceCalculator
{
    public static final HashMap<String, Vec3> START_POINT_STORAGE = new HashMap<>();

    public static boolean hasStartingPoint(CommandSourceStack source)
    {
        return START_POINT_STORAGE.containsKey(source.getTextName());
    }

    public static List<Component> findDistanceBetweenTwoPoints(Vec3 pos1, Vec3 pos2)
    {
        double dx = Mth.abs((float)pos1.x-(float)pos2.x);
        double dy = Mth.abs((float)pos1.y-(float)pos2.y);
        double dz = Mth.abs((float)pos1.z-(float)pos2.z);
        double manhattan = dx+dy+dz;
        double spherical = Math.sqrt(dx*dx + dy*dy + dz*dz);
        double cylindrical = Math.sqrt(dx*dx + dz*dz);
        List<Component> res = new ArrayList<>();
        res.add(Messenger.c("w Distance between ",
                Messenger.tp("c",pos1),"w  and ",
                Messenger.tp("c",pos2),"w :"));
        res.add(Messenger.c("w  - Spherical: ", String.format("wb %.2f", spherical)));
        res.add(Messenger.c("w  - Cylindrical: ", String.format("wb %.2f", cylindrical)));
        res.add(Messenger.c("w  - Manhattan: ", String.format("wb %.1f", manhattan)));
        return res;
    }

    public static int distance(CommandSourceStack source, Vec3 pos1, Vec3 pos2)
    {
        Messenger.send(source, findDistanceBetweenTwoPoints(pos1, pos2));
        return 1;
    }

    public static int setStart(CommandSourceStack source, Vec3 pos)
    {
        START_POINT_STORAGE.put(source.getTextName(), pos);
        Messenger.m(source,"gi Initial point set to: ", Messenger.tp("g",pos));
        return 1;
    }

    public static int setEnd(CommandSourceStack source, Vec3 pos)
    {
        if ( !hasStartingPoint(source) )
        {
            START_POINT_STORAGE.put(source.getTextName(), pos);
            Messenger.m(source,"gi There was no initial point for "+source.getTextName());
            Messenger.m(source,"gi Initial point set to: ", Messenger.tp("g",pos));
            return 0;
        }
        Messenger.send(source, findDistanceBetweenTwoPoints( START_POINT_STORAGE.get(source.getTextName()), pos));
        return 1;
    }
}
