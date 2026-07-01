package verymc.top.veryMcProto.mod.jeirecipebridge;

import net.minecraft.resources.Identifier;

/**
 * JEI Recipe Bridge 模块常量（mod 层）。对应原版 {@code com.mrbysco.jeicompat} 的通道声明。
 *
 * <p>JEI Recipe Bridge 在玩家进服时把服务端配方表同步给 JEI 客户端，按客户端 brand 走 fabric / neoforge
 * 两条原版 custom payload 通道——注意<b>不是</b> {@code servux:} 通道、也<b>不走 plugin messaging 投递}，
 * 而是 NMS 直发 {@code ClientboundCustomPayloadPacket}（配方包通常远超 32KiB，必须绕开 Bukkit size 上限）。
 */
public final class JeiRecipeBridgeReference
{
    private JeiRecipeBridgeReference() { }

    public static final String MOD_ID = "jei_recipe_bridge";

    /** fabric 客户端配方同步通道（payload 由 {@code FabricRecipeSyncPayload} 编码）。 */
    public static final Identifier CHANNEL_FABRIC = Identifier.fromNamespaceAndPath("fabric", "recipe_sync");
    /** neoforge 客户端配方内容通道（payload 由 {@code NeoforgeRecipeSyncPayload} 编码）。 */
    public static final Identifier CHANNEL_NEOFORGE = Identifier.fromNamespaceAndPath("neoforge", "recipe_content");
}
