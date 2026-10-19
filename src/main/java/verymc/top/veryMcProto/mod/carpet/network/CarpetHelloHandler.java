package verymc.top.veryMcProto.mod.carpet.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.nms.Nms;
import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.mod.carpet.CarpetReference;
import verymc.top.veryMcProto.mod.carpet.CarpetSettings;
import verymc.top.veryMcProto.mod.carpet.app.CarpetModule;
import verymc.top.veryMcProto.mod.carpet.settings.ParsedRule;
import verymc.top.veryMcProto.mod.carpet.settings.SettingsManager;

/**
 * carpet:hello 协议处理器（mod 层，Phase 3）——上游 {@code carpet/network/ServerNetworkHandler.java}
 * （26.1 树）的 Paper 移植，通道载体 = 单 CompoundTag（vanilla NBT，非 DataTag——上游
 * {@code CarpetClient.CarpetPayload} 即 {@code writeNbt/readNbt}）。
 *
 * <p>消息面（wire 全部逐字对齐上游，键名 = CompoundTag 字符串键）：
 * <ul>
 *   <li>S2C {@code HI}（键 "69"）：进服无条件发（含假人——假连接 send no-op 吸收，上游同构），
 *       版本串 = {@link CarpetReference#CARPET_VERSION}（上游 mod_version 逐字，禁发插件版本号）；</li>
 *   <li>C2S {@code hello}（键 "420"）：应答入名册 + 服务端 info/warn（版本 equals 严格比较，上游镜像）→
 *       回全量 {@code Rules} 帧；</li>
 *   <li>S2C {@code Rules}（键 "Rules"）：每规则 {@code {Value, Manager:"carpet", Rule}}，撞名 key 追加
 *       "2"（上游 :229-232）；全量口径含默认值规则；</li>
 *   <li>C2S/S2C {@code clientCommand}：命令执行 + 回执 {@code {id, error?, return, output?}}；</li>
 *   <li>自定义 NBT 广播机制面：{@link #broadcastCustomCommand}/{@link #sendCustomCommand}（上游唯一
 *       调用者 scShapes 随 Scarpet 不移植，B 侧无调用者——机制面交付）。</li>
 * </ul>
 *
 * <p><b>superSecretSetting gate 封闭清单（勿外溢）</b>：上游仅三处抑制——规则变更推送
 * （{@code updateRuleWithConnectedClients :134-137}）、broadcastCustomCommand（:146-149）、
 * isValidCarpetPlayer（:181-184）；<b>onHello 的全量 Rules 下发无 gate（上游 :52-67）</b>。
 *
 * <p><b>名册</b>：单表 {@code Map<UUID, String>}（UUID→客户端 carpet 版本）。上游 valid/remote 双集的
 * 差异仅在单机集成服（memory connection）场景，Paper 专用服恒等价；UUID 键对 respawn 实例重建免疫
 * （上游以 ServerPlayer 实例为键）。hello 写入、quit 清除、shutdown 清空。
 */
public final class CarpetHelloHandler implements IPluginServerPlayHandler, Listener
{
    /** clientCommand 键（上游字面量，ServerNetworkHandler.java:35）。 */
    private static final String KEY_CLIENT_COMMAND = "clientCommand";
    /** Rules 帧外层键（上游 DataBuilder.java:225）。 */
    private static final String KEY_RULES = "Rules";

    private final Map<UUID, String> remoteCarpetClients = new ConcurrentHashMap<>();
    private volatile boolean registered;

    // ───── Bukkit 监听（上游 PlayerList Mixin onPlayerJoin / onPlayerLoggedOut 的事件等价）─────

