package verymc.top.veryMcProto.framework.dataproviders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.debug.Debug;
import verymc.top.veryMcProto.framework.settings.IServuxSetting;
import verymc.top.veryMcProto.framework.util.JsonUtils;

/**
 * Provider 注册表 / 调度器 / 配置中枢（框架层）。移植自原版
 * {@code fi.dy.masa.servux.dataproviders.DataProviderManager}（单例 {@link #INSTANCE}）。
 *
 * <p><b>适配</b>（相对原版）：
 * <ul>
 *   <li>Guava {@code ImmutableList} → {@link List#copyOf} / {@link List#of}；</li>
 *   <li>配置目录由 {@link #setConfigDir} 注入（{@code plugin.getDataFolder()}），原版用 Fabric config dir；</li>
 *   <li>{@code RegistryAccess.Frozen} 初始 null，由 {@link #onCaptureImmutable} 在 server started 后填充。</li>
 * </ul>
 */
public class DataProviderManager
{
    public static final DataProviderManager INSTANCE = new DataProviderManager();

    /** 逻辑名 → provider。 */
    protected final HashMap<String, IDataProvider> providers = new HashMap<>();
    protected List<IDataProvider> providersImmutable = List.of();
    protected ArrayList<IDataProvider> providersTicking = new ArrayList<>();

    @Nullable
    protected Path configDir = null;

    /** server started 后捕获的注册表（给 Litematic palette 解析方块用）。 */
    protected RegistryAccess.Frozen immutable = null;

    private DataProviderManager() { }

    public List<IDataProvider> getAllProviders()
    {
        return this.providersImmutable;
    }

    /** 由主类 onEnable 注入配置目录（{@code plugin.getDataFolder().toPath()}）。 */
    public void setConfigDir(Path configDir)
    {
        this.configDir = configDir;
    }

    /** 注册 provider（按逻辑名 lowercase 去重）。 */
    public boolean registerDataProvider(IDataProvider provider)
    {
        String name = provider.getName().toLowerCase();

        if (this.providers.containsKey(name) == false)
        {
            this.providers.put(name, provider);
            this.providersImmutable = List.copyOf(this.providers.values());

            if (Debug.isOn(Debug.Cat.PROVIDER))
            {
                Debug.log(Debug.Cat.PROVIDER, "registerDataProvider: " + provider.getName()
                        + " channel=" + provider.getNetworkChannel() + " protoVer=" + provider.getProtocolVersion());
            }

            return true;
        }

        return false;
    }

    public boolean setProviderEnabled(String providerName, boolean enabled)
    {
        IDataProvider provider = this.providers.get(providerName);
        return provider != null && this.setProviderEnabled(provider, enabled);
    }

    public boolean setProviderEnabled(IDataProvider provider, boolean enabled)
    {
        boolean wasEnabled = provider.isEnabled();

        if (Debug.isOn(Debug.Cat.PROVIDER))
        {
            Debug.log(Debug.Cat.PROVIDER, "setProviderEnabled: " + provider.getName()
                    + " was=" + wasEnabled + " → now=" + enabled);
        }

        if (enabled || wasEnabled != enabled)
        {
            provider.setEnabled(enabled);
            this.updatePacketHandlerRegistration(provider);

            if (enabled && provider.shouldTick() && this.providersTicking.contains(provider) == false)
            {
                this.providersTicking.add(provider);
                Debug.log(Debug.Cat.PROVIDER, "  → 加入 ticking 列表，interval=" + provider.getTickInterval());
            }
            else
            {
                this.providersTicking.remove(provider);
            }

            return true;
        }

        return false;
    }

    public void tickProviders(MinecraftServer server, int tickCounter)
    {
        if (this.providersTicking.isEmpty() == false)
        {
            for (IDataProvider provider : this.providersTicking)
            {
                if ((tickCounter % provider.getTickInterval()) == 0)
                {
                    Debug.log(Debug.Cat.TICK, "tick[" + provider.getName() + "] @tick=" + tickCounter
                            + " interval=" + provider.getTickInterval());
                    try
                    {
                        provider.tick(server, tickCounter);
                    }
                    catch (Exception e)
                    {
                        Reference.logger().warning("tick[" + provider.getName() + "] 异常: " + e.getMessage());
                    }
                }
            }
        }
    }

    protected void registerEnabledPacketHandlers()
    {
        for (IDataProvider provider : this.providersImmutable)
        {
            this.updatePacketHandlerRegistration(provider);
        }
    }

    protected void updatePacketHandlerRegistration(IDataProvider provider)
    {
        if (provider.isEnabled())
        {
            Debug.log(Debug.Cat.PROVIDER, "updatePacketHandlerRegistration: " + provider.getName() + " → registerHandler()");
            provider.registerHandler();
        }
        else
        {
            Debug.log(Debug.Cat.PROVIDER, "updatePacketHandlerRegistration: " + provider.getName() + " → unregisterHandler() + setRegistered(false)");
            provider.unregisterHandler();
            provider.setRegistered(false); // 与 registerHandler 内 setRegistered(true) 对称，消除 provider 级标志撒谎
        }
    }

    public void onCaptureImmutable(@Nonnull RegistryAccess.Frozen immutable)
    {
        this.immutable = immutable;
    }

    /** 获取捕获的注册表（server started 后非 null）。 */
    @Nullable
    public RegistryAccess.Frozen getRegistryManager()
    {
        return this.immutable;
    }

