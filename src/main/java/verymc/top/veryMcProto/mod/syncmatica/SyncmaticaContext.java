package verymc.top.veryMcProto.mod.syncmatica;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.bukkit.plugin.Plugin;

import verymc.top.veryMcProto.mod.syncmatica.communication.CommunicationManager;
import verymc.top.veryMcProto.mod.syncmatica.communication.FeatureSet;
import verymc.top.veryMcProto.mod.syncmatica.communication.ServerCommunicationManager;
import verymc.top.veryMcProto.mod.syncmatica.data.FileStorage;
import verymc.top.veryMcProto.mod.syncmatica.data.IFileStorage;
import verymc.top.veryMcProto.mod.syncmatica.data.SyncmaticManager;
import verymc.top.veryMcProto.mod.syncmatica.extended_core.PlayerIdentifierProvider;
import verymc.top.veryMcProto.mod.syncmatica.service.DebugService;
import verymc.top.veryMcProto.mod.syncmatica.service.IService;
import verymc.top.veryMcProto.mod.syncmatica.service.JsonConfiguration;
import verymc.top.veryMcProto.mod.syncmatica.service.QuotaService;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaDebug;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * 领域根容器（移植自 {@code ch.endte.syncmatica.Context}）。
 *
 * <p>聚合 {@link IFileStorage} / {@link CommunicationManager} / {@link SyncmaticManager} /
 * {@link QuotaService} / {@link DebugService} / {@link PlayerIdentifierProvider}，管理配置与生命周期。
 *
 * <p><b>Paper 适配</b>：
 * <ul>
 *   <li>去掉 {@code Reference.isClient()/isIntegratedServer()/isOpenToLan()} 分支（恒 dedicated server）；</li>
 *   <li>去掉 {@code registerReceivers()}（通道注册移到 P9 {@code SyncmaticaApp} 的 {@code onEnable}）；</li>
 *   <li>{@code Syncmatica.LOGGER} → {@link SyncmaticaLog}；</li>
 *   <li>FileStorage 不再 {@code setContext}，改为 {@code setDownloadStateProvider}（函数式注入 comMan.getDownloadState）；</li>
 *   <li>IService（Quota/Debug）不再 {@code setContext}（P3 解耦）。</li>
 * </ul>
 */
public class SyncmaticaContext
{
    private final Plugin plugin;
    private final IFileStorage files;
    private final CommunicationManager comMan;
    private final SyncmaticManager synMan;
    private FeatureSet fs = null;
    private final QuotaService quota;
    private final DebugService debugService;
    private final PlayerIdentifierProvider playerIdentifierProvider;
    private final Path litematicFolder;
    private final Path configFolder;
    private boolean isStarted = false;
    /** 协议软禁用标志（{@code /syncmatica enable|disable}）。true=正常处理包；false=handler 吞包、不握手。不持久化（重启恢复 true）。 */
    private volatile boolean protocolEnabled = true;

    public SyncmaticaContext(final Plugin plugin,
                             final IFileStorage files,
                             final CommunicationManager comMan,
                             final SyncmaticManager synMan,
                             final Path litematicFolder,
                             final Path configFolder)
    {
        this.plugin = plugin;
        this.files = files;
        this.comMan = comMan;
        this.synMan = synMan;
        this.litematicFolder = litematicFolder;
        this.configFolder = configFolder;

        // 反向注入（comMan/synMan 需要 context 引用；FileStorage/IService 已解耦，不 setContext）
        comMan.setContext(this);
        synMan.setContext(this);

        quota = new QuotaService();
        playerIdentifierProvider = new PlayerIdentifierProvider();
        debugService = new DebugService();

        // FileStorage 的 downloadState 查询由 comMan 提供（替代原版 context.getCommunicationManager().getDownloadState）
        if (files instanceof FileStorage)
        {
            ((FileStorage) files).setDownloadStateProvider(placement -> comMan.getDownloadState(placement));
        }

        if (!Files.exists(litematicFolder))
        {
            try
            {
                Files.createDirectories(litematicFolder);
            }
            catch (Exception e)
            {
                throw new RuntimeException("Context(): Fatal error creating litematic Folder. Exception: " + e.getLocalizedMessage(), e);
            }
        }

        loadConfiguration();
    }

