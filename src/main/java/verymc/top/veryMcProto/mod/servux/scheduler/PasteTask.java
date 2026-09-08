package verymc.top.veryMcProto.mod.servux.scheduler;

import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import verymc.top.veryMcProto.mod.servux.schematic.placement.SchematicPlacement;
import verymc.top.veryMcProto.mod.servux.util.IntBoundingBox;
import verymc.top.veryMcProto.mod.servux.util.LayerRange;
import verymc.top.veryMcProto.mod.servux.util.PasteLayerBehavior;
import verymc.top.veryMcProto.mod.servux.util.ReplaceBehavior;
import verymc.top.veryMcProto.mod.servux.util.SchematicPlacingUtils;
import verymc.top.veryMcProto.mod.servux.util.position.PositionUtils;

/**
 * Litematica 投影粘贴任务（26.1 移植）——合并上游
 * {@code TaskPasteSchematicPerChunkBase + TaskPasteSchematicPerChunkDirect}（paste 任务化，docs/09 §26.1.5）。
 *
 * <p>行为真值逐项对照上游 {@code TaskPasteSchematicPerChunkDirect.java:51-138}：
 * <ul>
 *   <li><b>动态预算</b>：elapsed = 最近第 100 个原版 tick 耗时
 *       （{@code server.getTickTimesNanos()[getTickCount()%100]}）+ 本任务本 tick 已耗时，≥ <b>60ms</b> 即让出
 *       （Direct:63-76 逐字）——与 Fill/Delete 的固定 25ms（MultiPhase 路径）<b>刻意不同源，勿"顺手统一"</b>；</li>
 *   <li><b>每 tick 无条件推帧</b>（Direct:98），不同于 Fill/Delete 的 {@code processedChunksThisTick>0} 门控；</li>
 *   <li><b>ignoreBlocks && ignoreEntities 早退</b>（Direct:53-57）：返回 true 但<b>不置 finished</b> →
 *       stop() 走 paste.failed 文案——上游同源怪癖，照抄勿"修正"；</li>
 *   <li>区块加载判定 radius <b>1</b>（Base:98-102 → TaskBase:115-137，周边 3×3 全加载），严于 Fill/Delete 的
 *       radius 0——半径不足时 chunk 留队等待（上游同源：玩家远离后任务挂起直至区块再加载）；</li>
 *   <li><b>失败重试</b>：{@code placeToWorldWithinChunk} 返回 false 的 chunk 留队下 tick 重试——若投影 region
 *       数据损坏（容器缺失）将<b>无限重试 + 每 tick 推帧</b>直至插件停用（上游 Direct 同源 liveness 缺陷，
 *       非我方回归）；</li>
 *   <li><b>单 chunk 内无时间预算</b>（Direct:105-121 同源）——巨型区块单次调用可击穿 60ms 上限。</li>
 * </ul>
 *
 * <p><b>有意偏差（相对上游，B/CF 轮终审裁定）</b>：
 * <ul>
 *   <li><b>单 placement 字段</b>：上游 multimap（{@code placementsPerChunk}）支持多 placement，但上游自身两处
 *       调用点恒 {@code singletonList(placement)}（上游 LitematicsDataProvider:682/:734），我方两入口亦单投影——
 *       按"上游零调用点的泛化即裁"先例收敛（同 TaskScheduler 裁 hasTask）；</li>
 *   <li><b>钳制盒子用后即弃</b>：上游 {@code boxesInChunks} 在 Direct 执行期零读取（仅构造期 count&gt;0 选 chunk）；</li>
 *   <li>{@code changedBlocksOnly / ignoreBlocks / ignoreEntities} 存而不用——上游 Direct.processChunk:107 同源
 *       TODO，保留字段即保留未来上游消费时的对齐锚点（仅 ignore 双真早退消费）；</li>
 *   <li>{@code layerRange} 为 null 时跳过层域钳制（防御：我方 {@code RenderLayerRange} 解析可空；上游无守卫系
 *       客户端恒发，null 直接解引用会 NPE）。</li>
 * </ul>
 */
public class PasteTask extends LitematicaTask
{
    // ───── 上游 TaskFeedbackListener 的消息文案（servux en_us.json:161-162 原文，逐字——注意失败态大写 P）─────
    static final String MSG_PASTE_SUCCESSFUL = "§aServux Task: Schematic pasted in world§r";
    static final String MSG_PASTE_INTERRUPTED = "§cServux Task: Schematic Paste to world failed§r";

    private final SchematicPlacement placement;
    @Nullable private final LayerRange layerRange;
    private final ReplaceBehavior replaceBehavior;
    private final PasteLayerBehavior layerBehavior;
    private final boolean changedBlocksOnly;   // 存而不用（上游 Direct:107 TODO 同源）
    private final boolean ignoreBlocks;        // 存而不用（同上；仅 ignore 双真早退消费）
    private final boolean ignoreEntities;      // 存而不用（同上）

