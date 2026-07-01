package verymc.top.veryMcProto.mod.syncmatica.communication;

/**
 * MESSAGE 包的消息级别（移植自 {@code ch.endte.syncmatica.communication.MessageType}）。
 *
 * <p>用于 {@code PacketType.MESSAGE}（path={@code syncmatica:mesage}）的 body 第一字段。
 */
public enum MessageType {
    SUCCESS,
    INFO,
    WARNING,
    ERROR
}
