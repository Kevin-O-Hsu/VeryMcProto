package verymc.top.veryMcProto.mod.syncmatica.data;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import verymc.top.veryMcProto.mod.syncmatica.extended_core.PlayerIdentifier;
import verymc.top.veryMcProto.mod.syncmatica.extended_core.PlayerIdentifierProvider;
import verymc.top.veryMcProto.mod.syncmatica.extended_core.SubRegionData;
import verymc.top.veryMcProto.mod.syncmatica.data.litematica.SchematicMetadata;
import verymc.top.veryMcProto.mod.syncmatica.data.litematica.SchematicSchema;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaDebug;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaLog;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.apache.commons.lang3.tuple.Pair;

/**
 * 服务端投影放置（移植自 {@code ch.endte.syncmatica.data.ServerPlacement}）。
 *
 * <p>纯 JSON 序列化（{@link #toJson} / {@link #fromJson}），字段顺序与原版逐字段一致（客户端虽不读此文件，
 * 但服务端自愈逻辑与持久化兼容性依赖）。hash = MD5→type-3 UUID（{@link SyncmaticaUtil#createChecksum}）。
 *
 * <p><b>Paper 适配</b>：
 * <ul>
 *   <li>删除 {@code matList}（{@code SyncmaticaMaterialList}）字段与 getter/setter——原版死代码
 *       （无 exchange/协议/命令/持久化引用），见 docs/22 §9；</li>
 *   <li>{@code fromJson} 去掉 {@code Context} 参数，改接收 {@link PlayerIdentifierProvider}（解耦 Context）；
 *       原版 {@code context.isServer()} 分支的 peek 元数据修正抽到 {@link #correctMetadataFromPeek(Path)}，
 *       由 {@code SyncmaticManager.loadServer} 调用；</li>
 *   <li>{@code Syncmatica.debug} → {@link SyncmaticaLog#debug}。</li>
 * </ul>
 */
public class ServerPlacement
{
    private final UUID id;
    private final UUID hashValue; // UUID for the file contents
    // UUID since easier to transmit compare etc.
    private Path file; // Stores as a Path just for easier file name operations
    private String fileName; // The basic "file name" field that may, or may not point to the actual origin file.

    private PlayerIdentifier owner; // player that shared it
    private PlayerIdentifier lastModifiedBy; // player that last modified it

    private ServerPosition origin;
    private Rotation rotation;
    private Mirror mirror;
    private SubRegionData subRegionData = new SubRegionData();

    // Feature.DISPLAY_NAME
    private String displayName; // Save the proper Display Name of the Litematic file
    private boolean dirty;

    // Feature.VERSION
    private int dataVersion;
    private int litematicVersion;

    public ServerPlacement(final UUID id, final Path file, final String fileName, final String displayName,
                           final UUID hashValue, final PlayerIdentifier owner, int litematicVersion, int dataVersion)
    {
        this.id = id;
        this.file = file;
        this.fileName = fileName;
        this.displayName = displayName;
        this.hashValue = hashValue;
        this.owner = owner;
        this.lastModifiedBy = owner;
        this.litematicVersion = litematicVersion;
        this.dataVersion = dataVersion;
        this.dirty = false;
    }

    public ServerPlacement(final UUID id, final Path file, final String displayName, final PlayerIdentifier owner)
    {
        this(id, file, file.toAbsolutePath().toString(), displayName, generateHash(file), owner, -1, -1);
    }

    public ServerPlacement(UUID id, String fileName, final String displayName, UUID hash, PlayerIdentifier owner)
    {
        this(id, Path.of(fileName), fileName, displayName, hash, owner, -1, -1);
    }

    public ServerPlacement(UUID id, String fileName, final String displayName, UUID hash, PlayerIdentifier owner,
                           int litematicVersion, int dataVersion)
    {
        this(id, Path.of(fileName), fileName, displayName, hash, owner, litematicVersion, dataVersion);
    }

    public UUID getId()
    {
        return id;
    }

    public Path getFile()
    {
        return this.file;
    }

    public String getName()
    {
        return this.displayName;
    }

    public String getFileName()
    {
        return this.fileName;
    }

    public UUID getHash()
    {
        return hashValue;
    }

    public String getDimension()
    {
        return origin.getDimensionId();
    }

    public BlockPos getPosition()
    {
        return origin.getBlockPosition();
    }

    public ServerPosition getOrigin()
    {
        return origin;
    }

    public Rotation getRotation()
    {
        return rotation;
    }

    public Mirror getMirror()
    {
        return mirror;
    }

    // Feature.VERSION
    public int getLitematicVersion() {return litematicVersion;}

    public int getDataVersion() {return dataVersion;}

    public ServerPlacement setVersion(final int litematicVersion, final int dataVersion)
    {
        this.litematicVersion = litematicVersion;
        this.dataVersion = dataVersion;
        return this;
    }

