package verymc.top.veryMcProto.mod.servux.scheduler;

import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

/**
 * task 组 type 16（TASK_STATUS_SYNC）组帧器（26.1 移植，纯函数，单测锁形状）。
 *
 * <p>对照上游 {@code scheduler/info_hud/InfoHudSync.java:46-78 + InfoHudSyncChunks.java:38-72}（v3 极简合并）。
 * 线格式（客户端读端 litematica {@code InfoHudSync.onReceiveInfoSync:85-122} 实证）：
 * <ul>
 *   <li>进度帧：{@code {InfoHudComplete:false, InfoHudSync:[{Type:"REMAINING_CHUNKS", Data:[{n,rc,cx,cz}×≤10]}]}}；</li>
 *   <li>完成帧：<b>仅</b> {@code {InfoHudComplete:true}}、不带 InfoHudSync 键（客户端缺键回空列表，安全）；</li>
 *   <li>{@code Type} 字面量必须是枚举常量名 {@value #TYPE_REMAINING_CHUNKS}——客户端
 *       {@code InfoHudSyncType.valueOf} 无容错，未知值直接抛异常（B 轮实证，单测断言锁死）；</li>
 *   <li>条目首行为标题行（cx=-1，客户端跳过坐标渲染），rc = 待处理区块<b>总数</b>，n = 任务名。</li>
 * </ul>
 */
public final class InfoHudTaskSync
{
    /** 客户端 InfoHudSyncType 枚举常量名（不是注册名 "remaining_chunks"！）。 */
    public static final String TYPE_REMAINING_CHUNKS = "REMAINING_CHUNKS";

    private InfoHudTaskSync() { }

    /**
     * 进度帧：标题行（cx=-1）+ 最近优先的前 10 个区块坐标（上游 TaskBase.updateInfoHudLinesPendingChunks:162-173）。
     *
     * @param title 任务名（"Fill" / "Delete"）
     * @param sortedPending 已按最近优先排序的待处理区块列表
     */
    public static CompoundTag progressFrame(String title, List<ChunkPos> sortedPending)
    {
        ListTag dataList = new ListTag();
        int total = sortedPending.size();
        int maxLines = Math.min(total, 10);

        dataList.add(entryToData(title, total, -1, -1));

        for (int i = 0; i < maxLines; ++i)
        {
            ChunkPos pos = sortedPending.get(i);
            dataList.add(entryToData(title, total, pos.x(), pos.z()));
        }

        CompoundTag chunkInfo = new CompoundTag();
        chunkInfo.putString("Type", TYPE_REMAINING_CHUNKS);
        chunkInfo.put("Data", dataList);

        ListTag syncList = new ListTag();
        syncList.add(chunkInfo);

        CompoundTag data = new CompoundTag();
        data.putBoolean("InfoHudComplete", false);
        data.put("InfoHudSync", syncList);
        return data;
    }

    /** 完成帧：仅 InfoHudComplete=true（客户端移除 HUD renderer 的唯一信号）。 */
    public static CompoundTag completeFrame()
    {
        CompoundTag data = new CompoundTag();
        data.putBoolean("InfoHudComplete", true);
        return data;
    }

    private static CompoundTag entryToData(String n, int rc, int cx, int cz)
    {
        CompoundTag data = new CompoundTag();
        data.putString("n", n);
        data.putInt("rc", rc);
        data.putInt("cx", cx);
        data.putInt("cz", cz);
        return data;
    }

    /** 供断言用：判定一个进度帧条目的 Type 字面量是否为 REMAINING_CHUNKS。 */
    public static boolean isRemainingChunksType(CompoundTag chunkEntry)
    {
        return TYPE_REMAINING_CHUNKS.equals(chunkEntry.getStringOr("Type", ""));
    }
}
