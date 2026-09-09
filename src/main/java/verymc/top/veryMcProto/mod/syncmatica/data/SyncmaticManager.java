package verymc.top.veryMcProto.mod.syncmatica.data;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaContext;
import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaReference;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaDebug;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaLog;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaUtil;
import com.google.gson.*;

/**
 * placement 注册表 + 持久化（移植自 {@code ch.endte.syncmatica.data.SyncmaticManager}）。
 *
 * <p>内部 {@code Map<UUID, ServerPlacement>}（key = placement.id）。每次 add/remove/update 立即 saveServer；
 * startup 读 placements.json，shutdown 写。原子写（backup → current ← incoming，{@link SyncmaticaUtil#backupAndReplace}）。
 *
 * <p><b>Paper 适配</b>：
 * <ul>
 *   <li>{@code fromJson(elem, context)} → {@code fromJson(elem, context.getPlayerIdentifierProvider())}（P2 解耦）；</li>
 *   <li>服务端 peek 修正（原版在 fromJson 内 {@code context.isServer()} 分支）→ 读 JSON 后调
 *       {@link ServerPlacement#correctMetadataFromPeek}（P2 抽出）；</li>
 *   <li>去掉旧路径迁移（{@code Reference.CONFIG_ROOT}，Paper 无等价）；</li>
 *   <li>{@code Syncmatica.LOGGER} → {@link SyncmaticaLog}；路径用 {@link SyncmaticaReference#PLACEMENTS_FILE_NAME}。</li>
 * </ul>
 */
public class SyncmaticManager
{
    public static final String PLACEMENTS_JSON_KEY = "placements";
    private final Map<UUID, ServerPlacement> schematics = new HashMap<>();
    private final Collection<Consumer<ServerPlacement>> consumers = new ArrayList<>();

    SyncmaticaContext context;

    public void setContext(final SyncmaticaContext con)
    {
        if (context == null)
        {
            context = con;
        }
        else
        {
            throw new SyncmaticaContext.DuplicateContextAssignmentException("Duplicate Context assignment");
        }
    }

    public void addPlacement(final ServerPlacement placement)
    {
        schematics.put(placement.getId(), placement);
        updateServerPlacement(placement);
    }

    public ServerPlacement getPlacement(final UUID id)
    {
        return schematics.get(id);
    }

    public Collection<ServerPlacement> getAll()
    {
        return schematics.values();
    }

    public boolean hasPlacementHash(UUID hash)
    {
        AtomicBoolean bool = new AtomicBoolean(false);

        this.getAll().forEach(
                (p) ->
                {
                    if (p.getHash().compareTo(hash) == 0)
                    {
                        bool.set(true);
                    }
                });

        return bool.get();
    }

    public void removePlacement(final ServerPlacement placement)
    {
        schematics.remove(placement.getId());
        updateServerPlacement(placement);
    }

    public void addServerPlacementConsumer(final Consumer<ServerPlacement> consumer)
    {
        consumers.add(consumer);
    }

    public void removeServerPlacementConsumer(final Consumer<ServerPlacement> consumer)
    {
        consumers.remove(consumer);
    }

    public void updateServerPlacement(final ServerPlacement updated)
    {
        for (final Consumer<ServerPlacement> consumer : consumers)
        {
            consumer.accept(updated);
        }

        if (context.isServer())
        {
            saveServer();
        }
    }

    public void startup()
    {
        if (context.isServer())
        {
            loadServer();
        }
    }

    public void shutdown()
    {
        if (context.isServer())
        {
            saveServer();
        }
    }

    private void saveServer()
    {
        final JsonObject obj = new JsonObject();
        final JsonArray arr = new JsonArray();

        for (final ServerPlacement p : getAll())
        {
            // Sanitize the FileName
            Pattern pattern = Pattern.compile("[^/\\\\]+$");
            Matcher matcher = pattern.matcher(p.getFileName());

            if (matcher.find())
            {
                String result = matcher.group();
                ServerPlacement px = p.setFileName(result);
                arr.add(px.toJson());
            }
            else
            {
                arr.add(p.toJson());
            }
        }

        obj.add(PLACEMENTS_JSON_KEY, arr);

        final Path backup = context.getConfigFolder().resolve(SyncmaticaReference.PLACEMENTS_FILE_NAME + ".bak");
        final Path incoming = context.getConfigFolder().resolve(SyncmaticaReference.PLACEMENTS_FILE_NAME + ".new");
        final Path current = context.getConfigFolder().resolve(SyncmaticaReference.PLACEMENTS_FILE_NAME);

        SyncmaticaDebug.log(SyncmaticaDebug.Cat.DATA, "saveServer(): placements path: '" + current.toAbsolutePath() + "'");

        // We still use FileWriter, etc for porting/compatibility -- for now.
        try (final FileWriter writer = new FileWriter(incoming.toFile()))
        {
            writer.write(new GsonBuilder().setPrettyPrinting().create().toJson(obj));
        }
        catch (final IOException e)
        {
            SyncmaticaLog.error("saveServer(): Exception writing incoming file '{}'; {}",
                    incoming.getFileName(), e.getLocalizedMessage());
            return;
        }

        SyncmaticaUtil.backupAndReplace(backup, current, incoming);
    }

    private void loadServer()
    {
        final Path f = context.getConfigFolder().resolve(SyncmaticaReference.PLACEMENTS_FILE_NAME);

        SyncmaticaDebug.log(SyncmaticaDebug.Cat.DATA, "loadServer(): placements path: [" + f.toAbsolutePath() + "]");

        if (Files.exists(f) && Files.isReadable(f))
        {
            JsonElement element = null;
            try (final FileReader reader = new FileReader(f.toFile()))
            {
                element = JsonParser.parseReader(reader);
            }
            catch (final Exception e)
            {
                SyncmaticaLog.error("loadServer(): Exception reading file '{}'; {}", f.getFileName(),
                        e.getLocalizedMessage());
            }
            if (element == null)
            {
                return;
            }
            try
            {
                final JsonObject obj = element.getAsJsonObject();
                if (obj == null || !obj.has(PLACEMENTS_JSON_KEY))
                {
                    return;
                }
                final JsonArray arr = obj.getAsJsonArray(PLACEMENTS_JSON_KEY);
                boolean dirty = false;
                for (final JsonElement elem : arr)
                {
                    // Paper：fromJson 改接收 PlayerIdentifierProvider（P2 解耦）
                    final ServerPlacement placement = ServerPlacement.fromJson(elem.getAsJsonObject(),
                            context.getPlayerIdentifierProvider());

                    if (placement != null)
                    {
                        // Paper：服务端 peek 修正（原版在 fromJson 内 context.isServer() 分支）
                        if (placement.correctMetadataFromPeek(context.getLitematicFolder()))
                        {
                            placement.markDirty();
                        }

                        if (placement.isDirty())
                        {
                            dirty = true;
                        }

                        schematics.put(placement.getId(), placement); // NOSONAR
                    }
                }

                // Dirty flag detected; re-save placements.json
                if (dirty)
                {
                    SyncmaticaLog.warn("loadServer(): Found a dirty placements.json; re-saving with corrections.");
                    this.saveServer();
                }
            }
            catch (final IllegalStateException | NullPointerException e)
            {
                SyncmaticaLog.error("loadServer(): Exception loading server placement; {}", e.getLocalizedMessage());
            }
        }
    }

}