    public ServerPlacement move(final String dimensionId, final BlockPos origin, final Rotation rotation,
                                final Mirror mirror)
    {
        move(new ServerPosition(origin, dimensionId), rotation, mirror);
        return this;
    }

    public ServerPlacement move(final ServerPosition origin, final Rotation rotation, final Mirror mirror)
    {
        this.origin = origin;
        this.rotation = rotation;
        this.mirror = mirror;
        return this;
    }

    public ServerPlacement setFile(Path file)
    {
        this.file = file;
        this.fileName = file.toAbsolutePath().toString();
        return this;
    }

    public ServerPlacement setSchema(SchematicSchema schema)
    {
        this.litematicVersion = schema.litematicVersion();
        this.dataVersion = schema.minecraftDataVersion();
        return this;
    }

    public ServerPlacement setMetadata(SchematicMetadata meta)
    {
        this.displayName = meta.getName();
        return this;
    }

    public PlayerIdentifier getOwner()
    {
        return owner;
    }

    public void setOwner(final PlayerIdentifier playerIdentifier)
    {
        owner = playerIdentifier;
    }

    public PlayerIdentifier getLastModifiedBy()
    {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(final PlayerIdentifier lastModifiedBy)
    {
        this.lastModifiedBy = lastModifiedBy;
    }

    public SubRegionData getSubRegionData()
    {
        return subRegionData;
    }

    public static String removeExtension(final Path file)
    {
        // source stackoverflow
        final String fileName = file.toString();
        final int pos = fileName.lastIndexOf(".");
        return fileName.substring(0, pos);
    }

    public static String removeExtension(final String fileName)
    {
        // source stackoverflow
        final int pos = fileName.lastIndexOf(".");
        return fileName.substring(0, pos);
    }

    public static String normalizeFileName(final String badFileName)
    {
        String fileName = badFileName;

        if (badFileName.contains("/") || badFileName.contains("\\"))
        {
            fileName = SyncmaticaUtil.sanitizeUnicodeSubDirFileName(badFileName);

            Path dirtyPath = Paths.get(fileName);
            fileName = dirtyPath.getFileName().toString();
        }
        else
        {
            fileName = SyncmaticaUtil.sanitizeUnicodeFileName(badFileName);
        }

        SyncmaticaDebug.log(SyncmaticaDebug.Cat.DATA, "normalizeFileName(): Normalizing placement filename '" + badFileName + "' to: '" + fileName + "'");

        return fileName;
    }

    public String getNormalFileName()
    {
        return normalizeFileName(this.fileName);
    }

    private static UUID generateHash(final Path file)
    {
        UUID hash = null;
        try
        {
            hash = SyncmaticaUtil.createChecksum(new FileInputStream(file.toFile()));
        }
        catch (final Exception e)
        {
            throw new RuntimeException(e);
        }
        return hash;
    }

    public boolean isDirty()
    {
        return this.dirty;
    }

    public void markDirty()
    {
        this.dirty = true;
    }

    public JsonObject toJson()
    {
        final JsonObject obj = new JsonObject();
        obj.add("id", new JsonPrimitive(id.toString()));

        obj.add("file_name", new JsonPrimitive(this.fileName));
        // Feature.DISPLAY_NAME
        obj.add("display_name", new JsonPrimitive(this.displayName));
        obj.add("hash", new JsonPrimitive(hashValue.toString()));

        obj.add("origin", origin.toJson());
        obj.add("rotation", new JsonPrimitive(rotation.name()));
        obj.add("mirror", new JsonPrimitive(mirror.name()));

        obj.add("owner", owner.toJson());
        if (!owner.equals(lastModifiedBy))
        {
            obj.add("lastModifiedBy", lastModifiedBy.toJson());
        }

        if (subRegionData.isModified())
        {
            obj.add("subregionData", subRegionData.toJson());
        }
        // Feature.VERSION
        if (litematicVersion > -1)
        {
            obj.add("litematicVersion", new JsonPrimitive(litematicVersion));
        }
        if (dataVersion > -1)
        {
            obj.add("dataVersion", new JsonPrimitive(dataVersion));
        }

        return obj;
    }

    /**
     * 从 JSON 解析（纯解析，不做服务端 peek 修正）。
     *
     * <p><b>Paper 适配</b>：原版 {@code fromJson(obj, Context)} 含 {@code context.isServer()} 分支的 peek 修正。
     * 此处解耦为纯解析（owner/lastModifiedBy 经 {@code provider} 归一化），服务端修正由
     * {@link #correctMetadataFromPeek} 独立完成，由 {@code SyncmaticManager.loadServer} 在读完 JSON 后调用。
     *
     * @param obj      JSON 对象
     * @param provider 玩家标识归一化器（owner / lastModifiedBy 反序列化用）
     * @return 解析出的 placement；字段缺失或格式错误返回 {@code null}
     */
    public static ServerPlacement fromJson(final JsonObject obj, final PlayerIdentifierProvider provider)
    {
        if (obj == null
            || !obj.has("id")
            || !obj.has("file_name")
            || !obj.has("hash")
            || !obj.has("origin")
            || !obj.has("rotation")
            || !obj.has("mirror"))
        {
            return null;
        }

        // Paper 防御性增强：整个解析块 try/catch，损坏条目返回 null 而非炸掉整表加载。
        try
        {
            final UUID id = UUID.fromString(obj.get("id").getAsString());
            final String badFileName = obj.get("file_name").getAsString();
            final UUID hashValue = UUID.fromString(obj.get("hash").getAsString());
            String displayName;
            int version = -1;
            int dataVersion = -1;
            boolean dirty = false;

            PlayerIdentifier owner = PlayerIdentifier.MISSING_PLAYER;
            if (obj.has("owner"))
            {
                owner = provider.fromJson(obj.get("owner").getAsJsonObject());
            }

            // Feature.DISPLAY_NAME
            if (obj.has("display_name"))
            {
                displayName = obj.get("display_name").getAsString();
            }
            else
            {
                // Check for Absolute Paths being used, and fix
                displayName = normalizeFileName(badFileName);
                SyncmaticaDebug.log(SyncmaticaDebug.Cat.DATA, "ServerPlacement#fromJson(): displayName NORMALIZE [" + displayName + "] --> Dirty");
                dirty = true;
            }

            // Feature.VERSION
            if (obj.has("litematicVersion"))
            {
                version = obj.get("litematicVersion").getAsInt();
            }
            if (obj.has("dataVersion"))
            {
                dataVersion = obj.get("dataVersion").getAsInt();
            }

            String fileName = badFileName;

            if (!badFileName.endsWith(".litematic"))
            {
                fileName = SyncmaticaUtil.sanitizeUnicodeFileName(badFileName) + ".litematic";
                SyncmaticaDebug.log(SyncmaticaDebug.Cat.DATA, "ServerPlacement#fromJson(): no Extension [" + badFileName + "] -> [" + fileName + "] --> Dirty");
                dirty = true;
            }

            final ServerPlacement newPlacement = new ServerPlacement(id, fileName, displayName, hashValue, owner,
                                                                     version, dataVersion);

            final ServerPosition pos = ServerPosition.fromJson(obj.get("origin").getAsJsonObject());

            if (pos == null)
            {
                return null;
            }
            newPlacement.origin = pos;
            newPlacement.rotation = Rotation.valueOf(obj.get("rotation").getAsString());
            newPlacement.mirror = Mirror.valueOf(obj.get("mirror").getAsString());

            if (obj.has("lastModifiedBy"))
            {
                newPlacement.lastModifiedBy = provider.fromJson(obj.get("lastModifiedBy").getAsJsonObject());
            }
            else
            {
                if (!newPlacement.lastModifiedBy.getName().equals(owner.getName()))
                {
                    SyncmaticaDebug.log(SyncmaticaDebug.Cat.DATA, "ServerPlacement#fromJson(): Update owner: [" + newPlacement.lastModifiedBy.getName() + "] -> [" + owner.getName() + "] --> Dirty");
                    dirty = true;
                }

                newPlacement.lastModifiedBy = owner;
            }

            if (obj.has("subregionData"))
            {
                newPlacement.subRegionData = SubRegionData.fromJson(obj.get("subregionData"));
            }

            // This means that something has changed to correct the placement data at load time
            if (dirty)
            {
                newPlacement.markDirty();
            }

            return newPlacement;
        }
        catch (final Exception e)
        {
            SyncmaticaLog.warn("ServerPlacement#fromJson(): failed to parse placement entry; {}", e.getLocalizedMessage());
            return null;
        }
    }

    /**
     * 服务端加载时用文件 peek 修正 metadata（原版 {@code fromJson} 内 {@code context.isServer()} 分支，Paper 抽出解耦 Context）。
     *
     * <p>当 {@code <litematicFolder>/<hash>.litematic} 存在且其内部 Name 与当前 displayName 不一致时，
     * 用文件内的 Name / litematicVersion / minecraftDataVersion 覆盖。
     *
     * @param litematicFolder 投影文件目录
     * @return {@code true} 若发生了修正（调用方据此 {@link #markDirty()} 并回写）
     */
    public boolean correctMetadataFromPeek(final Path litematicFolder)
    {
        final Path testFile = litematicFolder.resolve(hashValue + ".litematic");
        final Pair<SchematicMetadata, SchematicSchema> pair = SyncmaticaUtil.litematicPeek(testFile);

        if (pair.getLeft() != null && !this.displayName.equals(pair.getLeft().getName()))
        {
            this.displayName = pair.getLeft().getName();
            this.litematicVersion = pair.getRight().litematicVersion();
            this.dataVersion = pair.getRight().minecraftDataVersion();
            SyncmaticaDebug.log(SyncmaticaDebug.Cat.DATA, "ServerPlacement#correctMetadataFromPeek(): Fix Metadata Name: [" + displayName + "] --> Dirty");
            return true;
        }
        return false;
    }
}