    public Plugin getPlugin() { return plugin; }

    public IFileStorage getFileStorage() { return files; }

    public CommunicationManager getCommunicationManager() { return comMan; }

    public SyncmaticManager getSyncmaticManager() { return synMan; }

    public QuotaService getQuotaService() { return quota; }

    public DebugService getDebugService() { return debugService; }

    public PlayerIdentifierProvider getPlayerIdentifierProvider() { return playerIdentifierProvider; }

    public FeatureSet getFeatureSet()
    {
        if (fs == null)
        {
            generateFeatureSet();
        }
        return fs;
    }

    // Paper 恒 dedicated server
    public boolean isServer() { return true; }

    public boolean isClient() { return false; }

    public boolean isIntegratedServer() { return false; }

    public boolean isStarted() { return isStarted; }

    public boolean isProtocolEnabled() { return protocolEnabled; }

    public void setProtocolEnabled(boolean enabled) { this.protocolEnabled = enabled; }

    /** 软禁用：停止处理 incoming 包 + 关闭进行中 exchange + 清空已握手集合。通道仍注册（避免 Paper 踢人）。 */
    public void suspendProtocol()
    {
        this.protocolEnabled = false;
        if (comMan instanceof ServerCommunicationManager scm)
        {
            scm.suspendAll();
        }
        SyncmaticaLog.info("Syncmatica 协议已禁用（软禁用：通道保留、handler 吞包、玩家不会被踢）");
    }

    /** 恢复：重新接受 incoming 包。在线玩家的重新握手由 SyncmaticaModule.reconnectOnlinePlayers 负责。 */
    public void resumeProtocol()
    {
        this.protocolEnabled = true;
        SyncmaticaLog.info("Syncmatica 协议已启用");
    }

    public Path getLitematicFolder() { return litematicFolder; }

    private void generateFeatureSet()
    {
        // 声明全集 FeatureSet（配合 MOD_VERSION="1.0.0" 触发 FEATURE 交换，使双方用全集编码）
        fs = new FeatureSet(Arrays.asList(Feature.values()));
    }

    public void startup()
    {
        quota.startup();
        debugService.startup();
        isStarted = true;
        synMan.startup();
    }

    public void shutdown()
    {
        saveConfiguration();
        quota.shutdown();
        debugService.shutdown();
        isStarted = false;
        synMan.shutdown();
    }

    public boolean checkPartnerVersion(final String version)
    {
        return !version.equals("0.0.1");
    }

    public Path getConfigFolder()
    {
        return configFolder;
    }

    public Path getConfigFile()
    {
        return configFolder.resolve(SyncmaticaReference.CONFIG_FILE_NAME);
    }

    public Path getAndCreateConfigFile() throws IOException
    {
        if (!Files.exists(configFolder))
        {
            Files.createDirectories(configFolder);
        }
        Path configFile = getConfigFile();
        if (!Files.exists(configFile))
        {
            Files.createFile(configFile);
        }
        return configFile;
    }

    public void loadConfiguration()
    {
        boolean attemptToLoad = false;
        JsonObject configuration;
        Path f = getConfigFile();

        try
        {
            configuration = new Gson().fromJson(new BufferedReader(new FileReader(f.toFile())), JsonObject.class);
            attemptToLoad = true;
        }
        catch (final Exception ignored)
        {
            configuration = new JsonObject();
        }
        boolean needsRewrite = false;
        // Paper 恒 server：quota + debug 都装配（原版 quota 仅 server 分支）
        needsRewrite = loadConfigurationForService(quota, configuration, attemptToLoad);
        needsRewrite |= loadConfigurationForService(debugService, configuration, attemptToLoad);
        if (needsRewrite)
        {
            try (final Writer writer = new BufferedWriter(new FileWriter(getAndCreateConfigFile().toFile())))
            {
                final Gson gson = new GsonBuilder().setPrettyPrinting().create();
                writer.write(gson.toJson(configuration));
            }
            catch (final Exception e)
            {
                SyncmaticaLog.error("loadConfiguration(): Exception loading config file '{}'; {}", f.getFileName(), e.getLocalizedMessage());
            }
        }

        // 恢复 SyncmaticaDebug 状态（master + 分类）——顶层 "debugLog" 子对象。缺 key 则保持默认 false/空（默认关）。
        if (attemptToLoad && configuration.has("debugLog"))
        {
            try
            {
                SyncmaticaDebug.SYS.restore(configuration.getAsJsonObject("debugLog"));
            }
            catch (final Exception e)
            {
                SyncmaticaLog.error("loadConfiguration(): restore debugLog failed; {}", e.getLocalizedMessage());
            }
        }
    }

