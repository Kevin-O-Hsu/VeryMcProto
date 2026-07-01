package verymc.top.veryMcProto.mod.syncmatica.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 上传配额服务（移植自 {@code ch.endte.syncmatica.service.QuotaService}）。
 *
 * <p>限制每个玩家向服务端上传的 litematic 字节总量（仅 {@code DownloadExchange} 查询，{@code UploadExchange} 不查）。
 * {@code progress} Map 不持久化，重启清零。
 *
 * <p><b>Paper 适配</b>：{@code isOverQuota} / {@code progressQuota} 改接收 {@code String senderName}
 * （原版接收 {@code ExchangeTarget}，解耦通信层；调用方传 {@code target.getPersistentName()}）。
 */
public class QuotaService extends AbstractService {

    public static final Boolean IS_ENABLED_DEFAULT = false;
    public static final Integer QUOTA_LIMIT_DEFAULT = 40000000;

    final Map<String, Integer> progress = new HashMap<>();
    Boolean isEnabled = IS_ENABLED_DEFAULT;
    Integer limit = QUOTA_LIMIT_DEFAULT;

    public Boolean isOverQuota(final String senderName, final Integer newData) {
        if (!Boolean.TRUE.equals(isEnabled)) {
            return false;
        }
        int curValue = progress.getOrDefault(senderName, 0);
        curValue += newData;
        return curValue > limit;
    }

    public void progressQuota(final String senderName, final Integer newData) {
        if (Boolean.TRUE.equals(isEnabled)) {
            final int curValue = progress.getOrDefault(senderName, 0);
            progress.put(senderName, curValue + newData);
        }
    }

    @Override
    public void getDefaultConfiguration(final IServiceConfiguration configuration) {
        configuration.saveBoolean("enabled", IS_ENABLED_DEFAULT);
        configuration.saveInteger("limit", QUOTA_LIMIT_DEFAULT);
    }

    @Override
    public String getConfigKey() {
        return "quota";
    }

    @Override
    public void configure(final IServiceConfiguration configuration) {
        configuration.loadBoolean("enabled", b -> isEnabled = b);
        configuration.loadInteger("limit", i -> limit = i);
    }

    @Override
    public void saveConfiguration(final IServiceConfiguration configuration) {
        configuration.saveBoolean("enabled", isEnabled);
        configuration.saveInteger("limit", limit);
    }
}
