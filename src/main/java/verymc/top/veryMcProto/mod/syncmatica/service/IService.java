package verymc.top.veryMcProto.mod.syncmatica.service;

/**
 * 服务契约（移植自 {@code ch.endte.syncmatica.service.IService}）。
 *
 * <p><b>Paper 适配</b>：去掉 {@code setContext(Context)} / {@code getContext()}（原版反向注入 Context）——
 * QuotaService/DebugService 实际不引用 Context，去掉以解耦使服务层独立编译。
 */
public interface IService {

    void getDefaultConfiguration(IServiceConfiguration configuration);

    String getConfigKey();

    void configure(IServiceConfiguration configuration);

    /**
     * 导出当前运行时配置值到给定 configuration（与 {@link #configure} 读方向对称的写方向）。
     *
     * <p>用于 {@code /syncmatica save}：把 service 运行时状态写回磁盘。
     * {@link AbstractService} 提供空默认，子类（如 Quota/Debug）按需覆写。
     */
    void saveConfiguration(IServiceConfiguration configuration);

    void startup();

    void shutdown();
}