    /**
     * 保存配置到磁盘：重读现有 config（保留 quota/debug service 段不被覆盖），写入 SyncmaticaDebug 快照（"debugLog"），落盘。
     * 由命令切换与 {@link #shutdown()} 调用。文件不存在/损坏则从空对象重建。
     */
    public void saveConfiguration()
    {
        JsonObject configuration;
        try
        {
            configuration = new Gson().fromJson(new BufferedReader(new FileReader(getConfigFile().toFile())), JsonObject.class);
            if (configuration == null)
            {
                configuration = new JsonObject();
            }
        }
        catch (final Exception ignored)
        {
            configuration = new JsonObject();
        }
        // 各 service 导出运行时配置到自己的子对象
        saveConfigurationForService(quota, configuration);
        saveConfigurationForService(debugService, configuration);
        // SyncmaticaDebug 状态（master + 分类）
        configuration.add("debugLog", SyncmaticaDebug.SYS.snapshot());
        try (final Writer writer = new BufferedWriter(new FileWriter(getAndCreateConfigFile().toFile())))
        {
            final Gson gson = new GsonBuilder().setPrettyPrinting().create();
            writer.write(gson.toJson(configuration));
        }
        catch (final Exception e)
        {
            SyncmaticaLog.error("saveConfiguration(): Exception saving config file; {}", e.getLocalizedMessage());
        }
    }

    /**
     * 把单个 service 的运行时配置导出到 root 的 configKey 子对象（与 {@link #loadConfigurationForService} 读方向对称）。
     */
    private void saveConfigurationForService(final IService service, final JsonObject root)
    {
        final String configKey = service.getConfigKey();
        final JsonObject serviceJson = new JsonObject();
        service.saveConfiguration(new JsonConfiguration(serviceJson));
        root.add(configKey, serviceJson);
    }

    private Boolean loadConfigurationForService(final IService service, final JsonObject configuration, final boolean attemptToLoad)
    {
        final String configKey = service.getConfigKey();
        JsonObject serviceJson = null;
        JsonConfiguration serviceConfiguration = null;
        boolean started = false;

        if (attemptToLoad && configuration.has(configKey))
        {
            try
            {
                serviceJson = configuration.getAsJsonObject(configKey);
                if (serviceJson != null)
                {
                    serviceConfiguration = new JsonConfiguration(serviceJson);
                    service.configure(serviceConfiguration);
                    started = true;
                    if (!serviceConfiguration.hadError())
                    {
                        return false;
                    }
                }
            }
            catch (final Exception e)
            {
                SyncmaticaLog.error("loadConfigurationForService(): Exception loading service config; {}", e.getLocalizedMessage());
            }
        }
        if (serviceJson == null)
        {
            serviceJson = new JsonObject();
            configuration.add(configKey, serviceJson);
        }
        if (serviceConfiguration == null)
        {
            serviceConfiguration = new JsonConfiguration(serviceJson);
        }
        service.getDefaultConfiguration(serviceConfiguration);
        if (!started)
        {
            service.configure(serviceConfiguration);
        }
        return true;
    }

    public static class DuplicateContextAssignmentException extends RuntimeException
    {
        private static final long serialVersionUID = -514754466116075630L;

        public DuplicateContextAssignmentException(final String reason)
        {
            super(reason);
        }
    }

    public static class ContextMismatchException extends RuntimeException
    {
        private static final long serialVersionUID = 2769376183212635479L;

        public ContextMismatchException(final String reason)
        {
            super(reason);
        }
    }
}