    public void onServerTickEndPre()
    {
        for (IDataProvider provider : this.providersImmutable)
        {
            provider.onTickEndPre();
        }
    }

    public void onServerTickEndPost()
    {
        for (IDataProvider provider : this.providersImmutable)
        {
            provider.onTickEndPost();
        }
    }

    public Optional<IDataProvider> getProviderByName(String providerName)
    {
        return Optional.ofNullable(this.providers.get(providerName));
    }

    @Nullable
    public IServuxSetting<?> getSettingByName(String name)
    {
        if (name.contains(":"))
        {
            String[] parts = name.split(":");
            if (parts.length < 2)
            {
                return null;
            }
            String providerName = parts[0];
            String settingName = parts[1];
            IDataProvider provider = this.providers.get(providerName);

            if (provider != null)
            {
                for (IServuxSetting<?> setting : provider.getSettings())
                {
                    if (setting.name().equalsIgnoreCase(settingName))
                    {
                        return setting;
                    }
                }
            }
        }
        else
        {
            for (IDataProvider provider : this.providersImmutable)
            {
                for (IServuxSetting<?> setting : provider.getSettings())
                {
                    if (setting.name().equalsIgnoreCase(name))
                    {
                        return setting;
                    }
                }
            }
        }
        return null;
    }

    public void readFromConfig()
    {
        JsonElement el = JsonUtils.parseJsonFileAsPath(this.getConfigFile());
        JsonObject obj = null;

        Debug.log(Debug.Cat.CONFIG, "DataProviderManager#readFromConfig() file=" + this.getConfigFile());

        if (el != null && el.isJsonObject())
        {
            JsonObject root = el.getAsJsonObject();

            if (JsonUtils.hasObject(root, "DataProviderToggles"))
            {
                obj = JsonUtils.getNestedObject(root, "DataProviderToggles", false);
            }

            for (IDataProvider provider : this.providersImmutable)
            {
                String name = provider.getName();

                if (JsonUtils.hasObject(root, name))
                {
                    provider.fromJson(JsonUtils.getNestedObject(root, name, false));
                }
            }

            // 读配置后按开关启停
            for (IDataProvider provider : this.providersImmutable)
            {
                if (obj != null)
                {
                    this.setProviderEnabled(provider, JsonUtils.getBooleanOrDefault(obj, provider.getName(), false));
                }
                else
                {
                    this.setProviderEnabled(provider, false);
                }

                // servux_main 永不被禁用（提供配置管理）
                if (provider.getName().equals("servux_main") && !provider.isEnabled())
                {
                    this.setProviderEnabled(provider, true);
                }
            }
        }
        else
        {
            Debug.log(Debug.Cat.CONFIG, "readFromConfig: 配置文件不存在，首次启动默认全启用");
            // 首次无 config：全启用（除 debug_data，本版无）
            for (IDataProvider provider : this.providersImmutable)
            {
                this.setProviderEnabled(provider, !provider.getName().equals("debug_data"));
            }
        }

        // 配置加载完成：通知各 provider 做后处理（如 ConfigProvider 同步框架 Debug 宏开关）
        for (IDataProvider provider : this.providersImmutable)
        {
            try { provider.onConfigLoaded(); }
            catch (Exception ex) { Reference.logger().warning("onConfigLoaded[" + provider.getName() + "] 异常: " + ex.getMessage()); }
        }

        // 汇总各 provider 启停结果（一次打印，便于核对 enabled/registered/tick 三态）
        if (Debug.isOn(Debug.Cat.CONFIG))
        {
            StringBuilder sb = new StringBuilder("readFromConfig 完成 → providers:");
            for (IDataProvider provider : this.providersImmutable)
            {
                sb.append("\n  - ").append(provider.getName())
                  .append(" [").append(provider.isEnabled() ? "ON" : "OFF").append("]")
                  .append(" reg=").append(provider.isRegistered())
                  .append(" shouldTick=").append(provider.shouldTick())
                  .append(" ch=").append(provider.getNetworkChannel());
            }
            Debug.log(Debug.Cat.CONFIG, sb.toString());
        }
    }

    public void writeToConfig()
    {
        JsonObject root = new JsonObject();
        JsonObject objToggles = new JsonObject();

        Debug.log(Debug.Cat.CONFIG, "DataProviderManager#writeToConfig() → " + this.getConfigFile());

        for (IDataProvider provider : this.providersImmutable)
        {
            String name = provider.getName();
            objToggles.add(name, new JsonPrimitive(provider.isEnabled()));
        }

        root.add("DataProviderToggles", objToggles);

        for (IDataProvider provider : this.providersImmutable)
        {
            String name = provider.getName();
            root.add(name, provider.toJson());
        }

        JsonUtils.writeJsonToFileAsPath(root, this.getConfigFile());
    }

    protected Path getConfigFile()
    {
        if (this.configDir == null)
        {
            this.configDir = Reference.plugin() != null
                    ? Reference.plugin().getDataFolder().toPath()
                    : Path.of("plugins", "VeryMcProto");
        }

        if (!Files.exists(this.configDir))
        {
            try
            {
                Files.createDirectory(this.configDir);
            }
            catch (Exception err)
            {
                Reference.logger().warning("getConfigFile: 创建配置目录失败 '" + this.configDir.toAbsolutePath() + "': " + err.getMessage());
            }
        }

        return this.configDir.resolve("servux.json");
    }
}