    public PasteTask(MinecraftServer server,
                     ServerLevel level,
                     ServerPlayer player,
                     SchematicPlacement placement,
                     long startTime,
                     @Nullable LayerRange layerRange,
                     ReplaceBehavior replaceBehavior,
                     PasteLayerBehavior layerBehavior,
                     boolean changedBlocksOnly,
                     boolean ignoreBlocks,
                     boolean ignoreEntities)
    {
        super(placement.getName(), server, level, player, startTime);

        this.placement = placement;
        this.layerRange = layerRange;
        this.replaceBehavior = replaceBehavior;
        this.layerBehavior = layerBehavior;
        this.changedBlocksOnly = changedBlocksOnly;
        this.ignoreBlocks = ignoreBlocks;
        this.ignoreEntities = ignoreEntities;

        this.init();
    }

    // ───── 区块队列（上游 TaskPasteSchematicPerChunkBase.init/addPlacement:48-92 照抄，盒子用后即弃）─────

    /** 构造期建队（上游 scheduleTask → task.init() 的合并等价，FillDeleteTask 同 idiom）。 */
    private void init()
    {
        this.addPlacement();
        this.sortChunkList();
    }

    private void addPlacement()
    {
        Set<ChunkPos> touchedChunks = this.placement.getTouchedChunks();

        for (ChunkPos pos : touchedChunks)
        {
            int count = 0;

            for (IntBoundingBox box : this.placement.getBoxesWithinChunk(pos.x(), pos.z()).values())
            {
                if (this.clampBox(box) != null)
                {
                    ++count;
                }
            }

            if (count > 0)
            {
                this.pendingChunks.add(pos);
            }
        }
    }

    /** 上游 Base:70-84 双重钳制（LayerRange → 世界高度）；盒子仅用于 count 判定后即弃，不入任务状态。 */
    @Nullable
    private IntBoundingBox clampBox(IntBoundingBox box)
    {
        IntBoundingBox clamped = box;

        if (this.layerRange != null)
        {
            clamped = this.layerRange.getClampedArea(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        }

        if (clamped != null)
        {
            clamped = PositionUtils.clampBoxToWorldHeightRange(clamped, this.level);
        }

        return clamped;
    }

    // ───── 执行主循环（上游 Direct.execute:51-102 照抄）─────

    @Override
    boolean execute()
    {
        // Nothing to do（Direct:53-57）：双忽略 = 无事可做，立即完结——不置 finished，
        // stop() 走 paste.failed 文案（上游同源怪癖，勿"修正"）
        if (this.ignoreBlocks && this.ignoreEntities)
        {
            return true;
        }

        // 动态预算（Direct:63-73）：最近第 100 个原版 tick 耗时 + 本任务本 tick 已耗时 ≥ 60ms 即让出
        final long vanillaTickTime = this.server.getTickTimesNanos()[this.server.getTickCount() % 100];
        final long timeStart = System.nanoTime();

        this.sortChunkList();

        for (int chunkIndex = 0; chunkIndex < this.pendingChunks.size(); ++chunkIndex)
        {
            long elapsedTickTime = vanillaTickTime + (System.nanoTime() - timeStart);

            if (elapsedTickTime >= 60_000_000L)
            {
                break;
            }

            ChunkPos pos = this.pendingChunks.get(chunkIndex);

            if (this.canProcessChunk(pos) && this.processChunk(pos))
            {
                this.pendingChunks.remove(chunkIndex);
                --chunkIndex;
            }
        }

        if (this.pendingChunks.isEmpty())
        {
            this.finished = true;
            return true;
        }

        // Direct:98——paste 每 tick 无条件推帧（Fill/Delete 是进度变化门控，刻意不同源）
        this.updateInfoHudLines();
        return false;
    }

    // ───── 区块处理（上游 Base:98-102 → TaskBase:115-137 + Direct.processChunk:104-121 照抄）─────

    private boolean canProcessChunk(ChunkPos pos)
    {
        return this.areSurroundingChunksLoaded(pos, 1);
    }

    /** TaskBase.areSurroundingChunksLoaded:115-137 照抄（radius 1 = 周边 3×3 全加载，严于 Fill/Delete 的 radius 0）。 */
    private boolean areSurroundingChunksLoaded(ChunkPos pos, int radius)
    {
        for (int cx = pos.x() - radius; cx <= pos.x() + radius; ++cx)
        {
            for (int cz = pos.z() - radius; cz <= pos.z() + radius; ++cz)
            {
                if (this.level.getChunkSource().hasChunk(cx, cz) == false)
                {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean processChunk(ChunkPos pos)
    {
        // TODO ignoreBlocks and ignoreEntities（上游 Direct:107 同源 TODO，存而不用）

        // 失败留队下 tick 重试（Direct:114-120 语义；region 数据损坏时上游同源无限重试，见类 javadoc）
        return SchematicPlacingUtils.placeToWorldWithinChunk(this.level, pos, this.placement,
                                                             this.replaceBehavior, this.layerBehavior, this.layerRange, false);
    }

    @Override
    String successMessage() { return MSG_PASTE_SUCCESSFUL; }

    @Override
    String interruptedMessage() { return MSG_PASTE_INTERRUPTED; }
}
