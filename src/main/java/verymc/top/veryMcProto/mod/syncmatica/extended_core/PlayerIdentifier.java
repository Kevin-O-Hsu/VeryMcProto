package verymc.top.veryMcProto.mod.syncmatica.extended_core;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.UUID;

/**
 * 玩家标识（移植自 {@code ch.endte.syncmatica.extended_core.PlayerIdentifier}）。
 *
 * <p>CORE_EX feature：placement 的 owner / lastModifiedBy 字段。
 * 用对象身份相等（无 equals/hashCode）——{@code owner.equals(lastModifiedBy)} 仅当同一实例才 true，
 * 故 {@code PlayerIdentifierProvider.createOrGet} 的归一化是关键（保证同 uuid 返回同实例）。
 *
 * <p>构造器包级：仅 {@link verymc.top.veryMcProto.mod.syncmatica.extended_core.PlayerIdentifierProvider}
 * （同包）可创建实例。
 */
public class PlayerIdentifier {
    public static final UUID MISSING_PLAYER_UUID = UUID.fromString("4c1b738f-56fa-4011-8273-498c972424ea");
    public static final PlayerIdentifier MISSING_PLAYER = new PlayerIdentifier(MISSING_PLAYER_UUID, "No Player");

    public final UUID uuid;
    private String bufferedPlayerName;

    PlayerIdentifier(final UUID uuid, final String bufferedPlayerName) {
        this.uuid = uuid;
        this.bufferedPlayerName = bufferedPlayerName;
    }

    public String getName() {
        return bufferedPlayerName;
    }

    public void updatePlayerName(final String name) {
        bufferedPlayerName = name;
    }

    public JsonObject toJson() {
        final JsonObject jsonObject = new JsonObject();

        jsonObject.add("uuid", new JsonPrimitive(uuid.toString()));
        jsonObject.add("name", new JsonPrimitive(bufferedPlayerName));

        return jsonObject;
    }
}
