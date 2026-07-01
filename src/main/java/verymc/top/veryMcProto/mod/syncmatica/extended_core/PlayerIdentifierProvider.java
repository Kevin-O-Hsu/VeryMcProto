package verymc.top.veryMcProto.mod.syncmatica.extended_core;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家标识归一化提供者（移植自 {@code ch.endte.syncmatica.extended_core.PlayerIdentifierProvider}）。
 *
 * <p>内存级 {@code Map<UUID, PlayerIdentifier>}（不持久化；随 placement JSON 落盘 uuid+name，重启重建）。
 * 保证同 uuid 返回同一实例（{@link PlayerIdentifier} 用对象身份相等，{@code owner==lastModifiedBy} 判定依赖此归一化）。
 *
 * <p><b>Paper 适配</b>（解耦 Context，使数据层可独立编译）：
 * <ul>
 *   <li>去掉 {@code context} 字段（Provider 不需要 Context 引用）；</li>
 *   <li>去掉 {@code createOrGet(ExchangeTarget)} 重载（依赖 ServerCommunicationManager；服务端改走 {@link #createOrGet(GameProfile)} 重载）；</li>
 *   <li>Paper 恒 server，{@link #createOrGet(UUID, String)} 不主动 updateName（原版 {@code if(!isServer())} 客户端分支删除）。</li>
 * </ul>
 */
public class PlayerIdentifierProvider {
    private final Map<UUID, PlayerIdentifier> identifiers = new HashMap<>();

    public PlayerIdentifierProvider() {
        identifiers.put(PlayerIdentifier.MISSING_PLAYER_UUID, PlayerIdentifier.MISSING_PLAYER);
    }

    public PlayerIdentifier createOrGet(final GameProfile gameProfile) {
        return createOrGet(gameProfile.id(), gameProfile.name());
    }

    public PlayerIdentifier createOrGet(final UUID uuid, final String playerName) {
        return identifiers.computeIfAbsent(uuid, id -> new PlayerIdentifier(uuid, playerName));
    }

    public void updateName(final UUID uuid, final String playerName) {
        createOrGet(uuid, playerName).updatePlayerName(playerName);
    }

    public PlayerIdentifier fromJson(final JsonObject obj) {
        if (obj == null || !obj.has("uuid") || !obj.has("name")) {
            return PlayerIdentifier.MISSING_PLAYER;
        }

        final UUID jsonUUID = UUID.fromString(obj.get("uuid").getAsString());

        return identifiers.computeIfAbsent(jsonUUID,
                key -> new PlayerIdentifier(jsonUUID, obj.get("name").getAsString())
        );
    }
}
