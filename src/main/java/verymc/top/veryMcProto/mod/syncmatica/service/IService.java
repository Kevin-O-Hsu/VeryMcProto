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

    void startup();

    void shutdown();
}
