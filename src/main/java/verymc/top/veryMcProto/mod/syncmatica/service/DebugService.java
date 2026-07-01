package verymc.top.veryMcProto.mod.syncmatica.service;

import verymc.top.veryMcProto.mod.syncmatica.network.PacketType;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaLog;

/**
 * 调试日志服务（移植自 {@code ch.endte.syncmatica.service.DebugService}）。
 *
 * <p>记录收发包日志（{@code CommunicationManager.onPacket} 调 {@link #logReceivePacket}；
 * {@code ExchangeTarget.sendPacket} 调 {@link #logSendPacket}）。
 *
 * <p><b>Paper 适配 / 原版 bug 修正</b>（docs/22 §8.2）：
 * <ul>
 *   <li>原版字段 {@code doPacketLogging} 默认 {@code true}，但配置默认值 {@code false}，不一致——此处统一 {@code false}
 *       （生产环境不应默认开 INFO 级包日志）；</li>
 *   <li>原版配置 key {@code "doPackageLogging"}（Package，拼写错误）——此处统一 {@code "doPacketLogging"}（Packet）；</li>
 *   <li>{@code Syncmatica.LOGGER}（log4j）→ {@link SyncmaticaLog}（JUL shim）。</li>
 * </ul>
 */
public class DebugService extends AbstractService
{
    // Paper 修正：统一默认 false（原版字段 true 与配置默认 false 不一致）
    private boolean doPacketLogging = false;

    public void logReceivePacket(final PacketType packetType)
    {
        if (doPacketLogging)
        {
            SyncmaticaLog.info("Syncmatica - received packet:[type={}]", packetType);
        }
    }

    public void logSendPacket(final PacketType packetType, final String targetIdentifier)
    {
        if (doPacketLogging)
        {
            SyncmaticaLog.info(
                    "Sending packet[type={}] to ExchangeTarget[id={}]",
                    packetType,
                    targetIdentifier
            );
        }
    }

    @Override
    public void getDefaultConfiguration(final IServiceConfiguration configuration)
    {
        // Paper 修正：原版 key "doPackageLogging"（拼写错误）→ 统一 "doPacketLogging"
        configuration.saveBoolean("doPacketLogging", false);
    }

    @Override
    public String getConfigKey()
    {
        return "debug";
    }

    @Override
    public void configure(final IServiceConfiguration configuration)
    {
        configuration.loadBoolean("doPacketLogging", b -> doPacketLogging = b);
    }
}