    @EventHandler
    public void onJoin(PlayerJoinEvent event)
    {
        // 上游 :38-50 逐字：对所有非 memory 连接无条件发 HI（不 gate 客户端类型——vanilla 客户端
        // 把未知通道 payload 解码为 DiscardedPayload 后静默丢弃）。假人 join 同样进入此处，
        // HI 被其 FakeClientConnection.send no-op 吸收（上游同构，无连接类型分支）。
        sendHi(Nms.toNms(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event)
    {
        remoteCarpetClients.remove(event.getPlayer().getUniqueId());
    }

    // ───── C2S 入口（上游 onClientData :117-130 的键名分发同构）─────

    @Override
    public void receivePlayPayload(FriendlyByteBuf data, ServerPlayer player)
    {
        CompoundTag compound = data.readNbt();
        if (compound == null)
        {
            return;
        }
        for (String key : compound.keySet())
        {
            if (CarpetReference.KEY_HELLO.equals(key))
            {
                onHello(player, compound.getString(key).orElseThrow());
            }
            else if (KEY_CLIENT_COMMAND.equals(key))
            {
                handleClientCommand(player, (CompoundTag) compound.get(key));
            }
            else
            {
                Reference.logger().warning("[Carpet] Unknown carpet client data: " + key);
            }
        }
    }

    /** 上游 onHello :52-67 逐字（豁免 superSecretSetting gate——见类 javadoc 封闭清单）。 */
    private void onHello(ServerPlayer player, String version)
    {
        remoteCarpetClients.put(player.getUUID(), version);
        if (version.equals(CarpetReference.CARPET_VERSION))
        {
            Reference.logger().info("[Carpet] Player " + player.getName().getString() + " joined with a matching carpet client");
        }
        else
        {
            Reference.logger().warning("[Carpet] Player " + player.getName().getString() + " joined with another carpet version: " + version);
        }
        sendRules(player);
    }

    /** 上游 onPlayerJoin :42-44 逐字：HI 帧 = {"69": carpetVersion}。 */
    public void sendHi(ServerPlayer player)
    {
        CompoundTag data = new CompoundTag();
        data.putString(CarpetReference.KEY_HI, CarpetReference.CARPET_VERSION);
        CarpetPacketSender.send(player, data);
    }

    /** 全量规则帧（上游 onHello :64-66 的 forEachManager 全规则口径——含默认值规则）。 */
    private void sendRules(ServerPlayer player)
    {
        SettingsManager sm = CarpetModule.getSettingsManager();
        if (sm == null)
        {
            return;
        }
        CarpetPacketSender.send(player, buildRulesFrame(sm.getRules(), sm.identifier()));
    }

    /** 规则变更推送（上游 updateRuleWithConnectedClients :132-142；gate 封闭清单第 1 处）。 */
    public void onRuleChanged(CommandSender source, ParsedRule<?> rule)
    {
        if (CarpetSettings.superSecretSetting)
        {
            return;
        }
        SettingsManager sm = CarpetModule.getSettingsManager();
        if (sm == null)
        {
            return;
        }
        CompoundTag frame = buildRulesFrame(List.of(rule), sm.identifier());
        for (UUID uuid : remoteCarpetClients.keySet())
        {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null)
            {
                CarpetPacketSender.send(Nms.toNms(online), frame);
            }
        }
    }

    /**
     * 自定义 NBT 广播（上游 broadcastCustomCommand :144-154；gate 封闭清单第 2 处）。
     * 上游唯一调用者 = scShapes（Scarpet 图形渲染，整体不移植）——B 侧作为机制面 API 暴露。
     */
    public void broadcastCustomCommand(String command, Tag data)
    {
        if (CarpetSettings.superSecretSetting)
        {
            return;
        }
        CompoundTag frame = new CompoundTag();
        frame.put(command, data);
        for (UUID uuid : remoteCarpetClients.keySet())
        {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null)
            {
                CarpetPacketSender.send(Nms.toNms(online), frame);
            }
        }
    }

    /** 定向自定义 NBT（上游 sendCustomCommand :156-162；名册门经 {@link #isValidCarpetPlayer}）。 */
    public void sendCustomCommand(ServerPlayer player, String command, Tag data)
    {
        if (!isValidCarpetPlayer(player))
        {
            return;
        }
        CompoundTag frame = new CompoundTag();
        frame.put(command, data);
        CarpetPacketSender.send(player, frame);
    }

    /** 上游 isValidCarpetPlayer :179-187 逐字（gate 封闭清单第 3 处）。 */
    public boolean isValidCarpetPlayer(ServerPlayer player)
    {
        if (CarpetSettings.superSecretSetting)
        {
            return false;
        }
        return remoteCarpetClients.containsKey(player.getUUID());
    }

