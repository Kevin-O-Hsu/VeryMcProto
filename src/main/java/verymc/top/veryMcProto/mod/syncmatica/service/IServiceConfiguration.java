package verymc.top.veryMcProto.mod.syncmatica.service;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 服务配置契约（移植自 {@code ch.endte.syncmatica.service.IServiceConfiguration}）。
 *
 * <p>回调式读取：loadXxx 接 Consumer，读到值才回调（读不到/出错则不回调，保留默认值）。
 */
public interface IServiceConfiguration {

    void loadBoolean(String key, Consumer<Boolean> loader);

    void saveBoolean(String key, Boolean value);

    void loadInteger(String key, IntConsumer loader);

    void saveInteger(String key, Integer value);
}
