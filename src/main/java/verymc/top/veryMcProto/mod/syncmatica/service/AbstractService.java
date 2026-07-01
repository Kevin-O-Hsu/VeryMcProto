package verymc.top.veryMcProto.mod.syncmatica.service;

/**
 * 服务基类（移植自 {@code ch.endte.syncmatica.service.AbstractService}）。
 *
 * <p><b>Paper 适配</b>：原版持有 {@code context} 字段 + setContext/getContext，此处全部移除（解耦 Context）；
 * 改为提供 {@link #startup()} / {@link #shutdown()} 的空默认，子类按需覆写。
 */
abstract class AbstractService implements IService {

    @Override
    public void startup()
    {
        // NOSONAR 默认空实现
    }

    @Override
    public void shutdown()
    {
        // NOSONAR 默认空实现
    }

    @Override
    public void saveConfiguration(final IServiceConfiguration configuration)
    {
        // NOSONAR 默认空实现：子类（Quota/Debug）覆写以导出运行时配置
    }
}
