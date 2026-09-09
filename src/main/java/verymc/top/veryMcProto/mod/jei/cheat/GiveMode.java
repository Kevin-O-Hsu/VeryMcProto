package verymc.top.veryMcProto.mod.jei.cheat;

/**
 * cheat 给物品模式（mod 层）。逐字镜像上游 {@code mezz.jei.common.config.GiveMode}。
 * 线序 = {@code FriendlyByteBuf.readEnum/writeEnum}（VAR_INT 序号，越界抛 {@code DecoderException}——严格解码）。
 */
public enum GiveMode
{
    INVENTORY, MOUSE_PICKUP;

    /** 上游 {@code GiveMode.defaultGiveMode}（客户端默认；服务端仅按包内字段执行，不消费此值）。 */
    public static final GiveMode defaultGiveMode = MOUSE_PICKUP;
}
