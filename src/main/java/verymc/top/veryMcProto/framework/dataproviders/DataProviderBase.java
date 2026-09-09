package verymc.top.veryMcProto.framework.dataproviders;

import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

import verymc.top.veryMcProto.framework.settings.IServuxSetting;

/**
 * Provider 抽象基类（框架层）。移植自原版 {@code fi.dy.masa.servux.dataproviders.DataProviderBase}。
 *
 * <p>构造时固化元信息（name / channel / protocolVersion / permNode / description）；
 * 提供 enabled / playRegistered / tickRate 字段 + 通用的 toJson / fromJson（遍历 getSettings）。
 */
public abstract class DataProviderBase implements IDataProvider
{
    protected final Identifier networkChannel;
    protected final String name;
    protected final String permNode;
    protected final String description;
    protected final int protocolVersion;
    protected final int defaultPerm;
    protected boolean enabled;
    protected boolean playRegistered;
    private int tickRate = 40;

    protected DataProviderBase(String name, Identifier channel, int protocolVersion, int defaultPerm, String permNode, String description)
    {
        this.name = name;
        this.networkChannel = channel;
        this.protocolVersion = protocolVersion;
        this.defaultPerm = defaultPerm > -1 && defaultPerm < 5 ? defaultPerm : 0;
        this.permNode = permNode;
        this.description = description;
    }

    @Override
    public String getName()
    {
        return this.name;
    }

    @Override
    public String getDescription()
    {
        return this.description;
    }

    @Override
    public Identifier getNetworkChannel()
    {
        return this.networkChannel;
    }

    @Override
    public int getProtocolVersion()
    {
        return this.protocolVersion;
    }

    /**
     * C2S 注册版本门禁判定。上游各 DataProvider 的 {@code register()} 门禁语句提炼
     * （{@code tags == null || tags.getIntOrDefault("version", -1) < getProtocolVersion()}，
     * 上游 HudDataProvider:411，五 Provider 同构）。
     *
     * <p>26.1 NMS {@link CompoundTag} 取值 API 为 {@code getIntOr(key, def)}（对应 malilib
     * {@code getIntOrDefault}）。纯函数，供单测（DataProviderVersionGateTest）。
     *
     * @return true = 客户端协议版本过低（或 tags/version 缺失），须拒绝注册
     */
    public static boolean isVersionTooLow(CompoundTag tags, int requiredVersion)
    {
        return tags == null || tags.getIntOr("version", -1) < requiredVersion;
    }

    @Override
    public boolean isEnabled()
    {
        return this.enabled;
    }

    @Override
    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    @Override
    public boolean isRegistered()
    {
        return this.playRegistered;
    }

    @Override
    public void setRegistered(boolean toggle)
    {
        this.playRegistered = toggle;
    }

    protected void setTickRate(int tickRate)
    {
        this.tickRate = Math.max(tickRate, 1);
    }

    @Override
    public int getTickInterval()
    {
        return this.tickRate;
    }

    @Override
    public List<IServuxSetting<?>> getSettings()
    {
        return List.of();
    }

    @Override
    public JsonObject toJson()
    {
        JsonObject object = new JsonObject();

        for (IServuxSetting<?> setting : getSettings())
        {
            object.add(setting.name(), setting.writeToJson());
        }

        return object;
    }

    @Override
    public void fromJson(JsonObject obj)
    {
        for (IServuxSetting<?> setting : getSettings())
        {
            JsonElement element = obj.get(setting.name());
            if (element != null)
            {
                setting.readFromJson(element);
            }
        }
    }
}
