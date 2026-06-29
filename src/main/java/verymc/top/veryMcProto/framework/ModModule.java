package verymc.top.veryMcProto.framework;

import verymc.top.veryMcProto.framework.dataproviders.DataProviderManager;

/**
 * 协议 mod 模块抽象（框架层）。
 *
 * <p>每个被移植的 Fabric 协议 mod 实现一个 {@code ModModule}，在 {@link #onRegister} 中向框架
 * {@link DataProviderManager} 注册自己的全部 Provider。主类 onEnable 遍历各 {@code ModModule} 注册，
 * 复用框架网络 / 配置 / 事件 / 命令层。
 *
 * <p>首个实现：{@code verymc.top.veryMcProto.mod.servux.ServuxModule}。
 * 新增 Fabric 协议时，在 {@code mod/<newmod>/} 下实现本接口并注册，无需改动框架。
 */
public interface ModModule
{
    /** mod 标识（如 "servux"）。 */
    String getModId();

    /** 协议握手字段（如 {@code servux-paper-1.21.11-1.0.0}）。 */
    String getModString();

    /** 注册本 mod 的所有 Provider 到框架。 */
    void onRegister(DataProviderManager manager);
}