    /**
     * clientCommand 执行（上游 handleClientCommand :80-115 同构 + B 侧增补 INFO 审计行）。
     * 命令以<b>玩家自身权限</b>执行（无提权）；畸形包缺键抛 NoSuchElementException → 框架
     * ProtocolChannel catch 记 warn 丢弃（不踢人——plugin messaging 路径结构性语义，偏差登记）。
     */
    private void handleClientCommand(ServerPlayer player, CompoundTag commandData)
    {
        String command = commandData.getString("command").orElseThrow();
        String id = commandData.getString("id").orElseThrow();
        List<Component> output = new ArrayList<>();
        Component[] error = { null };
        int[] returnValue = { 0 };
        if (player.level().getServer() == null)
        {
            error[0] = Component.literal("No Server");
        }
        else
        {
            player.level().getServer().getCommands().performPrefixedCommand(
                    new CarpetSnoopyCommandSource(player, error, output, returnValue), command);
        }
        // B 侧增补（上游无）：performPrefixedCommand 直调 NMS 命令链，绕过 PlayerCommandPreprocessEvent
        // 与 ops 广播——审计行补偿该缺口（偏差登记 docs/31-carpet.md §3）
        Reference.logger().info("[Carpet] clientCommand from " + player.getName().getString() + ": " + command);
        List<String> lines = new ArrayList<>(output.size());
        for (Component line : output)
        {
            lines.add(line.getString());
        }
        CompoundTag outer = new CompoundTag();
        outer.put(KEY_CLIENT_COMMAND, buildClientCommandReply(
                id, error[0] == null ? null : error[0].getContents().toString(), returnValue[0], lines));
        CarpetPacketSender.send(player, outer);
    }

    /** 模块卸载清理（上游 close :173-177 同构；通道注销由 CarpetModule.disable 经 ChannelManager 承载）。 */
    public void shutdown()
    {
        remoteCarpetClients.clear();
    }

    // ───── 帧构造（纯函数，单测面）─────

    /** Rules 全量帧：外层 {"Rules": {key: {Value, Manager, Rule}}}，撞名 key 追加 "2"（上游 :219-239 逐字）。 */
    static CompoundTag buildRulesFrame(Collection<ParsedRule<?>> rules, String managerIdentifier)
    {
        CompoundTag frame = new CompoundTag();
        CompoundTag rulesTag = new CompoundTag();
        for (ParsedRule<?> rule : rules)
        {
            String key = rule.name;
            while (rulesTag.contains(key))
            {
                key = key + "2";
            }
            CompoundTag entry = new CompoundTag();
            entry.putString("Value", String.valueOf(rule.value()));
            entry.putString("Manager", managerIdentifier);
            entry.putString("Rule", rule.name);
            rulesTag.put(key, entry);
        }
        frame.put(KEY_RULES, rulesTag);
        return frame;
    }

    /** clientCommand 回执：{id, error?, return, output?}——output 空则不含键（上游 :97-112 逐字）。 */
    static CompoundTag buildClientCommandReply(String id, String error, int returnValue, List<String> output)
    {
        CompoundTag result = new CompoundTag();
        result.putString("id", id);
        if (error != null)
        {
            result.putString("error", error);
        }
        result.putInt("return", returnValue);
        if (output != null && !output.isEmpty())
        {
            ListTag outputResult = new ListTag();
            for (String line : output)
            {
                outputResult.add(StringTag.valueOf(line));
            }
            result.put("output", outputResult);
        }
        return result;
    }

    // ───── IPluginServerPlayHandler 样板（黄金模板 JeiServerPlayHandler 形态）─────

    @Override
    public Identifier getPayloadChannel()
    {
        return CarpetReference.CARPET_CHANNEL;
    }

    @Override
    public boolean isPlayRegistered(Identifier channel)
    {
        return registered;
    }

    @Override
    public void setPlayRegistered(Identifier channel)
    {
        this.registered = true;
    }

    @Override
    public void clearPlayRegistered(Identifier channel)
    {
        this.registered = false;
    }

    @Override
    public void reset(Identifier channel)
    {
        // 名册生命周期由 join/quit/shutdown 承载，无跨包缓冲
    }

    @Override
    public void encodeWithSplitter(ServerPlayer player, FriendlyByteBuf buf)
    {
        // S2C 全走 CarpetPacketSender NMS 直发且帧恒 KB 级，不走 PacketSplitter
    }
}
